package arisa.scraper

import arisa.library.Library
import arisa.library.LibrarySearch
import arisa.library.MediaItem
import arisa.library.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals

class PinyinTest {
    @Test fun encode() {
        assertEquals("liu lang di|de qiu 2", Pinyin.encode("流浪地球 2"))
        assertEquals(listOf("chang", "zhang"), Pinyin.encode("长安").split(' ')[0].split('|').sorted())
        assertEquals("zhi zhu xia s h", Pinyin.encode("蜘蛛侠：S·H"))
        assertEquals("lv", Pinyin.encode("吕"))
    }

    @Test fun searchableOnTv() {
        val lib = Pinyin.annotate(
            Library(generatedAt = 0, items = listOf(MediaItem(id = "movie:1", type = MediaType.MOVIE, title = "长安三万里", directors = listOf("谢君伟"))))
        )
        assertEquals(setOf("长安三万里", "谢君伟"), lib.pinyin.keys)
        val search = LibrarySearch(lib)
        for (q in listOf("casw", "changan", "xjw")) assertEquals(1, search.search(q).size, q)
    }
}
