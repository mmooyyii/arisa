package arisa.scraper

import arisa.library.Library
import arisa.library.LibraryCodec
import arisa.quark.QuarkTvClient
import arisa.quark.QuarkWebClient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import kotlin.system.exitProcess

private val home = File(System.getenv("ARISA_HOME") ?: (System.getProperty("user.home") + "/.arisa"))
private val json = Json { prettyPrint = true; encodeDefaults = false; explicitNulls = false }

private const val USAGE = """arisa-scraper <命令> [参数]

识别 ("这是哪部片") 由 skill 判断, 这里只提供确定性的工具. 输出 JSON 的命令供 skill 调用.

登录:
  login [cookie]                网页端 Cookie 登录, 不带参数则从 stdin 读
  tv-login                      TV 端扫码登录 (验证 TV 接口用)
  whoami                        查看登录状态

网盘:
  ls [路径]                     列目录
  inspect <网盘目录>             只读遍历, 用规则解析每个视频
  thumbnail <网盘路径> <输出文件> 下载夸克为视频生成的缩略图 (不下载视频)
  link <路径> / tv-link <路径>   取直链
  put <本地文件> <网盘目录> / rm <网盘路径>

识别 (JSON):
  pending [--candidates]        还没识别的作品; --candidates 顺带给出 TMDB 候选
  auto                          自动识别确定的 (已有 NFO / TMDB 唯一且名字年份完全对上)
  tmdb search <片名> [--tv|--movie] [--year 年份]
  tmdb show <movie|tv> <id>     详情, 剧集含每季集数
  decide <fid> <movie:ID|tv:ID|skip> [--season N] [--note 说明]
  undecide <fid>
  mapping                       查看映射表

生成:
  build [--dry-run]             按映射表生成索引和 NFO (增量, 内容没变不上传)
  rename                        生成改名计划 ~/.arisa/rename-plan.txt (不改网盘)
  rename --apply                按计划文件改名
  daemon                        常驻: 定时 build, 定期请求夸克保持登录

选项:
  --config <文件>   默认 ~/.arisa/config.yaml
"""

