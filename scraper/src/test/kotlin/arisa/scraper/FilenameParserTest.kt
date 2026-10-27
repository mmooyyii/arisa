package arisa.scraper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /** 用户网盘里的真实路径. */
    @Test
    fun realWorld() {
        check("01 4K.mp4", listOf("长相思"), 2023, 1, 1, parents = listOf("【国产剧】长相思.全2季.国语中字.4K", "长相思S01.2023", "4K"))
        check("23.mp4", listOf("长相思"), 2024, 2, 23, parents = listOf("【国产剧】长相思.全2季.国语中字.4K", "长相思S02.2024", "1080P"))
        check("178 4K(1).mp4", listOf("凡人修仙传"), season = 1, episode = 178, parents = listOf("凡人修仙传"))
        check("01.mp4", listOf("全职猎人"), 1999, 1, 1, parents = listOf("Q 全职猎人 4K 148集新版+旧版+粤语版+日语版+剧场版+漫画  G", "全职猎人 1999", "日语版"))
        check("22.mp4", listOf("小市民系列"), season = 2, episode = 22, parents = listOf("X 小市民系列 1-2季", "S02"))
        check(
            "Teach.You.a.Lesson.S01E01.2026.2160p.NF.WEB-DL.H265.DV.HDR.DDP5.1.Atmos.3Audio.mkv",
            listOf("Teach You a Lesson"), 2026, 1, 1,
            parents = listOf("铁.拳.教.育（2026）NF DV&HDR", "S01", "4K HDR"),
        )
    }

    @Test
    fun animeReleases() {
        val reZero = listOf("Re：從零開始的異世界生活", "05. Re：從零開始的異世界生活 第二季", "[动漫国&KNA&轻之国度字幕组][Re從零開始的異世界生活][26-50+SP26-50(第二季全集)][1080P][简体][MP4]")
        check("[DMG&KNA&LKSUB][Re - Zero_kara_Hajimeru_Isekai_Seikatsu][38][1080P][GB].mp4", listOf("Re - Zero kara Hajimeru Isekai Seikatsu"), season = 2, episode = 38, parents = reZero)
        check(
            "[AI-Raws] 無職転生 #01 (BD HEVC 1920x1080 yuv444p10le FLAC)[06057AC0].mkv", listOf("無職転生"), season = 1, episode = 1,
            parents = listOf("W 無職轉生~到了異世界就拿出真本事~ 全3季 1080P", "01.無職轉生～第一季", "[AI-Raws][无职转生～到了异世界就拿出真本事～][BDRip][1080p][HEVC-yuv444p10le FLAC MKV]"),
        )
        check(
            "[DMG&RoxyLib] 無職転生 第16話「親子ケンカ」 [BDRip][AVC_AAC][1080P][CHS](B7800C0B).mp4", listOf("無職転生"), season = 1, episode = 16,
            parents = listOf("W 無職轉生~到了異世界就拿出真本事~ 全3季 1080P", "01.無職轉生～第一季", "x"),
        )
        check(
            "12.mkv", listOf("我的青春恋爱物语果然有问题"), 2013, 3, 12,
            parents = listOf("W 我的青春恋爱物语果然有问题（2013）1-3季 附OVA+OP+ED 内封、外挂字幕 4K", "第三季（2020）全12集 内封、外挂字幕 4K（Ma10p x265 flac）"),
        )
        check("01.mp4", listOf("小市民系列"), season = 1, episode = 1, parents = listOf("X 小市民系列 1-2季", "S01"))
        assertTrue(FilenameParser.isExtra("E40第11话 IN《STAR BEAT!》.mp4", listOf("BanG Dream! ", "BanG Dream! (2017)", "backdrops")))
    }

    @Test
    fun extras() {
        assertTrue(FilenameParser.isExtra("[VCB-Studio] Hibike! Euphonium [NCED01][Ma10p_1080p][x265_flac].mkv", emptyList()))
        assertTrue(FilenameParser.isExtra("Bang Dream! [Menu01].mkv", emptyList()))
        assertTrue(FilenameParser.isExtra("01.mkv", listOf("吹响吧！上低音号", "SPs")))
        assertTrue(!FilenameParser.isExtra("Breaking.Bad.S01E01.mkv", listOf("绝命毒师", "Season 01")))
        assertTrue(FilenameParser.isNoiseDir("4K HDR"))
        assertTrue(FilenameParser.isNoiseDir("日语版"))
        assertTrue(!FilenameParser.isNoiseDir("全职猎人 1999"))
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
