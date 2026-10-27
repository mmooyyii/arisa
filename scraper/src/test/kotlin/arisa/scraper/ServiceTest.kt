package arisa.scraper

import arisa.library.LibraryCodec
import arisa.quark.QuarkFile
import arisa.quark.RecentRecord
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 本地目录当网盘, 记录 list 次数, 可以塞「最近」动态. */
private class TestDrive(root: File) : Drive by LocalDrive(root) {
    val lists = mutableListOf<String>()
    val records = mutableListOf<RecentRecord>()
    private val local = LocalDrive(root)
    override fun list(fid: String): List<QuarkFile> = local.list(fid).also { lists += fid }
    override fun recent() = records.toList()
}

class ServiceTest {
    private fun touch(root: File, path: String) = File(root, path).apply { parentFile.mkdirs(); writeText("x") }

    private class Env(val root: File, val home: File, val agent: (List<PendingWork>) -> AgentRunner.Result) {
        val drive = TestDrive(root)
        val service = Service(
            home, File(home, "config.yaml"), StateStore(File(home, "state.json")),
            driveFactory = { drive },
            tmdbFactory = { FakeTmdb() },
            imagesFactory = { null },
            bangumiFactory = { null },
            agentFactory = { agent },
        )

        fun runAll() { while (service.runNext()) {} }
    }

    private fun env(agent: (List<PendingWork>) -> AgentRunner.Result): Env {
        val root = Files.createTempDirectory("arisa-drive").toFile()
        val home = Files.createTempDirectory("arisa-home").toFile()
        File(home, "config.yaml").writeText("libraries:\n  - path: /没看\n    type: auto\n")
        return Env(root, home, agent)
    }

    private fun ok(summary: String = "") = AgentRunner.Result(true, summary, 0.01, File("/dev/null"))

    @Test
    fun cachingDrive() {
        val root = Files.createTempDirectory("arisa-drive").toFile()
        touch(root, "a/1.mkv")
        val inner = TestDrive(root)
        val cacheFile = File(root.parentFile, root.name + "-cache.json")
        val cache = CachingDrive(inner, cacheFile)
        cache.list("a"); cache.list("a")
        assertEquals(listOf("a"), inner.lists)

        // 写操作让父目录失效
        cache.upload("a", "2.mkv", byteArrayOf(1))
        assertEquals(listOf("a/1.mkv", "a/2.mkv"), cache.list("a").map { it.fid })
        assertEquals(2, inner.lists.size)

        // 缓存落盘, 重启后不用再列
        cache.save()
        val reopened = CachingDrive(inner, cacheFile)
        reopened.list("a")
        assertEquals(2, inner.lists.size)
        reopened.invalidateAll()
        reopened.list("a")
        assertEquals(3, inner.lists.size)
    }

