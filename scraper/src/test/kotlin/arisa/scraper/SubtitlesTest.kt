package arisa.scraper

import arisa.quark.QuarkFile
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitlesTest {
    private fun f(name: String, dir: Boolean = false) = QuarkFile(name, name, dir, 0, 0, 0)

    @Test
    fun language() {
        assertEquals("zh-Hans", Subtitles.language(".chs.ass"))
        assertEquals("zh-Hans", Subtitles.language(".简体中文.srt"))
        assertEquals("zh-Hant", Subtitles.language("[CHT].ass"))
        assertEquals("zh-Hans", Subtitles.language(".chs&cht.ass"))
        assertEquals("en", Subtitles.language(".en.srt"))
        assertEquals("ja", Subtitles.language(".jpn.ass"))
        assertEquals(null, Subtitles.language(".srt"))
    }

    @Test
    fun matchByName() {
        val v1 = f("Show.S01E01.mkv")
        val siblings = listOf(v1, f("Show.S01E02.mkv"), f("Show.S01E01.chs.ass"), f("Show.S01E02.chs.ass"), f("Fonts", dir = true))
        assertEquals(listOf("Show.S01E01.chs.ass"), Subtitles.of(v1, siblings).map { it.name })
    }

    @Test
    fun onlyVideoTakesAll() {
        val v = f("movie.mkv")
        val subs = Subtitles.of(v, listOf(v, f("简体.srt"), f("English.srt")))
        assertEquals(listOf("English.srt" to "en", "简体.srt" to "zh-Hans"), subs.map { it.name to it.language })
    }
}