fun main(argv: Array<String>) {
    val args = argv.toMutableList()
    val configFile = takeOption(args, "--config")?.let(::File) ?: File(home, "config.yaml")
    val cmd = args.removeFirstOrNull() ?: run { println(USAGE); return }
    val state = StateStore(File(home, "state.json"))

    try {
        when (cmd) {
            "login" -> {
                val cookie = args.firstOrNull() ?: run {
                    println("粘贴 pan.quark.cn 的 Cookie 后回车:")
                    readlnOrNull()?.trim().orEmpty()
                }
                require(cookie.isNotBlank()) { "cookie 为空" }
                val client = QuarkWebClient(cookie)
                val member = client.member()
                state.update { it.copy(cookie = client.currentCookie) }
                File(home, "login-expired").delete()
                println("登录成功: ${member["data"]}")
            }
            "tv-login" -> tvLogin(state)
            "whoami" -> {
                File(home, "login-expired").takeIf { it.exists() }?.let { println("⚠️ 定时任务记录过登录失效: ${it.readText().trim()}") }
                val s = state.load()
                println("网页端: " + if (s.cookie == null) "未登录" else runCatching { web(state).member()["data"].toString() }.getOrElse { "失效: ${it.message}" })
                println("TV 端: " + if (s.tv?.refreshToken == null) "未登录" else runCatching { tv(state).userInfo()["data"].toString() }.getOrElse { "失效: ${it.message}" })
            }
            "ls" -> {
                val client = web(state)
                val dir = client.resolve(args.firstOrNull() ?: "/") ?: error("路径不存在")
                client.list(dir.fid).forEach { println(fmt(it.isDir, it.size, it.name, it.fid)) }
            }
            "inspect" -> {
                val drive = QuarkDrive(web(state), RateLimit())
                val root = drive.resolve(args.first()) ?: error("路径不存在")
                fun walk(fid: String, path: String, dirs: List<String>) {
                    for (f in drive.list(fid)) {
                        if (f.isDir) walk(f.fid, "$path/${f.name}", dirs + f.name)
                        else if (FilenameParser.isVideo(f.name) && !FilenameParser.isExtra(f.name, dirs)) {
                            val p = FilenameParser.parse(f.name, dirs)
                            val se = if (p.episode != null) " S${p.season}E${p.episode}" else ""
                            println("${p.titles.joinToString(" | ").ifEmpty { "(无片名)" }}${p.year?.let { " ($it)" } ?: ""}$se\t$path/${f.name}")
                        }
                    }
                }
                walk(root.fid, args.first().trimEnd('/'), emptyList())
            }
            "thumbnail" -> {
                val client = web(state)
                val f = client.resolve(args[0]) ?: error("路径不存在")
                val url = f.thumbnail ?: error("夸克没有为这个文件生成缩略图")
                val out = File(args[1])
                val bytes = httpClient(null).newCall(okhttp3.Request.Builder().url(url).header("Referer", "https://pan.quark.cn/").build())
                    .execute().use { r -> check(r.isSuccessful) { "下载缩略图失败 ${r.code}" }; r.body!!.bytes() }
                out.writeBytes(bytes)
                println(out.absolutePath)
            }
            "link" -> {
                val client = web(state)
                val f = client.resolve(args.first()) ?: error("路径不存在")
                val link = client.downloadLink(f.fid)
                println(link.url)
                link.headers.forEach { (k, v) -> println("  $k: ${if (k == "Cookie") v.take(20) + "…" else v}") }
            }
            "tv-link" -> {
                val f = web(state).resolve(args.first()) ?: error("路径不存在")
                val client = tv(state)
                val link = client.downloadLink(f.fid)
                println("原文件: ${link.url}")
                runCatching { client.streamingLinks(f.fid) }
                    .onSuccess { list -> list.forEach { println("转码 ${it.resolution} ${it.width}x${it.height}: ${it.url}") } }
                    .onFailure { println("转码流: ${it.message}") }
            }
            "put" -> {
                val local = File(args[0])
                val client = web(state)
                val dir = client.resolve(args[1]) ?: error("网盘目录不存在")
                println("fid=" + client.upload(dir.fid, local.name, local.readBytes()))
            }
            "rm" -> {
                val client = web(state)
                val f = client.resolve(args.first()) ?: error("路径不存在")
                client.delete(listOf(f.fid))
                println("已删除")
            }
            "parse" -> args.forEach { println("$it\n  → ${FilenameParser.parse(it.substringAfterLast('/'), it.split('/').dropLast(1))}") }

            "pending" -> {
                val withCandidates = args.remove("--candidates")
                val ctx = context(configFile, state, needTmdb = withCandidates, quiet = true)
                val pending = ctx.scraper.pending(ctx.scraper.scan(), ctx.store.load(), withCandidates)
                val text = json.encodeToString(ListSerializer(PendingWork.serializer()), pending)
                // decide 时从这里查路径
                File(home, "pending.json").writeText(text)
                println(text)
            }
            "auto" -> {
                val ctx = context(configFile, state, needTmdb = true)
                val added = ctx.scraper.autoDecide(ctx.scraper.scan(), ctx.store)
                println("自动识别 $added 部")
            }
            "tmdb" -> tmdbCommand(args, Config.load(configFile))
            "decide" -> {
                val fid = args.removeFirst()
                val target = args.removeFirst()
                require(MappingEntry.validTarget(target)) { "target 要写成 movie:ID / tv:ID / skip" }
                val season = takeOption(args, "--season")?.toInt()
                val note = takeOption(args, "--note")
                val ctx = context(configFile, state, needTmdb = false, quiet = true)
                val mapping = ctx.store.load()
                val path = pathFromPending(fid) ?: fid
                ctx.store.save(mapping.with(fid, MappingEntry(target, path, season, note, by = "skill")))
                println("$path → $target${season?.let { " 第 $it 季" } ?: ""}")
            }
            "undecide" -> {
                val ctx = context(configFile, state, needTmdb = false, quiet = true)
                val mapping = ctx.store.load()
                require(args.first() in mapping.entries) { "映射表里没有 ${args.first()}" }
                ctx.store.save(mapping.without(args.first()))
                println("已删除")
            }
            "mapping" -> {
                val ctx = context(configFile, state, needTmdb = false, quiet = true)
                println(json.encodeToString(Mapping.serializer(), ctx.store.load()))
            }

            "build" -> {
                val dry = args.remove("--dry-run")
                val ctx = context(configFile, state, needTmdb = true)
                build(ctx, write = !dry)
            }
            "rename" -> {
                val planFile = File(home, "rename-plan.txt")
                val ctx = context(configFile, state, needTmdb = true)
                if (args.remove("--apply")) {
                    require(planFile.exists()) { "没有计划文件, 先运行 rename" }
                    val plans = planFile.readLines().mapNotNull(RenamePlan::parse)
                    val (done, skipped) = ctx.scraper.applyRenames(plans)
                    println("改名 $done 个")
                    skipped.forEach { println("跳过: $it") }
                    planFile.delete()
                } else {
                    val (_, report) = ctx.scraper.build(ctx.scraper.scan(), ctx.store.load(), write = false)
                    planFile.writeText(buildString {
                        appendLine("# 改名计划, 确认后运行 arisa-scraper rename --apply")
                        appendLine("# 不想改的行删掉即可; 最后一列有内容的是跳过的及原因")
                        appendLine("# fid\t父目录fid\t父目录\t原名\t新名\tTMDB\t跳过原因")
                        report.renames.sortedWith(compareBy({ it.skipped != null }, { it.parentPath }, { it.from }))
                            .forEach { appendLine(it.toLine()) }
                    })
                    val (todo, skip) = report.renames.partition { it.skipped == null }
                    todo.forEach { println("${it.parentPath}/${it.from}\n    → ${it.to}") }
                    if (skip.isNotEmpty()) println("\n跳过 ${skip.size} 个:")
                    skip.forEach { println("  ${it.parentPath}/${it.from}: ${it.skipped}") }
                    println("\n计划已写到 $planFile, 确认后运行 rename --apply")
                }
            }
            "daemon" -> daemon(configFile, state)

            // 给片库 JSON 补主色调和 BlurHash (生成演示数据用)
            "enrich" -> {
                val file = File(args.first())
                val lib = LibraryCodec.json.decodeFromString(Library.serializer(), file.readText())
                val images = Images(httpClient(null), File(home, "cache/images"))
                val items = lib.items.map { item ->
                    val poster = images.analyze(item.poster)
                    val backdrop = images.analyze(item.backdrop, "w300")
                    item.copy(art = arisa.library.Artwork(backdrop.color ?: poster.color, poster.blurHash, backdrop.blurHash))
                }
                val pretty = Json(LibraryCodec.json) { prettyPrint = true }
                file.writeText(pretty.encodeToString(Library.serializer(), lib.copy(items = items)))
            }
            else -> { println(USAGE); exitProcess(1) }
        }
    } catch (e: Exception) {
        System.err.println("错误: ${e.message}")
        exitProcess(if (e is LoginExpiredException) 3 else 1)
    }
}

