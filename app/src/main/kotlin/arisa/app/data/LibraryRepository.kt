package arisa.app.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import arisa.library.Library
import arisa.library.LibraryBrowser
import arisa.library.LibraryCodec
import arisa.library.LibrarySearch
import arisa.library.MediaCollection
import arisa.library.MediaFile
import arisa.library.MediaItem
import arisa.library.SubtitleFile
import arisa.quark.QuarkFile
import arisa.quark.QuarkLink
import arisa.quark.QuarkWebClient
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 片库: 读 mac mini 写进夸克的 /.arisa/library-*.json.gz.
 * 先用本地缓存立刻显示, 后台比对文件名, 有新版本才下载.
 */
class LibraryRepository(
    private val context: Context,
    private val session: SessionStore,
    private val http: OkHttpClient,
    /** 不跟着页面走的后台任务: 提前取直链. */
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object Idle : Status
        data object Loading : Status
        /** 网盘里还没有索引, 刮削器没跑过. */
        data object NoIndex : Status
        data class Error(val message: String) : Status
        /** 片库是新版刮削器按新格式写的, 这个版本的 App 读不了, 要到设置里更新. 继续显示旧的片库. */
        data object NeedsAppUpdate : Status
    }

    private val cacheFile = File(context.filesDir, "library.json.gz")
    private val cacheNameFile = File(context.filesDir, "library.name")
    /** /.arisa 目录的 fid, 省得每次刷新都列一遍网盘根目录. */
    private val dirFidFile = File(context.filesDir, "library.dir")
    private val mutex = Mutex()
    private val loadMutex = Mutex()

    private val _library = MutableStateFlow<Library?>(null)
    val library: StateFlow<Library?> = _library

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    /** App 一启动就调一次, 首页出来时再调一次: 第二次等第一次读完, 不重复解析. */
    suspend fun loadCached() = withContext(Dispatchers.IO) { loadMutex.withLock { loadCachedLocked() } }

    private fun loadCachedLocked() {
        if (_library.value != null) return
        if (session.demo) {
            _library.value = context.assets.open("demo-library.json").use {
                LibraryCodec.json.decodeFromString(Library.serializer(), it.readBytes().decodeToString())
            }
            return
        }
        if (cacheFile.exists()) {
            _library.value = runCatching { LibraryCodec.decode(cacheFile.readBytes()).takeIf { it.schema <= Library.SCHEMA }?.playableFirst() }.getOrNull()
        }
        warm()
    }

    /** 在后台线程把搜索、筛选要用的东西先建好, 页面打开时直接用, 不占主线程. */
    private fun warm() {
        index()?.run { browser; search }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (session.demo || !session.isLoggedIn) return@withContext
        mutex.withLock {
            _status.value = Status.Loading
            _status.value = try {
                val client = session.client
                val files = indexFiles(client)
                val latest = LibraryCodec.latest(files.map { it.name })
                if (latest == null) {
                    Status.NoIndex
                } else {
                    if (latest != cacheNameFile.takeIf { it.exists() }?.readText() || _library.value == null) {
                        val fid = files.first { it.name == latest }.fid
                        val bytes = fetch(client.downloadLink(fid))
                        val lib = LibraryCodec.decode(bytes)
                        // 格式比 App 认识的新: 新版刮削器做了不兼容的改动, 不用它, 免得读出错的东西
                        if (lib.schema > Library.SCHEMA) return@withLock Status.NeedsAppUpdate.also { _status.value = it }
                        cacheFile.writeBytes(bytes)
                        cacheNameFile.writeText(latest)
                        _library.value = lib.playableFirst()
                        warm()
                    }
                    Status.Idle
                }
            } catch (e: Exception) {
                Status.Error(e.message ?: e.toString())
            }
        }
    }

    /**
     * 列出 /.arisa 里的文件. 先用缓存的目录 fid, 列不出索引 (目录被删、重建) 时再从根目录找一遍.
     */
    private fun indexFiles(client: QuarkWebClient): List<QuarkFile> {
        dirFidFile.takeIf { it.exists() }?.readText()?.let { fid ->
            val files = runCatching { client.list(fid) }.getOrNull()
            if (files != null && LibraryCodec.latest(files.map { it.name }) != null) return files
        }
        val dir = client.list(QuarkWebClient.ROOT).firstOrNull { it.isDir && it.name == LibraryCodec.DIR }
            ?: return emptyList<QuarkFile>().also { dirFidFile.delete() }
        dirFidFile.writeText(dir.fid)
        return client.list(dir.fid)
    }

    fun clear() {
        cacheFile.delete()
        cacheNameFile.delete()
        dirFidFile.delete()
        prefetched = null
        _library.value = null
    }

    fun item(id: String): MediaItem? = index()?.items?.get(id)

    fun collection(id: String): MediaCollection? = index()?.collections?.get(id)

    /** 没识别出来的视频, 不属于任何作品, 按 fid 找. */
    fun unmatchedFile(fid: String): MediaFile? = index()?.unmatched?.get(fid)

    /** 片库页、首页分类行用的筛选器. 和 [search] 一样按片库缓存, 片库换了才重建. */
    fun browser(): LibraryBrowser? = index()?.browser

    /**
     * 搜索索引. 建一次要把每部作品的片名、别名、演员都过一遍, 页面每次打开 (包括从详情页返回) 都重建的话会卡一下,
     * 所以放在这里, 片库加载完就在后台建好.
     */
    fun search(): LibrarySearch? = index()?.search

    /** 按 id 查的索引, 片库换了才重建. 合集页、详情页每次查都扫一遍列表, 片子多了慢. */
    private class Index(val library: Library) {
        val items = library.items.associateBy { it.id }
        val collections = library.collections.associateBy { it.id }
        val unmatched = library.unmatched.associateBy { it.fid }
        val browser by lazy { LibraryBrowser(library) }
        val search by lazy { LibrarySearch(library) }
    }

    @Volatile private var index: Index? = null

    private fun index(): Index? {
        val lib = _library.value ?: return null
        return index?.takeIf { it.library === lib } ?: Index(lib).also { index = it }
    }

    /** 提前取好的直链. 只留最近的一个 (详情页上默认要播的那个文件). */
    private class Prefetched(val fid: String, val link: Deferred<QuarkLink>, private val at: Long) {
        fun fresh() = SystemClock.elapsedRealtime() - at < LINK_TTL_MS
    }

    @Volatile private var prefetched: Prefetched? = null

    /**
     * 进详情页时把默认要播的那个文件的直链先取好, 按播放时少等一次请求.
     * 顺手用它发一个 1 字节的请求: 和夸克下载服务器的连接 (DNS、TLS 握手) 先建好留在连接池里, 起播的第一个请求直接用.
     */
    fun prefetchLink(fid: String) {
        if (session.demo || !session.isLoggedIn) return
        if (prefetched?.let { it.fid == fid && it.fresh() } == true) return
        val client = session.client
        prefetched = Prefetched(
            fid,
            scope.async(Dispatchers.IO) { client.downloadLink(fid).also { link -> scope.launch(Dispatchers.IO) { runCatching { warm(link) } } } },
            SystemClock.elapsedRealtime(),
        )
    }

    private fun warm(link: QuarkLink) {
        val req = Request.Builder().url(link.url).apply { link.headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=0-0").build()
        // 读完才会把连接还给连接池
        http.newCall(req).execute().use { it.body?.bytes() }
    }

    /**
     * 播放用的原文件直链 (要带 Cookie). 直链会过期: 提前取好的只在 [LINK_TTL_MS] 内用, 否则现取.
     *
     * @param fresh 一定现取 (播放出错后重试, 手上的直链多半已经失效).
     */
    suspend fun playLink(file: MediaFile, fresh: Boolean = false): QuarkLink {
        if (session.demo) return QuarkLink(DEMO_VIDEO)
        val ready = prefetched
        if (fresh) {
            prefetched = null
        } else if (ready != null && ready.fid == file.fid && ready.fresh()) {
            // 提前取的那次失败了就照常现取
            runCatching { ready.link.await() }.getOrNull()?.let { return it }
        }
        return withContext(Dispatchers.IO) { session.client.downloadLink(file.fid) }
    }

    /**
     * 外挂字幕下载到本地缓存, 统一转成 UTF-8 (中文字幕常是 GBK / Big5, 播放器只认 UTF-8).
     */
    suspend fun subtitle(sub: SubtitleFile): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val out = File(dir, sub.fid + "." + sub.name.substringAfterLast('.', "srt").lowercase())
        if (out.exists()) return@withContext out
        val text = decodeText(fetch(session.client.downloadLink(sub.fid)), sub.language)
        val tmp = File(dir, out.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(out)
        out
    }

    private fun decodeText(bytes: ByteArray, language: String?): String {
        fun strict(charset: Charset) = runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() || bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())) {
            return String(bytes, Charsets.UTF_16)
        }
        return (strict(Charsets.UTF_8)
            ?: (if (language == "zh-Hant") strict(Charset.forName("Big5")) else null)
            ?: strict(Charset.forName("GB18030"))
            ?: String(bytes, Charsets.UTF_8)).removePrefix("\uFEFF")
    }

    private fun fetch(link: QuarkLink): ByteArray {
        val req = Request.Builder().url(link.url).apply { link.headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "下载失败 ${resp.code}" }
            resp.body!!.bytes()
        }
    }

    companion object {
        /**
         * 提前取的直链最多放这么久. 夸克直链的有效期没有公开的说法, 取短一点;
         * 真过期了播放器会报错, 走出错重试那条路现取一个新的.
         */
        const val LINK_TTL_MS = 5 * 60_000L

        /** 演示模式下所有片子都播这个. */
        const val DEMO_VIDEO = "https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4"
    }
}

/**
 * 同一集 / 同一部有多个版本时, 把电视硬解不了的版本排到后面, 各处取第一个文件就能播.
 * 4:4:4 色度的 HEVC (压制组标 yuv444p10le / Hi444) 几乎没有硬件解码器支持, 硬接会出绿屏.
 */
private fun Library.playableFirst(): Library {
    fun List<MediaFile>.sorted() = sortedBy { CHROMA_444.containsMatchIn(it.path) }
    return copy(items = items.map { item ->
        item.copy(
            files = item.files.sorted(),
            seasons = item.seasons.map { s -> s.copy(episodes = s.episodes.map { e -> e.copy(files = e.files.sorted()) }) },
        )
    })
}

private val CHROMA_444 = Regex("""yuv444|444p|hi444""", RegexOption.IGNORE_CASE)

