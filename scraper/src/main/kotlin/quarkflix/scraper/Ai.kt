package quarkflix.scraper

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/** AI 解析出的文件名信息. */
@Serializable
data class AiParsed(
    val index: Int,
    @SerialName("title_zh") val titleZh: String? = null,
    @SerialName("title_en") val titleEn: String? = null,
    val year: Int? = null,
    /** movie / tv / unknown */
    val type: String = "unknown",
    val season: Int? = null,
    val episode: Int? = null,
)

/** AI 在候选里选出的结果, tmdbId 为 null 表示都不对. */
@Serializable
data class AiChoice(
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    val confidence: Double = 0.0,
)

data class Candidate(val id: Int, val title: String, val originalTitle: String?, val year: Int?, val overview: String?)

/**
 * 只做两件事: 从乱文件名里抽结构化信息, 以及在 TMDB 候选里做选择题.
 * 不让模型自己"想"出元数据, 避免幻觉.
 */
class Ai(private val config: AiConfig) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val enabled = config.provider != "none"

    private val anthropic: AnthropicClient? by lazy {
        if (config.provider != "anthropic") return@lazy null
        AnthropicOkHttpClient.builder()
            .apply { if (config.apiKey.isNotBlank()) apiKey(config.apiKey) else fromEnv() }
            .apply { config.proxy?.takeIf { it.isNotBlank() }?.let { proxy(toProxy(it)) } }
            .build()
    }

    private val http by lazy { httpClient(config.proxy) }

    fun parse(paths: List<String>): List<AiParsed> {
        if (paths.isEmpty()) return emptyList()
        val input = buildJsonArray {
            paths.forEachIndexed { i, p -> add(buildJsonObject { put("index", i); put("path", p) }) }
        }
        val prompt = """
            下面是网盘里一些视频文件的路径, 请识别每个文件对应的影视作品.
            - title_zh: 中文片名 (路径里没有就留空, 不要自己翻译); title_en: 英文或原文片名
            - year: 路径里出现的年份; type: movie / tv / unknown
            - season / episode: 剧集的季和集 (路径里没写季就填 1)
            - 片名只能来自路径里的文字, 去掉字幕组、分辨率、编码、网站广告等信息
            只返回 JSON.

            $input
        """.trimIndent()
        val out = complete(prompt, PARSE_SCHEMA) ?: return emptyList()
        return out["items"]?.jsonArray?.map { json.decodeFromJsonElement(AiParsed.serializer(), it) } ?: emptyList()
    }

    fun choose(path: String, kind: String, candidates: List<Candidate>): AiChoice? {
        if (candidates.isEmpty()) return null
        val list = buildJsonArray {
            candidates.forEach { c ->
                add(buildJsonObject {
                    put("tmdb_id", c.id)
                    put("title", c.title)
                    c.originalTitle?.let { put("original_title", it) }
                    c.year?.let { put("year", it) }
                    c.overview?.let { put("overview", it.take(150)) }
                })
            }
        }
        val prompt = """
            网盘里的视频文件: $path
            类型: $kind
            下面是 TMDB 搜索到的候选, 请判断这个文件是哪一个.
            只能从候选里选 tmdb_id; 都不对就返回 null. confidence 是 0~1 的把握程度.
            只返回 JSON.

            $list
        """.trimIndent()
        val out = complete(prompt, CHOOSE_SCHEMA) ?: return null
        val choice = json.decodeFromJsonElement(AiChoice.serializer(), out)
        // 防御: 模型返回了不在候选里的 ID 就当没选
        return if (choice.tmdbId == null || candidates.any { it.id == choice.tmdbId }) choice else AiChoice(null, 0.0)
    }

    private val model: String = config.model ?: when (config.provider) {
        "anthropic" -> "claude-opus-5"
        "deepseek" -> "deepseek-chat"
        else -> "qwen2.5:7b"
    }

    private fun complete(prompt: String, schema: JsonObject): JsonObject? = when (config.provider) {
        "anthropic" -> completeAnthropic(prompt, schema)
        "deepseek" -> completeOpenAi(prompt, config.baseUrl ?: "https://api.deepseek.com")
        "openai" -> completeOpenAi(prompt, config.baseUrl ?: "http://127.0.0.1:11434/v1")
        else -> null
    }

    private fun completeAnthropic(prompt: String, schema: JsonObject): JsonObject? {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.of(config.effort))
                    .format(JsonOutputFormat.builder().schema(toSchema(schema)).build())
                    .build()
            )
            .addUserMessage(prompt)
            .build()
        val resp = anthropic!!.messages().create(params)
        if (resp.stopReason().orElse(null) == StopReason.REFUSAL) return null
        val text = resp.content().flatMap { it.text().map { t -> listOf(t.text()) }.orElse(emptyList()) }.joinToString("")
        return parseJsonObject(text)
    }

    /** OpenAI 兼容接口: DeepSeek、本地 Ollama 等. */
    private fun completeOpenAi(prompt: String, baseUrl: String): JsonObject? {
        val base = baseUrl.trimEnd('/')
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
            })
        }
        val req = Request.Builder().url("$base/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
            .build()
        val text = http.newCall(req).execute().use { it.body?.string() ?: "" }
        val content = json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: return null
        return parseJsonObject(content)
    }

    private fun parseJsonObject(text: String): JsonObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun toSchema(schema: JsonObject): JsonOutputFormat.Schema =
        JsonOutputFormat.Schema.builder().apply {
            schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(toPlain(v))) }
        }.build()

    private fun toPlain(e: kotlinx.serialization.json.JsonElement): Any? = when (e) {
        is JsonObject -> e.mapValues { toPlain(it.value) }
        is kotlinx.serialization.json.JsonArray -> e.map { toPlain(it) }
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive -> when {
            e.isString -> e.content
            e.content == "true" || e.content == "false" -> e.content.toBoolean()
            e.content.contains('.') -> e.content.toDouble()
            else -> e.content.toLong()
        }
    }

    private fun toProxy(url: String): Proxy {
        val uri = URI(url)
        val type = if (uri.scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(type, InetSocketAddress(uri.host, uri.port))
    }

    private companion object {
        val NULLABLE_INT = buildJsonObject { put("type", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("integer")); add(kotlinx.serialization.json.JsonPrimitive("null")) }) }
        val NULLABLE_STRING = buildJsonObject { put("type", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("string")); add(kotlinx.serialization.json.JsonPrimitive("null")) }) }

        val PARSE_SCHEMA = Json.parseToJsonElement(
            """
            {"type":"object","additionalProperties":false,"required":["items"],
             "properties":{"items":{"type":"array","items":{
               "type":"object","additionalProperties":false,
               "required":["index","title_zh","title_en","year","type","season","episode"],
               "properties":{
                 "index":{"type":"integer"},
                 "title_zh":$NULLABLE_STRING,"title_en":$NULLABLE_STRING,
                 "year":$NULLABLE_INT,
                 "type":{"type":"string","enum":["movie","tv","unknown"]},
                 "season":$NULLABLE_INT,"episode":$NULLABLE_INT}}}}}
            """
        ).jsonObject

        val CHOOSE_SCHEMA = Json.parseToJsonElement(
            """
            {"type":"object","additionalProperties":false,"required":["tmdb_id","confidence"],
             "properties":{"tmdb_id":$NULLABLE_INT,"confidence":{"type":"number"}}}
            """
        ).jsonObject
    }
}
