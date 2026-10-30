package arisa.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import arisa.app.BuildConfig
import arisa.library.LibraryCodec
import arisa.library.ReleaseManifest
import arisa.quark.QuarkWebClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * App 更新: 刮削器把新版 APK 和 latest.json 转存到网盘 `/.arisa/app/` (电视上连 GitHub 不稳),
 * 这里读清单、下载、校验, 再交给系统安装器. 安装要用户在电视上点确认.
 */
class AppUpdater(
    private val context: Context,
    private val session: SessionStore,
    private val http: OkHttpClient,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        /** 网盘里还没有 App 安装包 (刮削器没开转存, 或者还没发过版). */
        data object NotFound : State
        data class Available(val manifest: ReleaseManifest) : State
        data class Downloading(val manifest: ReleaseManifest, val progress: Float) : State
        data class Ready(val manifest: ReleaseManifest, val apk: File) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val json = Json { ignoreUnknownKeys = true }
    private val dir get() = File(context.cacheDir, "update").apply { mkdirs() }

    /** 有新版本时 (还没装) 为 true, 首页提示用. */
    val hasUpdate get() = when (_state.value) { is State.Available, is State.Downloading, is State.Ready -> true; else -> false }

    suspend fun check() = withContext(Dispatchers.IO) {
        if (session.demo || !session.isLoggedIn) return@withContext
        if (_state.value is State.Downloading) return@withContext
        _state.value = State.Checking
        _state.value = try {
            val client = session.client
            val files = appDir(client)?.let(client::list).orEmpty()
            val manifestFile = files.firstOrNull { it.name == ReleaseManifest.FILE }
            val manifest = manifestFile?.let { json.decodeFromString(ReleaseManifest.serializer(), String(fetch(client, it.fid))) }
            when {
                manifest?.app == null || files.none { it.name == manifest.app!!.name } -> State.NotFound
                manifest.versionCode <= BuildConfig.VERSION_CODE -> State.UpToDate
                else -> File(dir, manifest.app!!.name).takeIf { it.exists() && verify(it.readBytes(), manifest) }
                    ?.let { State.Ready(manifest, it) } ?: State.Available(manifest)
            }
        } catch (e: Exception) {
            State.Error(e.message ?: e.toString())
        }
    }

    suspend fun download() = withContext(Dispatchers.IO) {
        val manifest = (_state.value as? State.Available)?.manifest ?: return@withContext
        val asset = manifest.app!!
        _state.value = State.Downloading(manifest, 0f)
        _state.value = try {
            val client = session.client
            val fid = appDir(client)?.let(client::list).orEmpty().first { it.name == asset.name }.fid
            dir.listFiles()?.forEach { it.delete() }
            val link = client.downloadLink(fid)
            val req = Request.Builder().url(link.url).apply { link.headers.forEach { (k, v) -> header(k, v) } }.build()
            val out = java.io.ByteArrayOutputStream(asset.size.toInt())
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "下载失败 ${resp.code}" }
                resp.body!!.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        _state.value = State.Downloading(manifest, out.size().toFloat() / asset.size)
                    }
                }
            }
            val bytes = out.toByteArray()
            check(verify(bytes, manifest)) { "安装包校验失败, 重新下载试试" }
            val apk = File(dir, asset.name).apply { writeBytes(bytes) }
            State.Ready(manifest, apk)
        } catch (e: Exception) {
            State.Error(e.message ?: e.toString())
        }
    }

    /**
     * 打开系统安装界面. 安卓 8 以上要先允许本 App 安装应用, 没允许时打开那个设置页, 返回 false.
     */
    fun install(): Boolean {
        val apk = (_state.value as? State.Ready)?.apk ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 有的电视系统没有这个设置页, 只能让用户自己去系统设置里找
            runCatching { context.startActivity(intent) }
                .onFailure { _state.value = State.Error("请在电视的系统设置里允许 Arisa 安装未知来源应用") }
            return false
        }
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        } else {
            // 安卓 6 的安装器读不了 content://, 放到外部缓存目录用 file://
            val external = File(context.externalCacheDir ?: return false, apk.name)
            apk.copyTo(external, overwrite = true)
            Uri.fromFile(external)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun appDir(client: QuarkWebClient): String? {
        val root = client.list(QuarkWebClient.ROOT).firstOrNull { it.isDir && it.name == LibraryCodec.DIR } ?: return null
        return client.list(root.fid).firstOrNull { it.isDir && it.name == ReleaseManifest.APP_DIR }?.fid
    }

    private fun fetch(client: QuarkWebClient, fid: String): ByteArray {
        val link = client.downloadLink(fid)
        val req = Request.Builder().url(link.url).apply { link.headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "下载失败 ${resp.code}" }
            resp.body!!.bytes()
        }
    }

    private fun verify(bytes: ByteArray, manifest: ReleaseManifest): Boolean {
        val asset = manifest.app ?: return false
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return bytes.size.toLong() == asset.size && sha == asset.sha256
    }
}
