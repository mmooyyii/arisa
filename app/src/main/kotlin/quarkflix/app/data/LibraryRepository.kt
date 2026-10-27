package quarkflix.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import quarkflix.library.Library
import quarkflix.library.LibraryCodec
import quarkflix.library.MediaFile
import quarkflix.library.MediaItem
import quarkflix.quark.QuarkLink
import quarkflix.quark.QuarkTvClient
import java.io.File

/**
 * 片库: 读 mac mini 写进夸克的 /.quarkflix/library-*.json.gz.
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
            _library.value = runCatching { LibraryCodec.decode(cacheFile.readBytes()) }.getOrNull()
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (session.demo || !session.isLoggedIn) return@withContext
        mutex.withLock {
            _status.value = Status.Loading
            _status.value = try {
                val client = session.client
                val dir = client.list(QuarkTvClient.ROOT).firstOrNull { it.isDir && it.name == LibraryCodec.DIR }
                val files = dir?.let { client.list(it.fid) }.orEmpty()
                val latest = LibraryCodec.latest(files.map { it.name })
                if (latest == null) {
                    Status.NoIndex
                } else {
                    if (latest != cacheNameFile.takeIf { it.exists() }?.readText() || _library.value == null) {
                        val fid = files.first { it.name == latest }.fid
                        val bytes = fetch(client.downloadLink(fid))
                        val lib = LibraryCodec.decode(bytes)
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

    fun clear() {
        cacheFile.delete()
        cacheNameFile.delete()
        _library.value = null
    }

    fun item(id: String): MediaItem? = _library.value?.items?.firstOrNull { it.id == id }

    /** 播放用的直链, 每次播放前现取 (直链会过期). */
    suspend fun playLink(file: MediaFile, transcoded: Boolean): QuarkLink = withContext(Dispatchers.IO) {
        if (session.demo) return@withContext QuarkLink(DEMO_VIDEO)
        val client = session.client
        if (transcoded) {
            val best = client.streamingLinks(file.fid).maxByOrNull { it.width * it.height }
            if (best != null) return@withContext QuarkLink(best.url, mapOf("User-Agent" to QuarkTvClient.USER_AGENT))
        }
        client.downloadLink(file.fid)
    }

    private fun fetch(link: QuarkLink): ByteArray {
        val req = Request.Builder().url(link.url).apply { link.headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "下载索引失败 ${resp.code}" }
            resp.body!!.bytes()
        }
    }

    companion object {
        /** 演示模式下所有片子都播这个. */
        const val DEMO_VIDEO = "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    }
}
