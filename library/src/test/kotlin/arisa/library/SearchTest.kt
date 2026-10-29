package arisa.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchTest {
    private fun item(id: String, title: String, year: Int? = null, aliases: List<String> = emptyList(), cast: List<String> = emptyList(), directors: List<String> = emptyList(), genres: List<String> = emptyList(), addedAt: Long = 0) =
        MediaItem(id = id, type = MediaType.MOVIE, title = title, aliases = aliases, year = year, cast = cast.map { CastMember(it) }, directors = directors, genres = genres, addedAt = addedAt)

    private val lib = Library(
        generatedAt = 0,
        items = listOf(
            item("movie:1", "流浪地球", 2019, cast = listOf("吴京"), directors = listOf("郭帆"), genres = listOf("科幻")),
            item("movie:2", "流浪地球2", 2023, cast = listOf("吴京", "刘德华"), directors = listOf("郭帆"), genres = listOf("科幻")),
            item("movie:3", "蜘蛛侠：英雄归来", 2017, aliases = listOf("蜘蛛人：返校日", "Spider-Man: Homecoming"), cast = listOf("Tom Holland"), genres = listOf("动作")),
            item("movie:4", "长安三万里", 2023, genres = listOf("动画")),
        ),
        unmatched = listOf(MediaFile("f1", "Some.Show.S01E01.mkv", "/x/Some.Show.S01E01.mkv", 1)),
        collections = listOf(MediaCollection("dir:1", "流浪地球系列", "movie:1", listOf("movie:1", "movie:2"))),
        // 刮削器生成的, 这里手写
        pinyin = mapOf(
            "流浪地球" to "liu lang di qiu",
            "流浪地球2" to "liu lang di qiu 2",
            "流浪地球系列" to "liu lang di qiu xi lie",
            "吴京" to "wu jing",
            "刘德华" to "liu de hua",
            "郭帆" to "guo fan",
            "科幻" to "ke huan",
            "长安三万里" to "chang|zhang an san wan li",
            "蜘蛛人：返校日" to "zhi zhu ren fan xiao ri",
        ),
    )
    private val search = LibrarySearch(lib)

    private fun ids(q: String) = search.search(q).map {
        when (it) {
            is LibrarySearch.Hit.Item -> it.item.id
            is LibrarySearch.Hit.Collection -> it.collection.id
            is LibrarySearch.Hit.File -> it.file.fid
        }
    }

    @Test fun initials() = assertTrue(ids("lldq").containsAll(listOf("movie:1", "movie:2", "dir:1")))
    @Test fun fullAndMixed() {
        assertTrue("movie:1" in ids("liulangdiqiu"))
        assertTrue("movie:1" in ids("liulangdq"))
        assertTrue("movie:1" in ids("LiuLang DiQiu"))
    }
    @Test fun middleOfTitle() = assertTrue("movie:1" in ids("diqiu"))
    @Test fun polyphone() = assertTrue("movie:4" in ids("changan"))
    @Test fun punctuationIgnored() = assertEquals("movie:3", ids("蜘蛛侠 英雄归来").first())
    @Test fun person() {
        assertEquals(setOf("movie:1", "movie:2"), ids("gf").toSet())
        assertEquals(listOf("movie:2"), ids("刘德华"))
        assertTrue("movie:3" in ids("holland"))
    }
    @Test fun genreAndYear() {
        assertTrue(ids("科幻").containsAll(listOf("movie:1", "movie:2")))
        assertEquals(setOf("movie:2", "movie:4"), ids("2023").toSet())
    }
    @Test fun file() = assertEquals(listOf("f1"), ids("some show"))
    @Test fun titleBeforePerson() = assertEquals("movie:1", ids("流浪地球").first())
    @Test fun alias() {
        assertEquals(listOf("movie:3"), ids("蜘蛛人"))
        assertEquals(listOf("movie:3"), ids("zzr"))
        assertEquals(listOf("movie:3"), ids("homecoming"))
    }
    @Test fun badPinyinIgnored() = assertTrue(LibrarySearch(lib.copy(pinyin = mapOf("流浪地球" to "liu lang"))).search("lldq").none { it is LibrarySearch.Hit.Item && it.item.id == "movie:1" })
    @Test fun names() = assertEquals(
        setOf("流浪地球", "流浪地球2", "流浪地球系列", "吴京", "刘德华", "郭帆", "科幻", "蜘蛛侠：英雄归来", "蜘蛛人：返校日", "动作", "长安三万里", "动画"),
        LibrarySearch.names(lib),
    )
    @Test fun noFalsePositive() = assertTrue(ids("zzzz").isEmpty())
}
