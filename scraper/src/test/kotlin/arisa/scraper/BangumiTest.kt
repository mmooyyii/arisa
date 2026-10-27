package arisa.scraper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BangumiTest {
    private val api = object : BangumiApi {
        val all = listOf(
            Bangumi.Subject(140001, "Re:ゼロから始める異世界生活", "Re：从零开始的异世界生活", "2016-04-03", "TV", 25),
            Bangumi.Subject(278826, "Re:ゼロから始める異世界生活 2nd season", "Re：从零开始的异世界生活 第二季", "2020-07-08", "TV", 13),
            Bangumi.Subject(225462, "Re:ゼロから始める異世界生活 Memory Snow", "Re：从零开始的异世界生活 雪之回忆", "2018-10-06", "剧场版", 1),
            Bangumi.Subject(1, "千と千尋の神隠し", "千与千寻", "2001-07-20", "剧场版", 1),
        )
        override fun searchAnime(keyword: String) = all
        override fun subject(id: Int) = all.first { it.id == id }
    }

    @Test
    fun match() {
        // 名字完全对上才算, 续作不会被当成第一季
        assertEquals(140001, Bangumi.match(api, "Re:ゼロから始める異世界生活", 2016, movie = false)?.id)
        assertEquals(1, Bangumi.match(api, "千と千尋の神隠し", 2001, movie = true)?.id)
        // 年份差太多不认
        assertNull(Bangumi.match(api, "千と千尋の神隠し", 2010, movie = true))
        assertNull(Bangumi.match(api, "ゼロから", 2016, movie = false))
    }
}
