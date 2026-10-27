package quarkflix.scraper

import quarkflix.library.LibraryCodec
import quarkflix.library.MediaType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}|]"), "")

private class FakeTmdb : TmdbApi {
    val movies = mapOf(
        693134 to Tmdb.Details(id = 693134, title = "沙丘2", originalTitle = "Dune: Part Two", releaseDate = "2024-02-27", overview = "保罗…", posterPath = "/p1.jpg", runtime = 167),
        438631 to Tmdb.Details(id = 438631, title = "沙丘", originalTitle = "Dune", releaseDate = "2021-09-15", overview = "…", posterPath = "/p0.jpg"),
    )
    val shows = mapOf(
        209867 to Tmdb.Details(id = 209867, name = "葬送的芙莉莲", originalName = "葬送のフリーレン", firstAirDate = "2023-09-29", overview = "…"),
        46298 to Tmdb.Details(id = 46298, name = "全职猎人", originalName = "HUNTER×HUNTER", firstAirDate = "2011-10-02", overview = "…"),
        2002 to Tmdb.Details(id = 2002, name = "全职猎人", originalName = "HUNTER×HUNTER", firstAirDate = "1999-10-16", overview = "…"),
        200001 to Tmdb.Details(id = 200001, name = "长相思", firstAirDate = "2023-07-24", overview = "…"),
        221851 to Tmdb.Details(id = 221851, name = "漫长的季节", firstAirDate = "2023-04-22", overview = "…"),
    )

    override fun searchMovie(query: String, year: Int?, language: String) = movies.values
        .filter { norm(query) in norm(it.title!! + "|" + it.originalTitle) }
        .filter { year == null || it.year == year }
        .map { Tmdb.SearchResult(it.id, title = it.title, originalTitle = it.originalTitle, releaseDate = it.releaseDate) }

    override fun searchTv(query: String, year: Int?, language: String) = shows.values
        .filter { it.name == query || it.originalName == query }
        .filter { year == null || it.year == year }
        .filter { year == null || it.year == year }
        .map { Tmdb.SearchResult(it.id, name = it.name, originalName = it.originalName, firstAirDate = it.firstAirDate) }

    override fun movie(id: Int, language: String) = movies.getValue(id)
    // 同名剧按年份区分
    override fun tv(id: Int, language: String) = shows.getValue(id)
    override fun season(tvId: Int, number: Int, language: String) = Tmdb.SeasonDetails(
        number, "第 $number 季",
        episodes = (1..12).map { Tmdb.EpisodeDetails(it, number, name = "第${it}集标题") },
    )
    override fun findByImdb(imdbId: String) = if (imdbId == "tt0000001") Tmdb.MediaKind.MOVIE to 438631 else null
}

class ScraperTest {
    private fun touch(root: File, path: String, content: String = "x") {
        File(root, path).apply { parentFile.mkdirs(); writeText(content) }
    }

