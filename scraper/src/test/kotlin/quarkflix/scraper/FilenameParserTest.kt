package quarkflix.scraper

import kotlin.test.Test
import kotlin.test.assertEquals

class FilenameParserTest {
    private fun check(
        file: String,
        titles: List<String>,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        parents: List<String> = emptyList(),
    ) {
        val p = FilenameParser.parse(file, parents)
        assertEquals(ParsedName(titles, year, season, episode), p, file)
    }

    @Test
    fun movies() {
        check("Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.H.265.mkv", listOf("Dune Part Two"), 2024)
        check("沙丘2 (2024).mkv", listOf("沙丘2"), 2024)
        check("【高清下载】流浪地球2.The.Wandering.Earth.II.2023.国语中字.mp4", listOf("流浪地球2", "The Wandering Earth II"), 2023)
        check("The.Matrix.1999.1080p.BluRay.x264.mkv", listOf("The Matrix"), 1999)
        check("Blade.Runner.2049.2017.1080p.mkv", listOf("Blade Runner 2049"), 2017)
        check("让子弹飞.mkv", listOf("让子弹飞"))
    }

    @Test
    fun episodes() {
        check("Breaking.Bad.S02E05.720p.mkv", listOf("Breaking Bad"), season = 2, episode = 5)
        check("漫长的季节 S01E01.mkv", listOf("漫长的季节"), season = 1, episode = 1)
        check("权游第三季第9集.mkv", listOf("权游"), season = 3, episode = 9)
        check(
            "[桜都字幕组][葬送的芙莉莲][Sousou no Frieren][12][1080P][简繁内封].mkv",
            listOf("葬送的芙莉莲", "Sousou no Frieren"), season = 1, episode = 12,
        )
        check("[SubsPlease] Frieren - 12 (1080p) [ABCD1234].mkv", listOf("Frieren"), season = 1, episode = 12)
        check("Friends.3x07.mkv", listOf("Friends"), season = 3, episode = 7)
    }

    @Test
    fun titleFromDirectory() {
        check("E05.mkv", listOf("漫长的季节"), 2023, 1, 5, parents = listOf("剧集", "漫长的季节 (2023)", "Season 01"))
        check("01.mkv", listOf("繁花"), season = 2, episode = 1, parents = listOf("繁花", "第二季"))
    }

    @Test
    fun seasonDirs() {
        assertEquals(1, FilenameParser.seasonOfDir("Season 01"))
        assertEquals(2, FilenameParser.seasonOfDir("S2"))
        assertEquals(3, FilenameParser.seasonOfDir("第三季"))
        assertEquals(null, FilenameParser.seasonOfDir("Sopranos"))
    }

    @Test
    fun chineseNumbers() {
        assertEquals(9, FilenameParser.parseNumber("九"))
        assertEquals(10, FilenameParser.parseNumber("十"))
        assertEquals(12, FilenameParser.parseNumber("十二"))
        assertEquals(20, FilenameParser.parseNumber("二十"))
        assertEquals(23, FilenameParser.parseNumber("二十三"))
    }
}
