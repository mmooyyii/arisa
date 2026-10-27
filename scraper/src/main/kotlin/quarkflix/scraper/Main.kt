package quarkflix.scraper

import quarkflix.library.LibraryCodec
import quarkflix.quark.QuarkTvClient
import quarkflix.quark.QuarkWebClient
import java.io.File
import java.util.Base64
import kotlin.system.exitProcess

private val home = File(System.getProperty("user.home"), ".quarkflix")

private const val USAGE = """quarkflix-scraper <命令> [参数]

登录:
  login [cookie]            网页端 Cookie 登录 (刮削/上传用), 不带参数则从 stdin 读
  tv-login                  TV 端扫码登录 (验证 TV 接口用)
  whoami                    查看登录状态

网盘:
  ls [路径]                 列目录 (网页接口)
  tv-ls [路径]              列目录 (TV 接口)
  link <路径>               取直链 (网页接口)
  tv-link <路径>            取直链和转码流 (TV 接口)
  put <本地文件> <网盘目录>  上传
  rm <网盘路径>             删除

刮削:
  parse <文件名>...          只跑规则解析, 看结果
  scan [--dry-run] [--out 文件]   扫描一次
  daemon                    常驻, 按 scan.interval_minutes 定时扫描

选项:
  --config <文件>   默认 ~/.quarkflix/config.yaml
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
                println("登录成功: ${member["data"]}")
            }
            "tv-login" -> tvLogin(state)
            "whoami" -> {
                val s = state.load()
                println("网页端: " + if (s.cookie == null) "未登录" else runCatching { web(state).member()["data"].toString() }.getOrElse { "失效: ${it.message}" })
                println("TV 端: " + if (s.tv?.refreshToken == null) "未登录" else runCatching { tv(state).userInfo()["data"].toString() }.getOrElse { "失效: ${it.message}" })
            }
            "ls" -> {
                val client = web(state)
                val dir = client.resolve(args.firstOrNull() ?: "/") ?: error("路径不存在")
                client.list(dir.fid).forEach { println(fmt(it.isDir, it.size, it.name, it.fid)) }
            }
            "tv-ls" -> {
                val client = tv(state)
                var fid = QuarkTvClient.ROOT
                for (part in (args.firstOrNull() ?: "/").split('/').filter { it.isNotEmpty() }) {
                    fid = client.list(fid).firstOrNull { it.name == part }?.fid ?: error("路径不存在: $part")
                }
                client.list(fid).forEach { println(fmt(it.isDir, it.size, it.name, it.fid)) }
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
                link.headers.forEach { (k, v) -> println("  $k: $v") }
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
            "scan" -> {
                val dry = args.remove("--dry-run")
                val out = takeOption(args, "--out")?.let(::File)
                scanOnce(Config.load(configFile), state, write = !dry, out = out)
            }
            "daemon" -> {
                while (true) {
                    val config = Config.load(configFile)
                    runCatching { scanOnce(config, state, write = true, out = null) }
                        .onFailure { println("扫描失败: ${it.message}") }
                    Thread.sleep(config.scan.intervalMinutes * 60_000L)
                }
            }
            // 给片库 JSON 补主色调和 BlurHash (生成演示数据用)
            "enrich" -> {
                val file = File(args.first())
                val lib = LibraryCodec.json.decodeFromString(quarkflix.library.Library.serializer(), file.readText())
                val images = Images(httpClient(null), File(home, "cache/images"))
                val items = lib.items.map { item ->
                    val poster = images.analyze(item.poster)
                    val backdrop = images.analyze(item.backdrop, "w300")
                    println("${item.title}: ${backdrop.color ?: poster.color}")
                    item.copy(art = quarkflix.library.Artwork(backdrop.color ?: poster.color, poster.blurHash, backdrop.blurHash))
                }
                val pretty = kotlinx.serialization.json.Json(LibraryCodec.json) { prettyPrint = true }
                file.writeText(pretty.encodeToString(quarkflix.library.Library.serializer(), lib.copy(items = items)))
            }
            else -> { println(USAGE); exitProcess(1) }
        }
    } catch (e: Exception) {
        System.err.println("错误: ${e.message}")
        exitProcess(1)
    }
}

private fun scanOnce(config: Config, state: StateStore, write: Boolean, out: File?) {
    require(config.tmdb.apiKey.isNotBlank()) { "config.yaml 里没有 tmdb.api_key" }
    val drive = QuarkDrive(web(state), config.rateLimit)
    val tmdbHttp = httpClient(config.tmdb.proxy)
    val tmdb = Tmdb(config.tmdb.apiKey, tmdbHttp, File(home, "cache/tmdb"))
    val ai = Ai(config.ai).takeIf { it.enabled }
    val images = Images(tmdbHttp, File(home, "cache/images"))
    val start = System.currentTimeMillis()
    val (library, report) = Scraper(config, drive, tmdb, ai, images).run(write)

    out?.let { it.writeBytes(LibraryCodec.encode(library)); println("索引另存到 $it") }
    // 给人看的报告: 没识别的、拿不准的
    val reportFile = File(home, "report.txt")
    reportFile.writeText(buildString {
        appendLine("扫描时间: ${java.time.LocalDateTime.now()}  耗时 ${(System.currentTimeMillis() - start) / 1000}s")
        appendLine("识别 ${report.matched} 部, 写 NFO ${report.nfoWritten} 个, 索引 ${report.indexFile ?: "(未写)"}")
        appendLine("\n== 未识别 (${report.unmatched.size}) ==")
        report.unmatched.forEach { appendLine(it) }
        appendLine("\n== 置信度低, 建议人工确认 (${report.lowConfidence.size}) ==")
        report.lowConfidence.forEach { appendLine(it) }
    })
    println("报告: $reportFile")
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

private fun web(state: StateStore): QuarkWebClient {
    val cookie = state.load().cookie ?: error("没有登录, 先运行 login")
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
