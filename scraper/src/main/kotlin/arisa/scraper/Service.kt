package arisa.scraper

import arisa.quark.QuarkWebClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingDeque

/**
 * 常驻服务: 盯着夸克的「最近」发现新转存/上传的内容, 增量扫描, 规则能定的直接定,
 * 其余交给 AI, AI 拿不准的放进待确认等用户在网页上处理, 最后生成索引和改名计划.
 *
 * 重活 (扫描、AI、生成索引、改名) 都在一个工作线程里排队做, 不会互相踩.
 */
class Service(
    private val home: File,
    private val configFile: File,
    private val state: StateStore,
    /** 测试时替换掉夸克. */
    driveFactory: ((Config) -> Drive)? = null,
    private val tmdbFactory: (Config) -> TmdbApi? = { c ->
        if (c.tmdb.apiKey.isBlank()) null else Tmdb(c.tmdb.apiKey, httpClient(c.tmdb.proxy), File(home, "cache/tmdb"))
    },
    private val bangumiFactory: (Config) -> BangumiApi? = { Bangumi(httpClient(null), File(home, "cache/bangumi")) },
    private val imagesFactory: (Config) -> Images? = { c -> Images(httpClient(c.tmdb.proxy), File(home, "cache/images")) },
    private val agentFactory: (Config) -> ((List<PendingWork>) -> AgentRunner.Result)? = { c ->
        if (c.agent.enabled) AgentRunner(c.agent, home)::run else null
    },
) {
    private val driveFactory = driveFactory ?: { c ->
        val cookie = state.load().cookie ?: throw LoginExpiredException("没有登录")
        QuarkDrive(QuarkWebClient(cookie, onCookieChange = { ck -> state.update { it.copy(cookie = ck) } }), c.rateLimit)
    }
    private val quark = LazyDrive { this.driveFactory(config()) }
    val drive = CachingDrive(quark, File(home, "scan-cache.json"))
    val store = MappingStore(File(home, "mapping.json"), drive)
    val review = ReviewStore(File(home, "review.json"))
    private val planFile = File(home, "rename-plan.txt")
    private val watchFile = File(home, "watch.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false; explicitNulls = false }

    // ---------- 状态, 给网页看 ----------

    @Serializable
    data class Status(
        val busy: String? = null,
        val queued: List<String> = emptyList(),
        val loginError: String? = null,
        val configError: String? = null,
        val lastScan: String? = null,
        val lastBuild: String? = null,
        val items: Int? = null,
        val pendingVideos: Int? = null,
        val pendingWorks: Int? = null,
        val review: Int = 0,
        val renames: Int = 0,
        val agentEnabled: Boolean = false,
        val agentCostToday: Double = 0.0,
    )

    @Volatile private var busy: String? = null
    @Volatile var loginError: String? = null
        private set
    @Volatile private var configError: String? = null
    @Volatile private var lastScan: String? = null
    @Volatile private var lastBuild: String? = null
    @Volatile private var lastReport: BuildReport? = null
    @Volatile private var pendingWorks: Int? = null
    private var costDay = ""
    @Volatile private var costToday = 0.0
    private val activity = ArrayDeque<String>()

    fun status() = Status(
        busy = busy,
        queued = synchronized(queue) { queue.map { it.title } },
        loginError = loginError,
        configError = configError,
        lastScan = lastScan,
        lastBuild = lastBuild,
        items = lastReport?.items,
        pendingVideos = lastReport?.pending,
        pendingWorks = pendingWorks,
        review = review.load().size,
        renames = renamePlans().count { it.skipped == null },
        agentEnabled = runCatching { config().agent.enabled }.getOrDefault(false),
        agentCostToday = costToday,
    )

    fun activity(): List<String> = synchronized(activity) { activity.toList().asReversed() }

    fun log(msg: String) {
        val line = "${LocalDateTime.now().format(TIME)} $msg"
        println(line)
        synchronized(activity) {
            activity.addLast(line)
            while (activity.size > 300) activity.removeFirst()
        }
    }

    fun config() = Config.load(configFile)

    fun tmdb(config: Config) = tmdbFactory(config)

    // ---------- 任务队列 ----------

    sealed class Job(val title: String) {
        /** 扫描 → 自动识别 → AI → 生成索引. [full] 时丢掉缓存全量扫描. */
        class Scan(val full: Boolean, reason: String) : Job(if (full) "全量扫描 ($reason)" else "增量扫描 ($reason)")
        class Build(reason: String) : Job("生成索引 ($reason)")
        class Rename(val fids: Set<String>) : Job("改名 ${fids.size} 个")
    }

    private val queue = LinkedBlockingDeque<Job>()

    /** 同类任务已经在排队就不重复加 (全量扫描可以顶掉排队中的增量扫描). */
    fun submit(job: Job): Unit = synchronized(queue) {
        when (job) {
            is Job.Scan -> {
                val existing = queue.filterIsInstance<Job.Scan>()
                if (existing.any { it.full || !job.full }) return
                queue.removeAll(existing.toSet())
                queue.removeIf { it is Job.Build }
            }
            is Job.Build -> if (queue.any { it is Job.Build || it is Job.Scan }) return
            is Job.Rename -> {}
        }
        queue.add(job)
    }

    /** 处理队列里的一个任务, 返回 false 表示队列空. 测试直接调. */
    fun runNext(): Boolean {
        val job = queue.poll() ?: return false
        busy = job.title
        try {
            when (job) {
                is Job.Scan -> pipeline(job.full)
                is Job.Build -> buildAll()
                is Job.Rename -> rename(job.fids)
            }
            if (loginError != null) log("夸克登录已恢复")
            loginError = null
            File(home, "login-expired").delete()
        } catch (e: LoginExpiredException) {
            loginExpired(e)
        } catch (e: Exception) {
            log("${job.title} 失败: ${e.message ?: e.javaClass.simpleName}")
            e.printStackTrace()
        } finally {
            busy = null
        }
        return true
    }

    private fun loginExpired(e: LoginExpiredException) {
        if (loginError == null) log("夸克登录失效: ${e.message}")
        loginError = e.message
        File(home, "login-expired").writeText("${LocalDateTime.now()} ${e.message}\n")
    }

    // ---------- 流水线 ----------

    private fun scraper(config: Config): Scraper {
        val tmdb = tmdbFactory(config)
        configError = if (tmdb == null) "没有 TMDB API Key, 填到 ${configFile.path} 的 tmdb.api_key" else null
        return Scraper(config, drive, tmdb, imagesFactory(config), bangumiFactory(config)) { log(it) }
    }

    private fun pipeline(full: Boolean) {
        val config = config()
        if (full) {
            drive.invalidateAll()
            saveWatch(watchState().copy(lastFullScan = java.time.LocalDate.now().toString()))
        }
        val scraper = scraper(config)
        val scan = scraper.scan()
        drive.save()
        lastScan = LocalDateTime.now().format(TIME)
        if (configError != null) {
            log(configError!!)
            return
        }
        val auto = scraper.autoDecide(scan, store)
        if (auto > 0) log("规则自动识别 $auto 部")

        val agent = agentFactory(config)
        if (agent != null) runAgent(scraper, scan, config.agent, agent)
        val left = scraper.pending(scan, store.load(), withCandidates = false)
        review.retain(left.mapTo(mutableSetOf()) { it.key })
        pendingWorks = left.size
        build(scraper, scan)
    }

    /** 分批交给 AI, 直到没有新的待识别 (已在待确认里的不再交给 AI). */
    private fun runAgent(scraper: Scraper, scan: Scan, config: AgentConfig, agent: (List<PendingWork>) -> AgentRunner.Result) {
        val tried = mutableSetOf<String>()
        while (true) {
            val skip = review.keys() + tried
            val keys = batchKeys(scraper.pending(scan, store.load(), withCandidates = false).filter { it.key !in skip }, config.batchSize)
            if (keys.isEmpty()) return
            tried += keys
            val batch = scraper.pending(scan, store.load(), withCandidates = true, only = keys)
            // decide 命令从这里查路径
            File(home, "pending.json").writeText(json.encodeToString(ListSerializer(PendingWork.serializer()), batch))
            log("交给 AI 识别 ${batch.size} 部")
            val result = agent(batch)
            result.costUsd?.let(::addCost)
            val cost = result.costUsd?.let { " 花费 $%.3f".format(it) } ?: ""
            log("AI ${if (result.ok) "完成" else "出错"}$cost: ${result.summary.lines().firstOrNull().orEmpty().take(200)}")

            // AI 没处理的放进待确认, 免得下次又交给它
            val stillPending = scraper.pending(scan, store.load(), withCandidates = false).associateBy { it.key }
            val asked = review.keys()
            val unhandled = batch.filter { it.key in stillPending && it.key !in asked }
            for (w in unhandled) {
                val reason = if (result.ok) "AI 没有给出结论" else "AI 运行出错: ${result.summary.take(100)}"
                review.add(ReviewEntry(w, reason, by = if (result.ok) "unhandled" else "error"))
            }
            if (unhandled.isNotEmpty()) log("${unhandled.size} 部 AI 没处理, 放进待确认")
            if (!result.ok) return
        }
    }

    /**
     * 取一批作品, 同一个顶层目录 (同一部作品的不同季、版本) 的放在同一批, AI 才能前后一致地映射.
     * 一个顶层目录本身超过批量时整个放进一批.
     */
    private fun batchKeys(works: List<PendingWork>, size: Int): Set<String> {
        val groups = works.groupBy { it.dirs.firstOrNull()?.fid ?: it.key }.values
        val batch = mutableListOf<PendingWork>()
        for (g in groups) {
            if (batch.isNotEmpty() && batch.size + g.size > size) break
            batch += g
        }
        return batch.mapTo(mutableSetOf()) { it.key }
    }

    private fun addCost(usd: Double) {
        val today = java.time.LocalDate.now().toString()
        if (today != costDay) { costDay = today; costToday = 0.0 }
        costToday += usd
    }

    private fun buildAll() {
        val scraper = scraper(config())
        if (configError != null) return log(configError!!)
        val scan = scraper.scan()
        drive.save()
        val left = scraper.pending(scan, store.load(), withCandidates = false)
        review.retain(left.mapTo(mutableSetOf()) { it.key })
        pendingWorks = left.size
        build(scraper, scan)
    }

    private fun build(scraper: Scraper, scan: Scan) {
        val (library, report) = scraper.build(scan, store.load(), write = true)
        // 本地留一份, 方便查看
        File(home, "library.json.gz").writeBytes(arisa.library.LibraryCodec.encode(library))
        store.backup()
        lastReport = report
        lastBuild = LocalDateTime.now().format(TIME)
        planFile.writeText(buildString {
            appendLine("# 改名计划, 网页上勾选确认, 或运行 arisa-scraper rename --apply")
            appendLine("# fid\t父目录fid\t父目录\t原名\t新名\tTMDB\t跳过原因")
            report.renames.forEach { appendLine(it.toLine()) }
        })
        drive.save()
        log("索引: ${report.items} 部作品, ${report.pending} 个视频待识别" +
            (report.indexFile?.let { ", 已更新 $it" } ?: ", 没有变化"))
    }

    fun renamePlans(): List<RenamePlan> =
        if (planFile.exists()) planFile.readLines().mapNotNull(RenamePlan::parse) else emptyList()

    private fun rename(fids: Set<String>) {
        val scraper = scraper(config())
        val (done, skipped) = scraper.applyRenames(renamePlans().filter { it.fid in fids })
        log("改名 $done 个" + if (skipped.isEmpty()) "" else ", 跳过 ${skipped.size} 个")
        skipped.forEach { log("  跳过 $it") }
        if (configError == null) buildAll()
    }

    // ---------- 网页上的操作 ----------

    fun decide(fid: String, target: String, season: Int?, path: String, reviewKey: String?, episodeOffset: Int? = null) {
        require(MappingEntry.validTarget(target)) { "target 要写成 movie:ID / tv:ID / skip" }
        store.update { it.with(fid, MappingEntry(target, path, season, episodeOffset, by = "user")) }
        reviewKey?.let(review::remove)
        log("确认 $path → $target${season?.let { " 第 $it 季" } ?: ""}")
        submit(Job.Build("确认了 $path"))
    }

    /** 从待确认移出, 下次扫描重新交给 AI. */
    fun retry(key: String) {
        if (review.remove(key)) submit(Job.Scan(false, "重新识别"))
    }

    fun setCookie(cookie: String) {
        QuarkWebClient(cookie.trim()).member()
        state.update { it.copy(cookie = cookie.trim()) }
        quark.reset()
        loginError = null
        File(home, "login-expired").delete()
        log("已更新夸克登录")
        submit(Job.Scan(false, "重新登录"))
    }

    // ---------- 盯网盘 ----------

    @Serializable
    private data class WatchState(val lastSeen: Long = 0, val lastFullScan: String? = null)

    private fun watchState() = runCatching { json.decodeFromString(WatchState.serializer(), watchFile.readText()) }.getOrDefault(WatchState())
    private fun saveWatch(s: WatchState) = watchFile.writeText(json.encodeToString(WatchState.serializer(), s))

    /**
     * 看一次「最近」: 有新的转存/上传落在扫描过的目录里, 就让这些目录的缓存失效并排一次增量扫描.
     * 夸克的 Cookie 每次请求都会续期, 这里定时请求也起到保活作用.
     */
    fun poll() {
        val records = try {
            drive.recent()
        } catch (e: LoginExpiredException) {
            loginExpired(e)
            return
        }
        if (loginError != null) {
            loginError = null
            File(home, "login-expired").delete()
            log("夸克登录已恢复")
            submit(Job.Scan(false, "登录恢复"))
        }
        val ws = watchState()
        val latest = records.maxOfOrNull { it.time } ?: ws.lastSeen
        val fresh = records.filter { it.time > ws.lastSeen && it.source !in IGNORED_SOURCES }
        if (ws.lastSeen == 0L) {
            // 第一次运行只记下位置, 启动时本来就会扫描
            saveWatch(ws.copy(lastSeen = latest))
            return
        }
        var touched = 0
        for (r in fresh) {
            for (f in r.files) {
                // 自己写的 NFO、索引不算
                if (f.name.endsWith(".nfo", true) || f.name.startsWith("library-") || f.name.startsWith("mapping")) continue
                val parent = f.parentFid ?: continue
                if (drive.isListed(parent)) {
                    drive.invalidate(parent)
                    touched++
                }
                if (f.isDir) drive.invalidate(f.fid)
            }
        }
        if (touched > 0) {
            log("网盘有 ${fresh.size} 条新动态 (${fresh.map { it.source }.distinct().joinToString()}), 涉及 $touched 个目录")
        }
        if (touched > 0) submit(Job.Scan(false, "网盘有变化"))
        saveWatch(ws.copy(lastSeen = maxOf(latest, ws.lastSeen)))
    }

    /** 每天在配置的钟点全量扫一次: 移动、删除在「最近」里看不到. */
    fun dailyFullScan() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate().toString()
        val ws = watchState()
        if (now.hour >= config().serve.fullScanHour && ws.lastFullScan != today) {
            saveWatch(ws.copy(lastFullScan = today))
            submit(Job.Scan(true, "每日"))
        }
    }

    // ---------- 运行 ----------

    fun start() {
        val config = config()
        submit(Job.Scan(full = !File(home, "scan-cache.json").exists(), reason = "启动"))
        Thread({ while (true) { if (!runNext()) Thread.sleep(1000) } }, "arisa-worker").apply { isDaemon = true }.start()
        Thread({
            while (true) {
                runCatching { poll(); dailyFullScan() }.onFailure { log("看网盘动态失败: ${it.message}") }
                // 登录失效时没必要频繁请求
                Thread.sleep(if (loginError != null) 600_000L else config().serve.pollSeconds * 1000L)
            }
        }, "arisa-watch").apply { isDaemon = true }.start()
        WebServer(this, config.serve.port).start()
        log("Arisa 刮削服务已启动, http://localhost:${config.serve.port}")
    }

    companion object {
        /** 不改变网盘内容的动态. */
        val IGNORED_SOURCES = setOf("PLAY", "VIEW_FILE")
        private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    }
}

/** 用到时才创建 (启动时可能还没登录), [reset] 后下次重新创建, 换 Cookie 时用. */
private class LazyDrive(private val factory: () -> Drive) : Drive {
    @Volatile private var drive: Drive? = null

    fun reset() { drive = null }

    private fun d(): Drive = drive ?: synchronized(this) { drive ?: factory().also { drive = it } }
    override val rootFid get() = d().rootFid
    override fun list(fid: String) = d().list(fid)
    override fun download(fid: String) = d().download(fid)
    override fun upload(parentFid: String, name: String, bytes: ByteArray) = d().upload(parentFid, name, bytes)
    override fun delete(fids: List<String>) = d().delete(fids)
    override fun mkdir(parentFid: String, name: String) = d().mkdir(parentFid, name)
    override fun rename(fid: String, newName: String) = d().rename(fid, newName)
    override fun recent() = d().recent()
    override fun thumbnail(file: arisa.quark.QuarkFile) = d().thumbnail(file)
}
