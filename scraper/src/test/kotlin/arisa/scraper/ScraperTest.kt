package arisa.scraper

import arisa.library.LibraryCodec
import arisa.library.MediaType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}|]"), "")

internal class FakeTmdb : TmdbApi {
    val movies = mapOf(
        693134 to Tmdb.Details(id = 693134, title = "沙丘2", originalTitle = "Dune: Part Two", releaseDate = "2024-02-27", overview = "保罗…", posterPath = "/p1.jpg", runtime = 167),
        438631 to Tmdb.Details(id = 438631, title = "沙丘", originalTitle = "Dune", releaseDate = "2021-09-15", overview = "…", posterPath = "/p0.jpg"),
        // 同一个系列的两部
        535167 to Tmdb.Details(id = 535167, title = "流浪地球", releaseDate = "2019-02-05", overview = "…", belongsToCollection = Tmdb.CollectionRef(1, "流浪地球（系列）")),
        842675 to Tmdb.Details(id = 842675, title = "流浪地球2", releaseDate = "2023-01-22", overview = "…", belongsToCollection = Tmdb.CollectionRef(1, "流浪地球（系列）")),
        // 属于某个系列, 但片库里只有这一部
        9001 to Tmdb.Details(id = 9001, title = "独行月球", releaseDate = "2022-07-29", overview = "…", belongsToCollection = Tmdb.CollectionRef(2, "独行月球（系列）")),
        9002 to Tmdb.Details(id = 9002, title = "疯狂的石头", releaseDate = "2006-06-30", overview = "…"),
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
        .map { Tmdb.SearchResult(it.id, name = it.name, originalName = it.originalName, firstAirDate = it.firstAirDate) }

    override fun movie(id: Int, language: String) = movies.getValue(id)
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

    private class Env(val root: File, val config: Config) {
        val drive = LocalDrive(root)
        val store = MappingStore(File(root.parentFile, root.name + "-mapping.json"), drive)
        val scraper = Scraper(config, drive, FakeTmdb(), null) {}
        fun scan() = scraper.scan()
        /** LocalDrive 的 fid 就是相对路径. */
        fun decide(path: String, target: String, season: Int? = null) =
            store.save(store.load().with(path, MappingEntry(target, path, season)))
    }

    private fun env(vararg libs: LibraryConfig): Env {
        val root = Files.createTempDirectory("qf").toFile()
        return Env(root, Config(libraries = libs.toList()))
    }

    @Test
    fun decideAndBuild() {
        val e = env(
            LibraryConfig("/电影", LibraryType.MOVIE),
            LibraryConfig("/剧集", LibraryType.TV),
            LibraryConfig("/动漫", LibraryType.AUTO, output = OutputMode.INDEX),
        )
        val root = e.root
        touch(root, "电影/沙丘2 (2024)/Dune.Part.Two.2024.2160p.WEB-DL.mkv")
        touch(root, "电影/沙丘 (2021)/Dune.2021.mkv")
        touch(root, "电影/沙丘 (2021)/movie.nfo", "<movie><uniqueid type=\"imdb\">tt0000001</uniqueid></movie>")
        touch(root, "电影/乱七八糟/xyz123.mkv")
        touch(root, "剧集/漫长的季节 (2023)/Season 01/E01.mkv")
        touch(root, "剧集/漫长的季节 (2023)/Season 01/E02.mkv")
        touch(root, "动漫/葬送的芙莉莲/[桜都字幕组][葬送的芙莉莲][12][1080P].mkv")
        touch(root, "动漫/葬送的芙莉莲/[桜都字幕组][葬送的芙莉莲][13][1080P].mkv")

        // auto: 已有 NFO 的, 以及 TMDB 唯一且名字年份完全对上的
        assertEquals(3, e.scraper.autoDecide(e.scan(), e.store))
        val auto = e.store.load().entries
        assertEquals("movie:438631", auto.getValue("电影/沙丘 (2021)/Dune.2021.mkv").target)
        assertEquals("nfo", auto.getValue("电影/沙丘 (2021)/Dune.2021.mkv").by)
        assertEquals("movie:693134", auto.getValue("电影/沙丘2 (2024)/Dune.Part.Two.2024.2160p.WEB-DL.mkv").target)
        assertEquals("tv:221851", auto.getValue("剧集/漫长的季节 (2023)").target)

        // 剩下的交给 skill: 芙莉莲没有年份, 规则不敢自动定
        val pending = e.scraper.pending(e.scan(), e.store.load(), withCandidates = true)
        assertEquals(setOf("动漫/葬送的芙莉莲", "电影/乱七八糟/xyz123.mkv"), pending.map { it.key }.toSet())
        val frieren = pending.first { it.key == "动漫/葬送的芙莉莲" }
        assertEquals("tv", frieren.guess)
        assertEquals("tv:209867", frieren.candidates.first().target)
        e.decide("动漫/葬送的芙莉莲", "tv:209867")
        e.decide("电影/乱七八糟/xyz123.mkv", MappingEntry.SKIP)

        val (library, report) = e.scraper.build(e.scan(), e.store.load())
        val byId = library.items.associateBy { it.id }
        assertEquals(setOf("movie:693134", "movie:438631", "tv:221851", "tv:209867"), byId.keys)
        assertEquals(listOf(1, 2), byId.getValue("tv:221851").seasons.single().episodes.map { it.episode })
        assertEquals(listOf(12, 13), byId.getValue("tv:209867").seasons.single().episodes.map { it.episode })
        assertEquals(MediaType.TV, byId.getValue("tv:209867").type)
        // skip 的不入库, 也不算待识别
        assertEquals(0, library.unmatched.size)

        // index+nfo: 读来的 NFO 不回写, 只写索引的目录不写
        assertTrue(File(root, "电影/沙丘2 (2024)/Dune.Part.Two.2024.2160p.WEB-DL.nfo").readText().contains("<uniqueid type=\"tmdb\" default=\"true\">693134</uniqueid>"))
        assertTrue(!File(root, "电影/沙丘 (2021)/Dune.2021.nfo").exists())
        assertTrue(File(root, "剧集/漫长的季节 (2023)/tvshow.nfo").exists())
        assertTrue(!File(root, "动漫/葬送的芙莉莲/tvshow.nfo").exists())
        assertEquals(2, report.nfoWritten)

        val indexFiles = { File(root, ".arisa").listFiles()!!.filter { LibraryCodec.isIndexFile(it.name) } }
        assertEquals(library, LibraryCodec.decode(indexFiles().single().readBytes()))

        // 没有变化时不写新索引
        Thread.sleep(5)
        val (_, again) = e.scraper.build(e.scan(), e.store.load())
        assertEquals(null, again.indexFile)
        assertEquals(0, again.nfoWritten)
        assertEquals(1, indexFiles().size)
    }

    @Test
    fun incremental() {
        val e = env(LibraryConfig("/没看", LibraryType.AUTO))
        touch(e.root, "没看/漫长的季节 (2023)/E01.mkv")
        e.decide("没看/漫长的季节 (2023)", "tv:221851")
        e.scraper.build(e.scan(), e.store.load())

        // 已识别的剧集目录里新加一集: 不需要识别, 直接并入
        touch(e.root, "没看/漫长的季节 (2023)/E02.mkv")
        // 新的作品: 出现在待识别里
        touch(e.root, "没看/沙丘2 (2024)/Dune.Part.Two.2024.mkv")
        val pending = e.scraper.pending(e.scan(), e.store.load(), withCandidates = false)
        assertEquals(listOf("没看/沙丘2 (2024)/Dune.Part.Two.2024.mkv"), pending.map { it.key })

        Thread.sleep(5)
        val (library, report) = e.scraper.build(e.scan(), e.store.load())
        assertEquals(listOf(1, 2), library.items.single { it.id == "tv:221851" }.seasons.single().episodes.map { it.episode })
        assertEquals(1, report.pending)
        assertTrue(report.indexFile != null)
    }

    @Test
    fun seasonOverride() {
        val e = env(LibraryConfig("/没看", LibraryType.AUTO))
        // 目录名看不出第几季, 映射时指定
        touch(e.root, "没看/漫长的季节 番外/01.mkv")
        e.decide("没看/漫长的季节 番外", "tv:221851", season = 0)
        val (library, _) = e.scraper.build(e.scan(), e.store.load(), write = false)
        assertEquals(0, library.items.single().seasons.single().number)
    }

    @Test
    fun renamePlan() {
        val e = env(LibraryConfig("/没看", LibraryType.AUTO))
        val root = e.root
        // 单部剧, 季目录里带画质子目录
        touch(root, "没看/【国产剧】长相思.全2季.国语中字.4K/长相思S01.2023/4K/01 4K.mp4")
        touch(root, "没看/【国产剧】长相思.全2季.国语中字.4K/长相思S01.2023/1080P/02.mp4")
        // 合集: 两部同名剧, 改下一层; "剧场版" 里没识别的不动
        touch(root, "没看/Q 全职猎人 4K 合集/全职猎人 1999/日语版/01.mp4")
        touch(root, "没看/Q 全职猎人 4K 合集/全职猎人 2011/01.mp4")
        touch(root, "没看/Q 全职猎人 4K 合集/全职猎人 剧场版/xyz.mkv")
        // 已经是规范名字的不出现在计划里
        touch(root, "没看/漫长的季节 (2023)/E01.mkv")
        // 没识别
        touch(root, "没看/H 欢迎.L 1080P/1080p.mkv")
        e.decide("没看/【国产剧】长相思.全2季.国语中字.4K", "tv:200001")
        e.decide("没看/Q 全职猎人 4K 合集/全职猎人 1999", "tv:2002")
        e.decide("没看/Q 全职猎人 4K 合集/全职猎人 2011", "tv:46298")
        e.decide("没看/漫长的季节 (2023)", "tv:221851")

        val (_, report) = e.scraper.build(e.scan(), e.store.load(), write = false)
        val byFrom = report.renames.associateBy { it.from }
        assertEquals("长相思 (2023)", byFrom.getValue("【国产剧】长相思.全2季.国语中字.4K").to)
        assertEquals("全职猎人 (1999)", byFrom.getValue("全职猎人 1999").to)
        assertEquals("全职猎人 (2011)", byFrom.getValue("全职猎人 2011").to)
        assertEquals("还没识别", byFrom.getValue("全职猎人 剧场版").skipped)
        assertEquals("还没识别", byFrom.getValue("H 欢迎.L 1080P").skipped)
        assertTrue("Q 全职猎人 4K 合集" !in byFrom)
        assertTrue("漫长的季节 (2023)" !in byFrom)

        // 写成计划文件再读回来执行
        val plans = report.renames.map { RenamePlan.parse(it.toLine())!! }
        val (done, skipped) = e.scraper.applyRenames(plans)
        assertEquals(3, done, skipped.toString())
        assertTrue(File(root, "没看/长相思 (2023)/长相思S01.2023/4K/01 4K.mp4").exists())
        assertTrue(File(root, "没看/Q 全职猎人 4K 合集/全职猎人 (1999)/日语版/01.mp4").exists())
        assertTrue(File(root, "没看/H 欢迎.L 1080P").exists())
    }

    @Test
    fun collections() {
        val e = env(LibraryConfig("/没看", LibraryType.AUTO))
        val root = e.root
        // 剧 + 剧: 名字和封面用集数最多的那部
        touch(root, "没看/Q 全职猎人 4K 合集/全职猎人 1999/01.mp4")
        touch(root, "没看/Q 全职猎人 4K 合集/全职猎人 2011/01.mp4")
        touch(root, "没看/Q 全职猎人 4K 合集/全职猎人 2011/02.mp4")
        // 全是电影: 名字用目录名, 封面用最早的
        touch(root, "没看/沙丘系列/沙丘/Dune.2021.mkv")
        touch(root, "没看/沙丘系列/沙丘2/Dune.Part.Two.2024.mkv")
        // 顶层目录里只有一部的不算合集
        touch(root, "没看/漫长的季节 (2023)/E01.mkv")
        e.decide("没看/Q 全职猎人 4K 合集/全职猎人 1999", "tv:2002")
        e.decide("没看/Q 全职猎人 4K 合集/全职猎人 2011", "tv:46298")
        e.decide("没看/沙丘系列/沙丘", "movie:438631")
        e.decide("没看/沙丘系列/沙丘2", "movie:693134")
        e.decide("没看/漫长的季节 (2023)", "tv:221851")

        val (library, _) = e.scraper.build(e.scan(), e.store.load(), write = false)
        val byName = library.collections.associateBy { it.name }
        assertEquals(setOf("全职猎人", "沙丘系列"), byName.keys)
        assertEquals("tv:46298", byName.getValue("全职猎人").cover)
        assertEquals(listOf("tv:2002", "tv:46298"), byName.getValue("全职猎人").items)
        assertEquals("movie:438631", byName.getValue("沙丘系列").cover)
        assertEquals(listOf("movie:438631", "movie:693134"), byName.getValue("沙丘系列").items)
    }

    @Test
    fun collectionsFromTmdbSeries() {
        val e = env(LibraryConfig("/没看", LibraryType.AUTO))
        val root = e.root
        // 同一个系列的两部电影放在不同的顶层目录里: 按 TMDB 的系列归到一起
        touch(root, "没看/流浪地球 (2019)/a.mkv")
        touch(root, "没看/流浪地球2 (2023)/b.mkv")
        // 系列里只有一部在片库里: 不成合集, 还可以按目录归组
        touch(root, "没看/杂/独行月球/c.mkv")
        touch(root, "没看/杂/疯狂的石头/d.mkv")
        e.decide("没看/流浪地球 (2019)", "movie:535167")
        e.decide("没看/流浪地球2 (2023)", "movie:842675")
        e.decide("没看/杂/独行月球", "movie:9001")
        e.decide("没看/杂/疯狂的石头", "movie:9002")

        val (library, _) = e.scraper.build(e.scan(), e.store.load(), write = false)
        val byId = library.collections.associateBy { it.id }
        assertEquals(setOf("流浪地球（系列）", "杂"), library.collections.map { it.name }.toSet())
        val series = byId.getValue("tmdb:1")
        assertEquals("流浪地球（系列）", series.name)
        assertEquals("movie:535167", series.cover)
        assertEquals(listOf("movie:535167", "movie:842675"), series.items)
        assertEquals(listOf("movie:9002", "movie:9001"), library.collections.first { it.name == "杂" }.items)
        // 系列名也能用拼音搜
        assertTrue("流浪地球（系列）" in library.pinyin)
    }

    @Test
    fun mappingBackup() {
        val e = env(LibraryConfig("/没看", LibraryType.AUTO))
        File(e.root, "没看").mkdirs()
        e.decide("a", "movie:1")
        e.store.backup()
        e.decide("b", "movie:2")
        e.store.backup()
        val remote = File(e.root, ".arisa").listFiles()!!.filter { it.name.startsWith("mapping") }
        assertEquals(listOf(MappingStore.NAME), remote.map { it.name })

        // 本地丢了从网盘恢复
        File(e.root.parentFile, e.root.name + "-mapping.json").delete()
        assertEquals(setOf("a", "b"), e.store.load().entries.keys)
    }
}
