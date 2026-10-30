package arisa.library

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseTest {
    private fun item(id: String, title: String, type: MediaType = MediaType.MOVIE, year: Int? = null, genres: List<String> = emptyList(), language: String? = null, countries: List<String> = emptyList(), rating: Double? = null, addedAt: Long = 0) =
        MediaItem(id = id, type = type, title = title, year = year, genres = genres, language = language, countries = countries, rating = rating, addedAt = addedAt)

    private val lib = Library(
        generatedAt = 0,
        items = listOf(
            item("movie:1", "流浪地球", year = 2019, genres = listOf("科幻"), language = "zh", rating = 7.8, addedAt = 1),
            item("movie:2", "流浪地球2", year = 2023, genres = listOf("科幻"), language = "zh", rating = 8.2, addedAt = 2),
            item("movie:3", "星际穿越", year = 2014, genres = listOf("科幻", "剧情"), language = "en", rating = 8.4, addedAt = 3),
            item("tv:4", "进击的巨人", type = MediaType.TV, year = 2013, genres = listOf("动画"), language = "ja", addedAt = 4),
            item("tv:5", "老友记", type = MediaType.TV, year = 1994, countries = listOf("US"), addedAt = 5),
            item("movie:6", "卡萨布兰卡", year = 1942, language = "en", addedAt = 6),
        ),
        collections = listOf(MediaCollection("dir:1", "流浪地球系列", "movie:1", listOf("movie:1", "movie:2"))),
        pinyin = mapOf("流浪地球系列" to "liu lang di|de qiu xi lie", "星际穿越" to "xing ji chuan yue", "进击的巨人" to "jin ji de|di ju ren", "老友记" to "lao you ji", "卡萨布兰卡" to "ka sa bu lan ka"),
    )
    private val browser = LibraryBrowser(lib)
    private fun keys(f: BrowseFilter) = browser.cards(f).map { it.key }

    @Test fun region() {
        assertEquals(Region.CHINESE, Region.of(lib.items[0]))
        assertEquals(Region.JAPAN, Region.of(lib.items[3]))
        assertEquals(Region.WESTERN, Region.of(lib.items[4]))
        assertEquals(null, Region.of(item("x", "x")))
    }

    @Test fun collectionMergedWhenTwoMatch() {
        assertEquals(listOf("movie:6", "tv:5", "tv:4", "movie:3", "dir:1"), keys(BrowseFilter()))
        // 只有一部符合时单独显示
        assertEquals(listOf("movie:2"), keys(BrowseFilter(decade = 2020)))
    }

    @Test fun filters() {
        assertEquals(listOf("movie:3", "dir:1"), keys(BrowseFilter(genre = "科幻")))
        assertEquals(listOf("movie:6", "movie:3"), keys(BrowseFilter(region = Region.WESTERN, type = MediaType.MOVIE)))
        assertEquals(listOf("movie:6"), keys(BrowseFilter(decade = BrowseFilter.EARLIER)))
    }

    @Test fun sort() {
        assertEquals(listOf("movie:3", "dir:1"), keys(BrowseFilter(genre = "科幻", sort = BrowseSort.RATING)))
        assertEquals(listOf("tv:4", "movie:6", "tv:5", "dir:1", "movie:3"), keys(BrowseFilter(sort = BrowseSort.TITLE)))
    }

    @Test fun options() {
        assertEquals(listOf(Region.CHINESE to 2, Region.WESTERN to 3, Region.JAPAN to 1), browser.regions(BrowseFilter()))
        assertEquals(listOf("科幻" to 2), browser.genres(BrowseFilter(region = Region.CHINESE)))
        assertEquals(listOf(2020 to 1, 2010 to 3, 1990 to 1, BrowseFilter.EARLIER to 1), browser.decades(BrowseFilter()))
    }
}