    @Test
    fun agentDecidesAsksAndLeavesUnhandled() {
        val batches = mutableListOf<List<String>>()
        lateinit var e: Env
        e = env { batch ->
            batches += batch.map { it.key }
            // AI: 认出芙莉莲, 问一部, 剩下一部没管
            e.service.store.update { it.with("没看/葬送的芙莉莲", MappingEntry("tv:209867", "没看/葬送的芙莉莲")) }
            e.service.review.add(ReviewEntry(batch.first { it.key.contains("乱码") }, "看不出是什么", guess = "movie:438631"))
            ok("识别 1 部, 问了 1 部")
        }
        touch(e.root, "没看/漫长的季节 (2023)/E01.mkv")            // 规则自动识别
        touch(e.root, "没看/葬送的芙莉莲/[桜都字幕组][葬送的芙莉莲][12][1080P].mkv")
        touch(e.root, "没看/乱码ΣΩ/xx.mkv")
        touch(e.root, "没看/另一个/yy.mkv")

        e.service.submit(Service.Job.Scan(false, "测试"))
        e.runAll()

        assertEquals(1, batches.size, "一批就交完, AI 没处理的不会马上再交")
        assertEquals(3, batches[0].size)
        val mapping = e.service.store.load().entries
        assertEquals("tv:221851", mapping.getValue("没看/漫长的季节 (2023)").target)
        assertEquals("tv:209867", mapping.getValue("没看/葬送的芙莉莲").target)
        val review = e.service.review.load().associateBy { it.work.key }
        assertEquals("ai", review.getValue("没看/乱码ΣΩ/xx.mkv").by)
        assertEquals("movie:438631", review.getValue("没看/乱码ΣΩ/xx.mkv").guess)
        assertEquals("unhandled", review.getValue("没看/另一个/yy.mkv").by)
        assertEquals(1, File(e.root, ".arisa").listFiles()!!.count { LibraryCodec.isIndexFile(it.name) })

        // 再扫一次: 待确认里的不再交给 AI
        e.service.submit(Service.Job.Scan(false, "测试"))
        e.runAll()
        assertEquals(1, batches.size)

        // 用户在网页上确认 → 移出待确认, 重新生成索引
        e.service.decide("没看/另一个/yy.mkv", "skip", null, "没看/另一个/yy.mkv", "没看/另一个/yy.mkv")
        e.runAll()
        assertEquals(setOf("没看/乱码ΣΩ/xx.mkv"), e.service.review.keys())
        assertEquals(1, e.service.status().pendingWorks)

        // 重新交给 AI
        e.service.retry("没看/乱码ΣΩ/xx.mkv")
        e.runAll()
        assertEquals(listOf("没看/乱码ΣΩ/xx.mkv"), batches.last())
    }

    @Test
    fun agentErrorGoesToReview() {
        val e = env { AgentRunner.Result(false, "Not logged in", null, File("/dev/null")) }
        touch(e.root, "没看/乱码ΣΩ/xx.mkv")
        e.service.submit(Service.Job.Scan(false, "测试"))
        e.runAll()
        val entry = e.service.review.load().single()
        assertEquals("error", entry.by)
        assertTrue(entry.reason.contains("Not logged in"))
    }

    @Test
    fun watchRecent() {
        val e = env { ok() }
        touch(e.root, "没看/漫长的季节 (2023)/E01.mkv")
        e.service.submit(Service.Job.Scan(false, "启动"))
        e.runAll()
        e.drive.records += RecentRecord("SAVE_SHARE", 1, emptyList())
        e.service.poll()   // 第一次只记位置
        assertEquals(false, e.service.runNext())

        // 新的一集上传到已扫描过的目录
        touch(e.root, "没看/漫长的季节 (2023)/E02.mkv")
        e.drive.records += RecentRecord("PLAY", 2, listOf(QuarkFile("x", "x.mkv", false, 0, 0, 0, parentFid = "没看/漫长的季节 (2023)")))
        e.service.poll()
        assertEquals(false, e.service.runNext(), "播放记录不算变化")

        e.drive.records += RecentRecord("UPLOAD_FILE", 3, listOf(QuarkFile("没看/漫长的季节 (2023)/E02.mkv", "E02.mkv", false, 0, 0, 0, parentFid = "没看/漫长的季节 (2023)")))
        e.drive.lists.clear()
        e.service.poll()
        e.runAll()
        // 增量: 只重新列了变化的目录 (以及写索引时的 .arisa 目录)
        assertEquals(listOf("没看/漫长的季节 (2023)"), e.drive.lists.filter { !it.startsWith(".arisa") && it.isNotEmpty() }.distinct())
        val index = File(e.root, ".arisa").listFiles()!!.filter { LibraryCodec.isIndexFile(it.name) }.maxBy { it.name }
        val item = LibraryCodec.decode(index.readBytes()).items.single()
        assertEquals(listOf(1, 2), item.seasons.single().episodes.map { it.episode })

        // 同一条动态不会重复触发
        e.service.poll()
        assertEquals(false, e.service.runNext())
    }
}