private class Context(val scraper: Scraper, val store: MappingStore, val drive: QuarkDrive)

/** 从上一次 pending 的输出里查 fid 对应的路径 (作品 key 或某一级目录). */
private fun pathFromPending(fid: String): String? {
    val file = File(home, "pending.json").takeIf { it.exists() } ?: return null
    val works = runCatching { json.decodeFromString(ListSerializer(PendingWork.serializer()), file.readText()) }.getOrNull() ?: return null
    works.firstOrNull { it.key == fid }?.let { return it.path }
    for (w in works) {
        val i = w.dirs.indexOfFirst { it.fid == fid }
        if (i >= 0) return w.path.substringBefore("/" + w.dirs[0].name) + "/" + w.dirs.take(i + 1).joinToString("/") { it.name }
    }
    return null
}

private fun context(configFile: File, state: StateStore, needTmdb: Boolean, quiet: Boolean = false): Context {
    val config = Config.load(configFile)
    val drive = QuarkDrive(web(state), config.rateLimit)
    val tmdbHttp = httpClient(config.tmdb.proxy)
    val tmdb = if (config.tmdb.apiKey.isNotBlank()) Tmdb(config.tmdb.apiKey, tmdbHttp, File(home, "cache/tmdb")) else null
    require(!needTmdb || tmdb != null) {
        "没有 TMDB API Key. 在 https://www.themoviedb.org/settings/api 申请后, " +
            "填到 ${File(home, "config.yaml")} 的 tmdb.api_key, 或设置环境变量 ARISA_TMDB_API_KEY"
    }
    val images = Images(tmdbHttp, File(home, "cache/images"))
    // JSON 输出的命令把日志打到 stderr, 不污染 stdout
    val log: (String) -> Unit = if (quiet) { msg -> System.err.println(msg) } else ::println
    return Context(Scraper(config, drive, tmdb, images, log), MappingStore(File(home, "mapping.json"), drive), drive)
}