    @Test
    fun endToEnd() {
        val root = Files.createTempDirectory("qf").toFile()
        touch(root, "电影/沙丘2 (2024)/Dune.Part.Two.2024.2160p.WEB-DL.mkv")
        touch(root, "电影/沙丘 (2021)/Dune.2021.mkv")
        touch(root, "电影/沙丘 (2021)/movie.nfo", "<movie><uniqueid type=\"imdb\">tt0000001</uniqueid></movie>")
        touch(root, "电影/乱七八糟/xyz123.mkv")
        touch(root, "剧集/漫长的季节 (2023)/Season 01/E01.mkv")
        touch(root, "剧集/漫长的季节 (2023)/Season 01/E02.mkv")
        touch(root, "动漫/葬送的芙莉莲/[桜都字幕组][葬送的芙莉莲][12][1080P].mkv")
        touch(root, "动漫/葬送的芙莉莲/[桜都字幕组][葬送的芙莉莲][13][1080P].mkv")

        val config = Config(
            libraries = listOf(
                LibraryConfig("/电影", LibraryType.MOVIE),
                LibraryConfig("/剧集", LibraryType.TV),
                LibraryConfig("/动漫", LibraryType.AUTO, output = OutputMode.INDEX),
            ),
        )
        val logs = mutableListOf<String>()
        val (library, report) = Scraper(config, LocalDrive(root), FakeTmdb(), null, null) { logs += it }.run()

        val byId = library.items.associateBy { it.id }
        assertEquals(setOf("movie:693134", "movie:438631", "tv:221851", "tv:209867"), byId.keys, logs.joinToString("\n"))
        assertEquals("nfo", byId.getValue("movie:438631").match.source)
        assertEquals(listOf(1, 2), byId.getValue("tv:221851").seasons.single().episodes.map { it.episode })
        assertEquals(listOf(12, 13), byId.getValue("tv:209867").seasons.single().episodes.map { it.episode })
        assertEquals(MediaType.TV, byId.getValue("tv:209867").type)
        assertEquals(listOf("/电影/乱七八糟/xyz123.mkv"), library.unmatched.map { it.path })

        // index+nfo: 电影和剧集写了 NFO, 读来的 NFO 不回写, 动漫目录只写索引
        assertTrue(File(root, "电影/沙丘2 (2024)/Dune.Part.Two.2024.2160p.WEB-DL.nfo").readText().contains("<uniqueid type=\"tmdb\" default=\"true\">693134</uniqueid>"))
        assertTrue(!File(root, "电影/沙丘 (2021)/Dune.2021.nfo").exists())
        assertTrue(File(root, "剧集/漫长的季节 (2023)/tvshow.nfo").exists())
        assertTrue(!File(root, "动漫/葬送的芙莉莲/tvshow.nfo").exists())
        assertEquals(2, report.nfoWritten)

        // 索引写进 .quarkflix, 能读回来
        val index = File(root, ".quarkflix").listFiles()!!.single()
        assertEquals(library, LibraryCodec.decode(index.readBytes()))

        // 第二次扫描: 写过 NFO 的读自己的 NFO, 只写索引的目录沿用上次索引
        Thread.sleep(5)
        val (again, report2) = Scraper(config, LocalDrive(root), FakeTmdb(), null, null) {}.run()
        assertEquals(library.items.map { it.id }.toSet(), again.items.map { it.id }.toSet())
        assertEquals("nfo", again.items.first { it.id == "movie:693134" }.match.source)
        assertEquals("previous", again.items.first { it.id == "tv:209867" }.match.source)
        assertEquals(0, report2.nfoWritten)
        assertEquals(2, File(root, ".quarkflix").listFiles()!!.size)
    }

    @Test
    fun renamePlan() {
        val root = Files.createTempDirectory("qf").toFile()
        // 单部剧, 季目录里带画质子目录
        touch(root, "没看/【国产剧】长相思.全2季.国语中字.4K/长相思S01.2023/4K/01 4K.mp4")
        touch(root, "没看/【国产剧】长相思.全2季.国语中字.4K/长相思S01.2023/1080P/02.mp4")
        // 合集: 两部同名剧, 改下一层; "剧场版" 里没识别的不动
        touch(root, "没看/Q 全职猎人 4K 合集/全职猎人 1999/日语版/01.mp4")
        touch(root, "没看/Q 全职猎人 4K 合集/全职猎人 2011/01.mp4")
        touch(root, "没看/Q 全职猎人 4K 合集/全职猎人 剧场版/xyz.mkv")
        // 已经是规范名字的不出现在计划里
        touch(root, "没看/漫长的季节 (2023)/E01.mkv")
        // 部分没识别
        touch(root, "没看/H 欢迎.L 1080P/1080p.mkv")

        val config = Config(libraries = listOf(LibraryConfig("/没看", LibraryType.AUTO)))
        val drive = LocalDrive(root)
        val (_, report) = Scraper(config, drive, FakeTmdb(), null, null) {}.run(write = false)
        val byFrom = report.renames.associateBy { it.from }

        assertEquals("长相思 (2023)", byFrom.getValue("【国产剧】长相思.全2季.国语中字.4K").to)
        assertEquals("全职猎人 (1999)", byFrom.getValue("全职猎人 1999").to)
        assertEquals("全职猎人 (2011)", byFrom.getValue("全职猎人 2011").to)
        assertEquals("没识别出来", byFrom.getValue("全职猎人 剧场版").skipped)
        assertEquals("没识别出来", byFrom.getValue("H 欢迎.L 1080P").skipped)
        assertTrue("Q 全职猎人 4K 合集" !in byFrom)
        assertTrue("漫长的季节 (2023)" !in byFrom)

        // 写成计划文件再读回来执行
        val plans = report.renames.map { RenamePlan.parse(it.toLine())!! }
        val (done, skipped) = Scraper(config, drive, FakeTmdb(), null, null) {}.applyRenames(plans)
        assertEquals(3, done, skipped.toString())
        assertTrue(File(root, "没看/长相思 (2023)/长相思S01.2023/4K/01 4K.mp4").exists())
        assertTrue(File(root, "没看/Q 全职猎人 4K 合集/全职猎人 (1999)/日语版/01.mp4").exists())
        assertTrue(File(root, "没看/H 欢迎.L 1080P").exists())
    }
}
