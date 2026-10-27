package arisa.scraper

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
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/** mac mini 上的管理网页: 状态、待确认、改名计划、动态、夸克登录. 只在局域网用, 没有鉴权. */
class WebServer(private val service: Service, private val port: Int) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

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
                else -> send(ex, 404, "text/plain; charset=utf-8", "not found")
            }
        } catch (e: Exception) {
            runCatching { sendJson(ex, buildJsonObject { put("error", e.message ?: e.javaClass.simpleName) }, 400) }
        } finally {
            ex.close()
        }
    }

    private fun tmdb(config: Config): TmdbApi = service.tmdb(config) ?: error("没有 TMDB API Key")

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

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }

    private fun resource(name: String) =
        javaClass.getResourceAsStream("/arisa/$name")!!.readBytes().decodeToString()
}