private fun build(ctx: Context, write: Boolean) {
    val start = System.currentTimeMillis()
    val mapping = ctx.store.load()
    val (_, report) = ctx.scraper.build(ctx.scraper.scan(), mapping, write)
    if (write) ctx.store.backup()
    val renames = report.renames.filter { it.skipped == null }
    File(home, "report.txt").writeText(buildString {
        appendLine("时间: ${java.time.LocalDateTime.now()}  耗时 ${(System.currentTimeMillis() - start) / 1000}s")
        appendLine("${report.items} 部作品, ${report.pending} 个视频待识别, 写 NFO ${report.nfoWritten} 个, 索引 ${report.indexFile ?: "(没变或未写)"}")
        appendLine("建议改名 ${renames.size} 个 (rename 命令生成计划)")
        renames.forEach { appendLine("  ${it.parentPath}/${it.from} → ${it.to}") }
    })
    println("${report.items} 部作品, ${report.pending} 个视频待识别, 写 NFO ${report.nfoWritten} 个, 建议改名 ${renames.size} 个")
}

/**
 * 常驻: 每 interval_minutes 生成一次索引; 每 keepalive_hours 至少请求一次夸克, 让 Cookie 续期.
 * 登录失效时不退出 (launchd 会不停重启), 写 ~/.arisa/login-expired 标记, 每小时重试, 重新 login 后自动恢复.
 */
private fun daemon(configFile: File, state: StateStore) {
    val expiredMark = File(home, "login-expired")
    var lastKeepalive = 0L
    var lastBuild = 0L
    while (true) {
        val config = Config.load(configFile)
        val now = System.currentTimeMillis()
        val buildDue = now - lastBuild >= config.scan.intervalMinutes * 60_000L
        val keepaliveDue = now - lastKeepalive >= config.scan.keepaliveHours * 3_600_000L
        val retryDue = expiredMark.exists() && now - lastBuild >= 3_600_000L
        if (buildDue && !expiredMark.exists() || retryDue || keepaliveDue) {
            try {
                if (buildDue || retryDue) {
                    build(context(configFile, state, needTmdb = true), write = true)
                    lastBuild = now
                } else {
                    web(state).member()
                }
                lastKeepalive = now
                if (expiredMark.delete()) println("${java.time.LocalDateTime.now()} 登录已恢复")
            } catch (e: LoginExpiredException) {
                if (!expiredMark.exists()) println("${java.time.LocalDateTime.now()} ${e.message}")
                expiredMark.writeText("${java.time.LocalDateTime.now()} ${e.message}\n")
                lastBuild = now
            } catch (e: Exception) {
                println("${java.time.LocalDateTime.now()} 失败: ${e.message}")
                lastBuild = now
            }
        }
        Thread.sleep(60_000)
    }
}

