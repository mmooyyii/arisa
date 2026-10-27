package arisa.scraper

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit

/** 识别规则, skill 和无人值守的 AI 共用 (arisa-scraper guide 打印的就是它). */
fun guideText(): String = resource("guide.md")

private fun resource(name: String) =
    object {}.javaClass.getResourceAsStream("/arisa/$name")!!.readBytes().decodeToString()

/**
 * 用 DeepSeek 识别一批作品: 把 arisa-scraper 的几个子命令和看缩略图当成工具交给模型, 循环执行到它给出总结.
 * 结论通过 decide / ask 命令写进映射表和待确认列表, 这里只负责跑工具、限时、留日志.
 */
class AgentRunner(
    private val config: AgentConfig,
    private val home: File,
) {
    /** [costCny]: 按 token 用量和 DeepSeek 价格算的花费. */
    data class Result(val ok: Boolean, val summary: String, val costCny: Double?, val logFile: File)

    private val json = Json { prettyPrint = true; encodeDefaults = false; explicitNulls = false }
    private val http = httpClient(null)

    fun run(works: List<PendingWork>): Result {
        val workDir = File(home, "agent").apply { mkdirs() }
        val logDir = File(home, "logs").apply { mkdirs() }
        val logFile = File(logDir, "agent-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.log")
        val log = StringBuilder()
        val prompt = buildString {
            appendLine("待识别 ${works.size} 部:")
            appendLine("```json")
            appendLine(json.encodeToString(ListSerializer(PendingWork.serializer()), works))
            appendLine("```")
        }
        log.appendLine(prompt)
        val messages = mutableListOf<JsonObject>(
            message("system", resource("agent.md") + guideText()),
            message("user", prompt),
        )
        val deadline = System.currentTimeMillis() + config.timeoutMinutes * 60_000L
        var cost = 0.0
        fun finish(ok: Boolean, summary: String): Result {
            logFile.writeText("$log\n--- 结果 (${if (ok) "完成" else "出错"}, ¥%.4f)\n$summary\n".format(cost))
            return Result(ok, summary, cost, logFile)
        }

        repeat(MAX_TURNS) {
            if (System.currentTimeMillis() > deadline) return finish(false, "超过 ${config.timeoutMinutes} 分钟, 已停止")
            val resp = try {
                chat(messages)
            } catch (e: Exception) {
                return finish(false, "调用 DeepSeek 出错: ${e.message}")
            }
            (resp["usage"] as? JsonObject)?.let { cost += costCny(it) }
            val msg = resp["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
            messages += JsonObject(msg.filterKeys { it in setOf("role", "content", "tool_calls") })
            val text = msg["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isNotEmpty()) log.appendLine("\n[AI] $text")
            val calls = (msg["tool_calls"] as? JsonArray).orEmpty()
            if (calls.isEmpty()) return finish(true, text)

            val images = mutableListOf<File>()
            for (call in calls.map { it.jsonObject }) {
                val fn = call["function"]!!.jsonObject
                val name = fn["name"]!!.jsonPrimitive.content
                val args = runCatching { Json.parseToJsonElement(fn["arguments"]!!.jsonPrimitive.content).jsonObject }
                    .getOrDefault(JsonObject(emptyMap()))
                val output = when (name) {
                    "arisa_scraper" -> runScraper(args["args"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), workDir)
                    "thumbnail" -> {
                        val fid = args["fid"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val file = File(workDir, "thumb-$fid.jpg")
                        val out = runScraper(listOf("thumbnail", "--fid", fid, file.path), workDir, allowThumbnail = true)
                        if (file.exists() && file.length() > 0) {
                            images += file
                            "缩略图在下一条消息里"
                        } else out
                    }
                    else -> "没有这个工具: $name"
                }
                log.appendLine("\n[$name] $args\n$output")
                messages += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", call["id"]!!.jsonPrimitive.content)
                    put("content", output)
                }
            }
            // tool 消息只能放文字, 图片跟在后面单独发一条
            if (images.isNotEmpty()) messages += buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    for (f in images) {
                        addJsonObject { put("type", "text"); put("text", "缩略图 ${f.name}:") }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(f.readBytes())) }
                        }
                    }
                }
            }
        }
        return finish(false, "超过 $MAX_TURNS 轮还没结束, 已停止")
    }

    private fun message(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }

    private fun chat(messages: List<JsonObject>): JsonObject {
        val body = buildJsonObject {
            put("model", config.model)
            put("messages", JsonArray(messages))
            put("tools", TOOLS)
        }
        val req = Request.Builder().url("$DEEPSEEK/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body!!.string()
            require(resp.isSuccessful) { "HTTP ${resp.code}: ${text.take(300)}" }
            return Json.parseToJsonElement(text).jsonObject
        }
    }

    /** 运行 arisa-scraper 子命令, 只放行识别用得到的几个. */
    private fun runScraper(args: List<String>, workDir: File, allowThumbnail: Boolean = false): String {
        val allowed = setOf("decide", "ask", "tmdb", "bangumi") + if (allowThumbnail) setOf("thumbnail") else emptySet()
        if (args.firstOrNull() !in allowed) return "只能用 ${allowed.joinToString(" / ")} 子命令"
        val process = ProcessBuilder(listOf("arisa-scraper") + args).directory(workDir).apply {
            // launchd 下 PATH 很短, 补上 arisa-scraper 常见的安装位置
            val path = environment()["PATH"].orEmpty()
            val extra = listOf("${System.getProperty("user.home")}/.local/bin", "/opt/homebrew/bin", "/usr/local/bin")
            environment()["PATH"] = (path.split(':') + extra).filter { it.isNotEmpty() }.distinct().joinToString(":")
            environment()["ARISA_HOME"] = home.path
        }.start()
        val stderr = StringBuilder()
        val reader = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }.apply { start() }
        val stdout = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.MINUTES)) process.destroyForcibly()
        reader.join(10_000)
        val out = if (process.exitValue() == 0) stdout else "$stdout\n出错 (退出码 ${process.exitValue()}): ${stderr.toString().trim().takeLast(1000)}"
        return out.trim().let { if (it.length > MAX_OUTPUT) it.take(MAX_OUTPUT) + "\n…(太长, 截断了)" else it }
    }

    /** 按 DeepSeek Flash 的价格算. 余额接口只精确到分, 一小批的花费看不出来. */
    private fun costCny(usage: JsonObject): Double {
        fun n(key: String) = usage[key]?.jsonPrimitive?.longOrNull ?: 0
        val now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
        // 工作日 9-12 点、14-18 点是高峰, 价格翻倍 (不算节假日, 节假日会多算)
        val peak = now.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && now.hour in (9..11) + (14..17)
        val yuan = n("prompt_cache_miss_tokens") * 1.0 + n("prompt_cache_hit_tokens") * 0.02 + n("completion_tokens") * 4.0
        return yuan / 1_000_000 * (if (peak) 2 else 1)
    }

    companion object {
        private const val DEEPSEEK = "https://api.deepseek.com"
        private const val MAX_TURNS = 200
        private const val MAX_OUTPUT = 20_000

        private val TOOLS = buildJsonArray {
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "arisa_scraper")
                    put("description", "运行一条 arisa-scraper 命令, 只能用 decide / ask / tmdb / bangumi 子命令. 返回命令输出.")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("args") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                                put("description", "arisa-scraper 后面的参数, 每个参数一项, 不用加引号. 如 [\"tmdb\", \"search\", \"无职转生\", \"--tv\"]")
                            }
                        }
                        putJsonArray("required") { add("args") }
                    }
                }
            }
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "thumbnail")
                    put("description", "看一个视频文件的缩略图 (夸克生成的一帧画面), 图片会发给你.")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("fid") { put("type", "string"); put("description", "视频文件的 fid, 一般用 sampleFid") }
                        }
                        putJsonArray("required") { add("fid") }
                    }
                }
            }
        }
    }
}
