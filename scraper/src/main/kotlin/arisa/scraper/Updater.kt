package arisa.scraper

import arisa.library.LibraryCodec
import arisa.library.ReleaseAsset
import arisa.library.ReleaseManifest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 自动更新. 定时读 GitHub Release 的 latest.json:
 * - 有新版 TV App 时把 APK 转存到网盘 `/.arisa/app/`, TV 在设置里手动更新 (电视上连 GitHub 不稳);
 * - 有新版刮削器时下载解压到 `~/.arisa/versions/<版本>`, 把 `~/.arisa/current` 指过去,
 *   等手上的任务做完退出, launchd 用新版重启. 只对 install.sh 装的 (从 versions 目录运行的) 生效, 开发版不动.
 */
class Updater(
    private val home: File,
    private val drive: Drive,
    private val http: () -> OkHttpClient,
    private val log: (String) -> Unit,
    /** 没有任务在跑, 可以重启. */
    private val idle: () -> Boolean,
    private val restart: () -> Unit,
    private val current: String = currentVersion(),
    private val installDir: File? = installDir(),
    private val manifestUrl: String = ReleaseManifest.GITHUB_LATEST,
    private val assetUrl: (version: String, name: String) -> String = ReleaseManifest::assetUrl,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }
    private val versions = File(home, "versions")
    private val currentLink = File(home, "current")

    /** 最近一次读到的最新版本, 给网页显示. */
    @Volatile var latest: ReleaseManifest? = null
        private set
    /** 网盘里 TV App 的版本. */
    @Volatile var appOnDrive: String? = null
        private set
    @Volatile var lastCheck: Long? = null
        private set
    @Volatile var lastError: String? = null
        private set

    /** 是 install.sh 装的 (从 ~/.arisa/versions 运行), 会自动更新; 否则是开发版. */
    val managed get() = installDir?.parentFile?.canonicalFile == versions.canonicalFile

    /** 检查一次. [selfUpdate] / [mirrorApp] 对应配置里的开关. */
    fun check(selfUpdate: Boolean, mirrorApp: Boolean) {
        lastCheck = System.currentTimeMillis()
        try {
            val text = try { String(get(manifestUrl)) } catch (e: NotFound) { error("GitHub 上还没有发布过版本") }
            val manifest = json.decodeFromString(ReleaseManifest.serializer(), text)
            latest = manifest
            if (mirrorApp) manifest.app?.let { mirrorApp(manifest, it) }
            if (selfUpdate) manifest.scraper?.let { selfUpdate(manifest, it) }
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            throw e
        }
    }

    // ---------- TV App 转存到网盘 ----------

    private fun mirrorApp(manifest: ReleaseManifest, apk: ReleaseAsset) {
        val root = drive.list(drive.rootFid).firstOrNull { it.isDir && it.name == LibraryCodec.DIR }?.fid
            ?: drive.mkdir(drive.rootFid, LibraryCodec.DIR)
        val dir = drive.list(root).firstOrNull { it.isDir && it.name == ReleaseManifest.APP_DIR }?.fid ?: drive.mkdir(root, ReleaseManifest.APP_DIR)
        val files = drive.list(dir)
        val existing = files.firstOrNull { it.name == ReleaseManifest.FILE }?.let { f ->
            runCatching { json.decodeFromString(ReleaseManifest.serializer(), String(drive.download(f.fid))) }.getOrNull()
        }
        appOnDrive = existing?.version
        if (existing != null && existing.versionCode >= manifest.versionCode && files.any { it.name == apk.name }) return

        log("转存 TV App ${manifest.version} 到网盘")
        val bytes = download(manifest.version, apk)
        if (files.none { it.name == apk.name }) drive.upload(dir, apk.name, bytes)
        // 先删旧清单再传新的, 同名上传夸克会自动改名
        drive.delete(files.filter { it.name == ReleaseManifest.FILE }.map { it.fid })
        val forTv = manifest.copy(scraper = null)
        drive.upload(dir, ReleaseManifest.FILE, json.encodeToString(ReleaseManifest.serializer(), forTv).toByteArray())
        // 旧 APK 只留上一个版本
        val oldApks = files.filter { it.name.endsWith(".apk") && it.name != apk.name }.sortedByDescending { it.updatedAt }.drop(1)
        drive.delete(oldApks.map { it.fid })
        appOnDrive = manifest.version
        log("TV App ${manifest.version} 已转存到 /${LibraryCodec.DIR}/${ReleaseManifest.APP_DIR}/${apk.name}")
    }

    // ---------- 刮削器自己 ----------

    private fun selfUpdate(manifest: ReleaseManifest, zip: ReleaseAsset) {
        val dir = installDir
        if (dir == null || !managed) return
        if (manifest.versionCode <= ReleaseManifest.versionCode(current)) return

        val target = File(versions, manifest.version)
        if (!target.isDirectory) {
            log("下载刮削器 ${manifest.version}")
            val tmp = File(versions, "${manifest.version}.tmp").apply { deleteRecursively() }
            unzip(download(manifest.version, zip), tmp)
            File(tmp, "bin").listFiles()?.forEach { it.setExecutable(true) }
            // 先在新版里跑一下, 起不来就不切换
            val probe = ProcessBuilder(File(tmp, "bin/arisa-scraper").path, "version").redirectErrorStream(true).start()
            val out = probe.inputStream.bufferedReader().readText().trim()
            if (!probe.waitFor(60, TimeUnit.SECONDS) || probe.exitValue() != 0 || out.lines().lastOrNull() != manifest.version) {
                tmp.deleteRecursively()
                error("新版 ${manifest.version} 试运行失败: ${out.take(300)}")
            }
            check(tmp.renameTo(target)) { "改名失败 $tmp" }
        }
        switchTo(target)
        // 版本目录只留当前和上一个, 出问题时可以手动切回去
        versions.listFiles()?.filter { it.isDirectory && it != target && it.canonicalFile != dir.canonicalFile }
            ?.forEach { it.deleteRecursively() }

        log("刮削器已更新到 ${manifest.version}, 等当前任务做完后重启")
        while (!idle()) Thread.sleep(5_000)
        restart()
    }

    /** 把 current 链接原子地换成指向 [target]. */
    private fun switchTo(target: File) {
        val tmp = File(home, "current.new").toPath()
        Files.deleteIfExists(tmp)
        // 相对链接 versions/<版本>, 整个 ~/.arisa 挪地方也不坏
        Files.createSymbolicLink(tmp, java.nio.file.Paths.get(versions.name, target.name))
        Files.move(tmp, currentLink.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    // ---------- 下载 ----------

    private fun get(url: String): ByteArray =
        http().newCall(Request.Builder().url(url).header("User-Agent", "arisa-scraper/$current").build()).execute().use { r ->
            if (r.code == 404) throw NotFound(url)
            if (!r.isSuccessful) error("下载失败 HTTP ${r.code}: $url")
            r.body!!.bytes()
        }

    private fun download(version: String, asset: ReleaseAsset): ByteArray {
        val bytes = get(assetUrl(version, asset.name))
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(bytes.size.toLong() == asset.size && sha == asset.sha256) { "${asset.name} 校验失败 (大小 ${bytes.size}, sha256 $sha)" }
        return bytes
    }

    private class NotFound(url: String) : IllegalStateException("下载失败 HTTP 404: $url")

    companion object {
        /** 编译时写进去的版本号, 开发版是 0.0.0-dev. */
        fun currentVersion(): String =
            Updater::class.java.getResourceAsStream("/arisa/scraper/version.txt")?.use { String(it.readBytes()).trim() } ?: "0.0.0-dev"

        /** 安装目录 (里面有 bin/ lib/), 从自己的 jar 的位置推出来; 从 IDE 或 classes 目录跑时是 null. */
        fun installDir(): File? {
            val jar = runCatching { File(Updater::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull() ?: return null
            if (!jar.isFile || jar.parentFile?.name != "lib") return null
            return jar.parentFile.parentFile
        }

        /** 解压 distZip 打的包, 去掉最外层的 arisa-scraper-x.y.z/ 目录. */
        fun unzip(bytes: ByteArray, to: File) {
            val root = to.canonicalFile
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val out = File(root, rel).canonicalFile
                    require(out.path.startsWith(root.path + File.separator)) { "压缩包里有越界路径: ${e.name}" }
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
    }
}