private fun tmdbCommand(args: MutableList<String>, config: Config) {
    require(config.tmdb.apiKey.isNotBlank()) { "没有 TMDB API Key" }
    val tmdb = Tmdb(config.tmdb.apiKey, httpClient(config.tmdb.proxy), File(home, "cache/tmdb"))
    val lang = config.defaults.language
    when (args.removeFirstOrNull()) {
        "search" -> {
            val tv = args.remove("--tv")
            val movie = args.remove("--movie")
            val year = takeOption(args, "--year")?.toInt()
            val query = args.joinToString(" ")
            val out = buildJsonArray {
                fun addAll(kind: String, results: List<Tmdb.SearchResult>) = results.take(8).forEach { r ->
                    add(buildJsonObject {
                        put("target", "$kind:${r.id}")
                        put("title", r.displayTitle)
                        r.displayOriginal?.takeIf { it != r.displayTitle }?.let { put("original", it) }
                        r.year?.let { put("year", it) }
                        put("popularity", r.popularity)
                        r.overview?.takeIf { it.isNotBlank() }?.let { put("overview", it.take(100)) }
                    })
                }
                if (!tv) addAll("movie", tmdb.searchMovie(query, year, lang))
                if (!movie) addAll("tv", tmdb.searchTv(query, year, lang))
            }
            println(json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), out))
        }
        "show" -> {
            val kind = args[0]
            val id = args[1].toInt()
            val d = if (kind == "movie") tmdb.movie(id, lang) else tmdb.tv(id, lang)
            val out = buildJsonObject {
                put("target", "$kind:$id")
                put("title", d.displayTitle)
                d.displayOriginal?.let { put("original", it) }
                d.year?.let { put("year", it) }
                d.runtime?.let { put("runtime", it) }
                d.overview?.let { put("overview", it.take(200)) }
                put("seasons", buildJsonArray {
                    d.seasons.forEach { s ->
                        add(buildJsonObject { put("season", s.seasonNumber); put("name", s.name ?: ""); put("episodes", s.episodeCount) })
                    }
                })
            }
            println(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), out))
        }
        else -> error("用法: tmdb search <片名> [--tv|--movie] [--year Y] / tmdb show <movie|tv> <id>")
    }
}

private fun tvLogin(state: StateStore) {
    val session = state.load().tv ?: QuarkTvClient.TvSession.newDevice()
    val client = QuarkTvClient(session, onSessionChange = { s -> state.update { it.copy(tv = s) } })
    val qr = client.startQrLogin()
    val png = File(home, "tv-login-qr.jpg")
    png.parentFile.mkdirs()
    png.writeBytes(Base64.getDecoder().decode(qr.qrJpegBase64.substringAfter("base64,")))
    println("用夸克 App 扫码: $png")
    runCatching { ProcessBuilder("open", png.path).start() }
    repeat(120) {
        Thread.sleep(2000)
        if (client.pollLogin(qr.queryToken)) {
            println("登录成功")
            return
        }
    }
    error("扫码超时")
}

class LoginExpiredException(message: String) : Exception(message)

private fun web(state: StateStore): QuarkWebClient {
    val cookie = state.load().cookie ?: throw LoginExpiredException("没有登录, 先运行 login")
    return QuarkWebClient(cookie, onCookieChange = { c -> state.update { it.copy(cookie = c) } })
}

private fun tv(state: StateStore): QuarkTvClient {
    val session = state.load().tv ?: error("没有 TV 登录, 先运行 tv-login")
    return QuarkTvClient(session, onSessionChange = { s -> state.update { it.copy(tv = s) } })
}

private fun fmt(dir: Boolean, size: Long, name: String, fid: String) =
    "%s %10s  %s  (%s)".format(if (dir) "d" else "-", if (dir) "" else humanSize(size), name, fid)

private fun humanSize(n: Long): String = when {
    n >= 1L shl 30 -> "%.1fG".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1fM".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.1fK".format(n / (1L shl 10).toDouble())
    else -> "${n}B"
}

private fun takeOption(args: MutableList<String>, name: String): String? {
    val i = args.indexOf(name)
    if (i < 0 || i + 1 >= args.size) return null
    val v = args[i + 1]
    args.removeAt(i + 1)
    args.removeAt(i)
    return v
}
