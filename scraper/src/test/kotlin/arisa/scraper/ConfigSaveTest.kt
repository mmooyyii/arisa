package arisa.scraper

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigSaveTest {
    @Test fun roundTripOnlyNonDefaults() {
        val dir = Files.createTempDirectory("cfg").toFile()
        val file = File(dir, "config.yaml")
        file.writeText("# 旧文件\ntmdb:\n  api_key: abc\n")
        val config = Config.loadFile(file).copy(
            libraries = listOf(LibraryConfig("/电影", LibraryType.MOVIE), LibraryConfig("/动画", output = OutputMode.INDEX)),
            update = UpdateConfig(selfUpdate = false, proxy = "http://127.0.0.1:7890"),
        )
        Config.save(file, config)
        assertEquals(config, Config.loadFile(file))
        assertTrue(File(dir, "config.yaml.bak").readText().startsWith("# 旧文件"))
        val text = file.readText()
        assertFalse("poll_seconds" in text, text)   // 默认值不写
        assertTrue("self_update: false" in text, text)
        // 环境变量里的 key 不进文件
        assertEquals("abc", Config.load(file, mapOf("ARISA_TMDB_API_KEY" to "env")).let { Config.loadFile(file).tmdb.apiKey })
        dir.deleteRecursively()
    }
}
