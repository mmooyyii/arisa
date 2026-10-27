package arisa.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import arisa.library.Library
import arisa.library.LibraryCodec
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
) {
    sealed interface Status {
        data object Idle : Status
        data object Loading : Status
        /** 网盘里还没有索引, 刮削器没跑过. */
        data object NoIndex : Status
        data class Error(val message: String) : Status
    }

    private val cacheFile = File(context.filesDir, "library.json.gz")
    private val cacheNameFile = File(context.filesDir, "library.name")
    /** /.arisa 目录的 fid, 省得每次刷新都列一遍网盘根目录. */
    private val dirFidFile = File(context.filesDir, "library.dir")
    private val mutex = Mutex()

    private val _library = MutableStateFlow<Library?>(null)
    val library: StateFlow<Library?> = _library

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        if (_library.value != null) return@withContext
        if (session.demo) {
            _library.value = context.assets.open("demo-library.json").use {
                LibraryCodec.json.decodeFromString(Library.serializer(), it.readBytes().decodeToString())
            }
            return@withContext
        }
        if (cacheFile.exists()) {
            _library.value = runCatching { LibraryCodec.decode(cacheFile.readBytes()).playableFirst() }.getOrNull()
        }
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
                        val lib = LibraryCodec.decode(bytes).playableFirst()
                        cacheFile.writeBytes(bytes)
                        cacheNameFile.writeText(latest)
                        _library.value = lib
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
        _library.value = null
    }

    fun item(id: String): MediaItem? = _library.value?.items?.firstOrNull { it.id == id }

    /** 没识别出来的视频, 不属于任何作品, 按 fid 找. */
    fun unmatchedFile(fid: String): MediaFile? = _library.value?.unmatched?.firstOrNull { it.fid == fid }

    /** 播放用的原文件直链 (要带 Cookie), 每次播放前现取 (直链会过期). */
    suspend fun playLink(file: MediaFile): QuarkLink = withContext(Dispatchers.IO) {
        if (session.demo) return@withContext QuarkLink(DEMO_VIDEO)
        session.client.downloadLink(file.fid)
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

