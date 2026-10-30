package arisa.scraper

import arisa.quark.QuarkWebLogin
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/** mac mini 上的管理网页: 状态、待确认、片库、改名计划、动态、设置、更新、夸克登录. 只在局域网用, 没有鉴权. */
class WebServer(private val service: Service, private val port: Int) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    /** 扫码登录进行中的会话, token → 登录器 (它攒着登录过程中拿到的 Cookie). */
    private val logins = java.util.concurrent.ConcurrentHashMap<String, QuarkWebLogin>()

    fun start() {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            val query = parseQuery(ex.requestURI.rawQuery)
            when ("${ex.requestMethod} $path") {
                "GET /", "GET /index.html" -> send(ex, 200, "text/html; charset=utf-8", resource("web/index.html"))
                "GET /api/status" -> sendJson(ex, json.encodeToJsonElement(Service.Status.serializer(), service.status()))
                "GET /api/activity" -> sendJson(ex, json.encodeToJsonElement(ListSerializer(String.serializer()), service.activity()))
                "GET /api/review" -> sendJson(ex, json.encodeToJsonElement(ListSerializer(ReviewEntry.serializer()), service.review.load().sortedByDescending { it.at }))
                "GET /api/renames" -> sendJson(ex, buildJsonArray {
                    service.renamePlans().forEach { p ->
                        add(buildJsonObject {
                            put("fid", p.fid); put("parentPath", p.parentPath); put("from", p.from); put("to", p.to)
                            put("tmdb", p.tmdb); p.skipped?.let { put("skipped", it) }
                        })
                    }
                })
                "GET /api/thumbnail" -> thumbnail(ex, query["fid"] ?: error("缺少 fid"))
                "GET /api/tmdb/search" -> {
                    val config = service.config()
                    val tmdb = tmdb(config)
                    val kind = query["kind"]?.takeIf { it == "movie" || it == "tv" }
                    sendJson(ex, tmdbSearchJson(tmdb, query["q"].orEmpty(), kind, query["year"]?.toIntOrNull(), config.defaults.language))
                }
                "GET /api/tmdb/show" -> {
                    val config = service.config()
                    sendJson(ex, tmdbShowJson(tmdb(config), query["kind"]!!, query["id"]!!.toInt(), config.defaults.language))
                }
                "POST /api/decide" -> {
                    val b = body(ex)
                    service.decide(
                        fid = b.str("fid")!!,
                        target = b.str("target")!!,
                        season = b["season"]?.jsonPrimitive?.intOrNull,
                        path = b.str("path") ?: b.str("fid")!!,
                        reviewKey = b.str("key"),
                        episodeOffset = b["episodeOffset"]?.jsonPrimitive?.intOrNull,
                    )
                    ok(ex)
                }
                "POST /api/retry" -> { service.retry(body(ex).str("key")!!); ok(ex) }
                "POST /api/rename" -> {
                    val fids = body(ex)["fids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    require(fids.isNotEmpty()) { "没有选择要改名的目录" }
                    service.submit(Service.Job.Rename(fids))
                    ok(ex)
                }
                "POST /api/scan" -> {
                    val full = body(ex)["full"]?.jsonPrimitive?.booleanOrNull == true
                    service.submit(Service.Job.Scan(full, "手动"))
                    ok(ex)
                }
                "POST /api/cookie" -> { service.setCookie(body(ex).str("cookie")!!); ok(ex) }
                "POST /api/login/start" -> {
                    val login = QuarkWebLogin()
                    val qr = login.start()
                    logins[qr.token] = login
                    sendJson(ex, buildJsonObject { put("token", qr.token); put("svg", qrSvg(qr.qrContent)) })
                }
                "GET /api/login/poll" -> {
                    val token = query["token"] ?: error("缺少 token")
                    val login = logins[token] ?: error("二维码过期了, 刷新一下")
                    val cookie = login.poll(token)
                    if (cookie != null) {
                        logins.remove(token)
                        service.setCookie(cookie)
                    }
                    sendJson(ex, buildJsonObject { put("done", cookie != null) })
                }
                "GET /api/library" -> sendJson(ex, libraryJson())
                "GET /api/pending" -> {
                    val inReview = service.review.keys()
                    val works = service.pendingWorks()
                    sendJson(ex, buildJsonObject {
                        put("scanned", works != null)
                        put("works", buildJsonArray {
                            works.orEmpty().forEach { w ->
                                add(buildJsonObject {
                                    put("work", json.encodeToJsonElement(PendingWork.serializer(), w))
                                    put("inReview", w.key in inReview)
                                })
                            }
                        })
                    })
                }
                "POST /api/undecide" -> { service.undecide(body(ex).str("fid")!!); ok(ex) }
                "GET /api/config" -> sendJson(ex, configJson())
                "POST /api/config" -> sendJson(ex, saveConfig(body(ex)))
                "GET /api/update" -> sendJson(ex, updateJson())
                "POST /api/update/check" -> {
                    // 自动更新可能会等任务做完再重启, 不能卡住请求
                    Thread({ service.checkUpdate() }, "arisa-update-manual").apply { isDaemon = true }.start()
                    ok(ex)
                }
                else -> send(ex, 404, "text/plain; charset=utf-8", "not found")
            }
        } catch (e: Exception) {
            runCatching { sendJson(ex, buildJsonObject { put("error", e.message ?: e.javaClass.simpleName) }, 400) }
        } finally {
            ex.close()
        }
    }

    private fun tmdb(config: Config): TmdbApi = service.tmdb(config) ?: error("没有 TMDB API Key")

    // ---------- 片库 ----------

    /** 已识别的作品 + 它们来自映射表里的哪几条, 以及跳过的条目. */
    private fun libraryJson(): JsonElement {
        val mapping = service.store.load().entries
        val byTarget = mapping.entries.groupBy { it.value.target }
        fun entryJson(fid: String, e: MappingEntry) = buildJsonObject {
            put("fid", fid); put("path", e.path); put("by", e.by); put("at", e.at)
            e.season?.let { put("season", it) }
            e.episodeOffset?.let { put("episodeOffset", it) }
            e.note?.let { put("note", it) }
        }
        val library = service.library()
        return buildJsonObject {
            put("generatedAt", library?.generatedAt ?: 0)
            put("items", buildJsonArray {
                library?.items.orEmpty().forEach { item ->
                    add(buildJsonObject {
                        put("id", item.id); put("title", item.title); put("type", item.type.name.lowercase())
                        item.originalTitle?.let { put("originalTitle", it) }
                        item.year?.let { put("year", it) }
                        item.poster?.let { put("poster", it) }
                        item.rating?.let { put("rating", it) }
                        put("addedAt", item.addedAt)
                        put("files", item.files.size + item.seasons.sumOf { s -> s.episodes.sumOf { e -> e.files.size } })
                        put("seasons", item.seasons.size)
                        put("mappings", buildJsonArray { byTarget[item.id].orEmpty().forEach { add(entryJson(it.key, it.value)) } })
                    })
                }
            })
            put("skipped", buildJsonArray { byTarget[MappingEntry.SKIP].orEmpty().forEach { add(entryJson(it.key, it.value)) } })
        }
    }

    // ---------- 设置 ----------

    private val configJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    /** 配置文件的内容 (不含环境变量), key 只露后 4 位. */
    private fun configJson(): JsonElement {
        val raw = Config.loadFile(service.configFile)
        val masked = raw.copy(
            tmdb = raw.tmdb.copy(apiKey = mask(raw.tmdb.apiKey)),
            agent = raw.agent.copy(apiKey = mask(raw.agent.apiKey)),
        )
        return buildJsonObject {
            put("config", configJson.encodeToJsonElement(Config.serializer(), masked))
            put("path", service.configFile.path)
            put("env", buildJsonArray {
                for (k in listOf("ARISA_TMDB_API_KEY", "ARISA_DEEPSEEK_API_KEY")) if (!System.getenv(k).isNullOrBlank()) add(k)
            })
        }
    }

    private fun saveConfig(body: JsonObject): JsonElement {
        val old = Config.loadFile(service.configFile)
        val incoming = configJson.decodeFromJsonElement(Config.serializer(), body["config"] ?: error("缺少 config"))
        // 没改过的 key 传回来的是打码的样子, 换回原值
        fun keep(new: String, old: String) = if (new.startsWith(MASK)) old else new.trim()
        val config = incoming.copy(
            tmdb = incoming.tmdb.copy(apiKey = keep(incoming.tmdb.apiKey, old.tmdb.apiKey)),
            agent = incoming.agent.copy(apiKey = keep(incoming.agent.apiKey, old.agent.apiKey)),
            libraries = incoming.libraries.map { it.copy(path = "/" + it.path.trim().trim('/')) },
        )
        require(config.libraries.all { it.path != "/" }) { "片库目录不能是网盘根目录" }
        require(config.libraries.map { it.path }.toSet().size == config.libraries.size) { "片库目录重复了" }
        require(config.serve.port in 1..65535) { "端口不对" }
        Config.save(service.configFile, config)
        service.log("网页上修改了设置")
        // 新加的目录在网盘里找不到时提醒一下, 照样保存 (可能还没建)
        val warnings = buildJsonArray {
            for (lib in config.libraries.filter { l -> old.libraries.none { it.path == l.path } }) {
                runCatching { service.drive.resolve(lib.path) }
                    .onSuccess { if (it == null) add("网盘里找不到 ${lib.path}") }
                    .onFailure { add("${lib.path}: 没法确认网盘里有没有 (${it.message})") }
            }
            if (config.serve.port != old.serve.port) add("端口要重启服务后才生效")
        }
        if (config.libraries != old.libraries) service.submit(Service.Job.Scan(false, "片库目录变了"))
        return buildJsonObject { put("ok", true); put("warnings", warnings) }
    }

    private fun mask(key: String) = if (key.isEmpty()) "" else MASK + key.takeLast(4)

    // ---------- 更新 ----------

    private fun updateJson(): JsonElement {
        val u = service.updater
        val c = service.config().update
        return buildJsonObject {
            put("version", Updater.currentVersion())
            put("managed", u.managed)
            u.latest?.let { m ->
                put("latest", m.version)
                put("latestCode", m.versionCode)
                m.notes?.let { put("notes", it) }
                put("publishedAt", m.publishedAt)
            }
            put("currentCode", arisa.library.ReleaseManifest.versionCode(Updater.currentVersion()))
            u.appOnDrive?.let { put("appOnDrive", it) }
            u.lastCheck?.let { put("lastCheck", it) }
            u.lastError?.let { put("error", it) }
            put("selfUpdate", c.selfUpdate)
            put("mirrorApp", c.app)
            put("checkHours", c.checkHours)
        }
    }

    /** 二维码画成 SVG, 一个模块一个方块. */
    private fun qrSvg(content: String): String {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(
            content, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2),
        )
        return buildString {
            append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${m.width} ${m.height}" shape-rendering="crispEdges">""")
            append("""<rect width="100%" height="100%" fill="#fff"/><path fill="#000" d="""")
            for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) append("M${x} ${y}h1v1h-1z")
            append("""" /></svg>""")
        }
    }

    /** 夸克缩略图要带 Cookie, 浏览器不能直接引用, 由这里转发. 地址可能过期, 先重新列一次目录. */
    private fun thumbnail(ex: HttpExchange, fid: String) {
        val file = runCatching { service.drive.fresh(fid) }.getOrNull() ?: service.drive.cached(fid)
            ?: return send(ex, 404, "text/plain; charset=utf-8", "没有这个文件")
        val bytes = runCatching { service.drive.thumbnail(file) }.getOrElse {
            return send(ex, 404, "text/plain; charset=utf-8", it.message ?: "没有缩略图")
        }
        ex.responseHeaders.add("Cache-Control", "max-age=86400")
        val webp = bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        send(ex, 200, if (webp) "image/webp" else "image/jpeg", bytes)
    }

    private fun body(ex: HttpExchange): JsonObject =
        Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString().ifBlank { "{}" }).jsonObject

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun ok(ex: HttpExchange) = sendJson(ex, buildJsonObject { put("ok", true) })

    private fun sendJson(ex: HttpExchange, body: JsonElement, code: Int = 200) =
        send(ex, code, "application/json; charset=utf-8", json.encodeToString(JsonElement.serializer(), body))

    private fun send(ex: HttpExchange, code: Int, type: String, body: String) = send(ex, code, type, body.toByteArray())

    private fun send(ex: HttpExchange, code: Int, type: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private companion object {
        const val MASK = "••••"
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }

    private fun resource(name: String) =
        javaClass.getResourceAsStream("/arisa/$name")!!.readBytes().decodeToString()
}
