package arisa.app.data

import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import arisa.quark.QuarkWebClient
import arisa.library.LibraryCodec
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import arisa.library.Episode
import arisa.library.Library
import arisa.library.MediaItem
import arisa.library.MediaType
import arisa.library.PlaybackProgress
import arisa.library.ProgressSync

/**
 * 观看进度, key = 文件 fid. 本地存一份, 网盘 /.arisa/progress-<设备ID>.json 存一份, 多台设备靠它同步:
 * 启动时和退出播放时合并 (同一个文件按更新时间新的为准), 本地有变化才写回网盘.
 * 同步时顺便清掉片库里已经没有的文件的进度, 规则见 [ProgressSync.prune].
 */
class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), PlaybackProgress.serializer())

    private val _all = MutableStateFlow(
        prefs.getString("all", null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()
    )
    val all: StateFlow<Map<String, PlaybackProgress>> = _all

    fun get(fid: String) = _all.value[fid]

    /** 本地有没同步到网盘的改动. */
    @Volatile private var dirty = prefs.getBoolean("dirty", false)
    /** 每存一次加一, 同步期间又存过就不清 dirty. */
    private val version = AtomicLong()
    private val syncLock = Mutex()

    /** 存盘用的后台线程, 一次只跑一个. */
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /**
     * 记下进度. [flush] 为 true 时当场存盘 (退出播放、换集、播完: 之后 App 可能马上被系统关掉);
     * 播放中每隔几秒记一次的传 false, 存盘放到后台线程: 存一次要把全部进度转成 JSON, 不占主线程.
     * 内存里的值两种情况都是马上更新的.
     */
    fun save(fid: String, positionMs: Long, durationMs: Long, flush: Boolean = true) {
        _all.update { it + (fid to PlaybackProgress(positionMs, durationMs, System.currentTimeMillis())) }
        version.incrementAndGet()
        dirty = true
        if (flush) persist() else writer.launch { persist() }
    }

    /** 加锁: 主线程、后台存盘、同步都会调, 保证后存的不比先存的旧. */
    @Synchronized
    private fun persist() {
        prefs.edit().putString("all", json.encodeToString(serializer, _all.value)).putBoolean("dirty", dirty).apply()
    }

    /** 这台设备的 ID, 网盘上每台设备写自己的文件. */
    private val device = prefs.getString("device", null) ?: UUID.randomUUID().toString().take(8).also {
        prefs.edit().putString("device", it).apply()
    }

    /**
     * 和网盘上各设备的进度合并, 本地有新的就写回自己的文件. 失败不影响本地进度, 下次再同步.
     *
     * 每台设备只写 progress-<设备ID>.json, 没有写冲突; 读的时候所有设备的文件一起合并,
     * 同一个视频取更新时间最新的. 本地在当前值上合并, 同步期间新存的进度不会被盖掉.
     *
     * [library] 是当前的片库, 用来清掉已经不在片库里的文件的进度; 还没加载出来时传 null, 这次不清.
     */
    suspend fun sync(client: QuarkWebClient, library: Library?) = syncLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = client.list(QuarkWebClient.ROOT).firstOrNull { it.isDir && it.name == LibraryCodec.DIR }
                    ?: return@runCatching
                val startVersion = version.get()
                val now = System.currentTimeMillis()
                fun clean(all: Map<String, PlaybackProgress>) = if (library == null) all else ProgressSync.prune(all, library, now)

                val files = client.list(dir.fid).filter { it.name.startsWith("progress-") && it.name.endsWith(".json") }
                val contents = files.mapNotNull { f ->
                    runCatching { json.decodeFromString(serializer, client.download(f.fid).decodeToString()) }.getOrNull()?.let { f to it }
                }
                // 旧版 App 不清理, 它的文件里还有该清的条目: 合并完按同一条规则再筛一遍
                val remote = clean(contents.fold(emptyMap()) { acc, (_, m) -> ProgressSync.merge(acc, m) })
                val merged = _all.updateAndGet { clean(ProgressSync.merge(it, remote)) }
                val mine = files.filter { it.name.startsWith("progress-$device") }
                // 自己的文件里有已经清掉的条目时也重写一次, 让网盘上的文件跟着变小
                val stale = contents.any { (f, m) -> f in mine && m.keys.any { it !in merged } }
                if (merged != remote || stale) {
                    // 夸克不覆盖同名文件: 先用临时名上传, 删掉自己的旧文件, 再改回正式名字
                    val fid = client.upload(dir.fid, "progress-$device-${System.currentTimeMillis()}.json", json.encodeToString(serializer, merged).toByteArray())
                    if (mine.isNotEmpty()) client.delete(mine.map { it.fid })
                    client.rename(fid, "progress-$device.json")
                }
                if (version.get() == startVersion) dirty = false
                persist()
            }
        }
    }

    /** 本地有改动才同步, 退出播放时用. */
    suspend fun syncIfDirty(client: QuarkWebClient, library: Library?) {
        if (dirty) sync(client, library)
    }

    /** 继续观看: 看了一半的, 以及看完一集后的下一集. 按最近观看排序, 只留最近 [CONTINUE_LIMIT] 个. */
    fun continueWatching(library: Library, progress: Map<String, PlaybackProgress>): List<ContinueEntry> {
        val entries = mutableListOf<ContinueEntry>()
        for (item in library.items) {
            when (item.type) {
                MediaType.MOVIE -> {
                    val (file, p) = item.files.mapNotNull { f -> progress[f.fid]?.let { f to it } }.maxByOrNull { it.second.updatedAt } ?: continue
                    if (!p.finished && p.positionMs > 60_000) entries += ContinueEntry(item, null, file.fid, p, p.updatedAt)
                }
                MediaType.TV -> {
                    val episodes = item.seasons.flatMap { it.episodes }
                    val watched = episodes.mapIndexedNotNull { i, e ->
                        e.files.mapNotNull { f -> progress[f.fid] }.maxByOrNull { it.updatedAt }?.let { Triple(i, e, it) }
                    }.maxByOrNull { it.third.updatedAt } ?: continue
                    val (index, episode, p) = watched
                    if (!p.finished) {
                        if (p.positionMs > 60_000) entries += ContinueEntry(item, episode, episode.files.first().fid, p, p.updatedAt)
                    } else {
                        val next = episodes.getOrNull(index + 1) ?: continue
                        entries += ContinueEntry(item, next, next.files.first().fid, null, p.updatedAt)
                    }
                }
            }
        }
        return entries.sortedByDescending { it.updatedAt }.take(CONTINUE_LIMIT)
    }

    data class ContinueEntry(
        val item: MediaItem,
        val episode: Episode?,
        val fid: String,
        val progress: PlaybackProgress?,
        val updatedAt: Long,
    )

    private companion object {
        const val CONTINUE_LIMIT = 10
    }
}
