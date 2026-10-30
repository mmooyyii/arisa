package arisa.scraper

import arisa.library.ReleaseAsset
import arisa.library.ReleaseManifest
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdaterTest {
    private val tmp = Files.createTempDirectory("updater").toFile()
    private val home = File(tmp, "home").apply { mkdirs() }
    private val driveRoot = File(tmp, "drive").apply { mkdirs() }
    private val files = mutableMapOf<String, ByteArray>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = files[ex.requestURI.path.removePrefix("/")]
            if (body == null) ex.sendResponseHeaders(404, -1) else {
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val json = Json { ignoreUnknownKeys = true }
    private var restarted = 0

    @AfterTest fun cleanup() { server.stop(0); tmp.deleteRecursively() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun asset(name: String, bytes: ByteArray): ReleaseAsset { files[name] = bytes; return ReleaseAsset(name, bytes.size.toLong(), sha(bytes)) }

    /** 假的刮削器安装包: bin/arisa-scraper 打印 [printed]. */
    private fun scraperZip(version: String, printed: String = version): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("arisa-scraper-$version/bin/arisa-scraper"))
            z.write("#!/bin/sh\necho $printed\n".toByteArray())
            z.putNextEntry(ZipEntry("arisa-scraper-$version/lib/scraper.jar"))
            z.write(byteArrayOf(1, 2, 3))
        }
        return out.toByteArray()
    }

    private fun publish(version: String, apk: ByteArray = "apk-$version".toByteArray(), zip: ByteArray = scraperZip(version)) {
        val m = ReleaseManifest(
            version, ReleaseManifest.versionCode(version),
            scraper = asset("arisa-scraper-$version.zip", zip),
            app = asset("arisa-$version.apk", apk),
        )
        files["latest.json"] = json.encodeToString(ReleaseManifest.serializer(), m).toByteArray()
    }

    private fun updater(current: String, installDir: File?) = Updater(
        home, LocalDrive(driveRoot), http = { OkHttpClient() }, log = {}, idle = { true }, restart = { restarted++ },
        current = current, installDir = installDir,
        manifestUrl = "$base/latest.json", assetUrl = { _, name -> "$base/$name" },
    )

    private fun installed(version: String) = File(home, "versions/$version").apply { File(this, "lib").mkdirs() }
    private fun appDir() = File(driveRoot, ".arisa/app")

    @Test fun mirrorsAppToDrive() {
        publish("0.2.0")
        updater("0.1.0", null).check(selfUpdate = true, mirrorApp = true)
        assertEquals("apk-0.2.0", File(appDir(), "arisa-0.2.0.apk").readText())
        val onDrive = json.decodeFromString(ReleaseManifest.serializer(), File(appDir(), "latest.json").readText())
        assertEquals("0.2.0", onDrive.version)
        assertEquals(null, onDrive.scraper)

        // 新版出来后换掉清单, 旧 APK 留一个
        publish("0.3.0")
        updater("0.1.0", null).check(selfUpdate = false, mirrorApp = true)
        publish("0.4.0")
        updater("0.1.0", null).check(selfUpdate = false, mirrorApp = true)
        assertEquals(setOf("latest.json", "arisa-0.4.0.apk", "arisa-0.3.0.apk"), appDir().list()!!.toSet())
    }

    @Test fun badChecksumRejected() {
        publish("0.2.0")
        files["arisa-0.2.0.apk"] = "tampered".toByteArray()
        assertFailsWith<IllegalStateException> { updater("0.1.0", null).check(selfUpdate = false, mirrorApp = true) }
        assertFalse(File(appDir(), "latest.json").exists())
    }

    @Test fun selfUpdateSwitchesAndRestarts() {
        publish("0.2.0")
        val old = installed("0.1.0")
        updater("0.1.0", old).check(selfUpdate = true, mirrorApp = false)
        assertEquals(1, restarted)
        val current = File(home, "current")
        assertEquals("versions/0.2.0", Files.readSymbolicLink(current.toPath()).toString())
        assertTrue(File(current, "bin/arisa-scraper").canExecute())
        assertTrue(old.exists())

        // 再升一次, 0.1.0 被清掉, 留当前运行的 0.2.0 和新的 0.3.0
        publish("0.3.0")
        updater("0.2.0", File(home, "versions/0.2.0")).check(selfUpdate = true, mirrorApp = false)
        assertEquals(setOf("0.2.0", "0.3.0"), File(home, "versions").list()!!.toSet())
    }

    @Test fun brokenReleaseNotInstalled() {
        publish("0.2.0", zip = scraperZip("0.2.0", printed = "boom"))
        assertFailsWith<IllegalStateException> { updater("0.1.0", installed("0.1.0")).check(selfUpdate = true, mirrorApp = false) }
        assertEquals(0, restarted)
        assertFalse(File(home, "current").exists())
        assertEquals(setOf("0.1.0"), File(home, "versions").list()!!.toSet())
    }

    @Test fun devBuildAndSameVersionSkipped() {
        publish("0.2.0")
        updater("0.0.0-dev", File(tmp, "repo/build/install/arisa-scraper")).check(selfUpdate = true, mirrorApp = false)
        updater("0.2.0", installed("0.2.0")).check(selfUpdate = true, mirrorApp = false)
        assertEquals(0, restarted)
    }
}
