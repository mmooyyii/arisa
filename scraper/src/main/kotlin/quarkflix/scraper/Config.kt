package quarkflix.scraper

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** 输出模式, 见 README. */
@Serializable
enum class OutputMode {
    /** 只写汇总索引. */
    @SerialName("index") INDEX,
    /** 索引 + 电影 NFO + tvshow.nfo, 图片用远程 URL. */
    @SerialName("index+nfo") INDEX_NFO,
    /** 索引 + 全部 NFO (含单集) + 全部图片. */
    @SerialName("index+full") INDEX_FULL,
}

/** 视频旁已有 NFO 时怎么处理. */
@Serializable
enum class ExistingNfo {
    @SerialName("trust") TRUST,
    @SerialName("merge") MERGE,
    @SerialName("ignore") IGNORE,
    @SerialName("overwrite") OVERWRITE,
}

@Serializable
enum class Recognize {
    @SerialName("rules") RULES,
    @SerialName("rules+ai") RULES_AI,
    @SerialName("ai") AI,
}

@Serializable
enum class LibraryType {
    @SerialName("movie") MOVIE,
    @SerialName("tv") TV,
    @SerialName("auto") AUTO,
}

/** 片库目录, 除 path/type 外的字段为 null 时用 defaults. */
@Serializable
data class LibraryConfig(
    val path: String,
    val type: LibraryType = LibraryType.AUTO,
    val output: OutputMode? = null,
    @SerialName("existing_nfo") val existingNfo: ExistingNfo? = null,
    val recognize: Recognize? = null,
    @SerialName("confidence_threshold") val confidenceThreshold: Double? = null,
    val language: String? = null,
    @SerialName("fallback_language") val fallbackLanguage: String? = null,
)

@Serializable
data class Defaults(
    val output: OutputMode = OutputMode.INDEX_NFO,
    @SerialName("existing_nfo") val existingNfo: ExistingNfo = ExistingNfo.TRUST,
    val recognize: Recognize = Recognize.RULES_AI,
    @SerialName("confidence_threshold") val confidenceThreshold: Double = 0.85,
    val language: String = "zh-CN",
    @SerialName("fallback_language") val fallbackLanguage: String = "en-US",
)

@Serializable
data class TmdbConfig(
    @SerialName("api_key") val apiKey: String = "",
    /** 可选代理, 如 http://127.0.0.1:7890 (默认走 api.tmdb.org, 国内能直连) */
    val proxy: String? = null,
)

@Serializable
data class AiConfig(
    /** anthropic / deepseek / openai (OpenAI 兼容接口, 含 Ollama) / none */
    val provider: String = "none",
    /** 不填按 provider 取默认: anthropic=claude-opus-5, deepseek=deepseek-chat */
    val model: String? = null,
    /** low / medium / high, 只对 anthropic 生效; 文件名识别用 low 就够. */
    val effort: String = "low",
    @SerialName("api_key") val apiKey: String = "",
    /** openai 兼容接口地址, 如 http://127.0.0.1:11434/v1; deepseek 默认 https://api.deepseek.com */
    @SerialName("base_url") val baseUrl: String? = null,
    val proxy: String? = null,
)

@Serializable
data class RateLimit(
    @SerialName("list_per_sec") val listPerSec: Double = 2.0,
    @SerialName("upload_per_sec") val uploadPerSec: Double = 1.0,
)

@Serializable
data class ScanConfig(
    /** 分钟, daemon 模式下的扫描间隔. */
    @SerialName("interval_minutes") val intervalMinutes: Int = 30,
    /** 保留几个历史索引. */
    @SerialName("keep_indexes") val keepIndexes: Int = 5,
)

/** 人工修正: 网盘路径 (目录或文件) → TMDB. */
@Serializable
data class Override(
    val path: String,
    val type: LibraryType,
    @SerialName("tmdb_id") val tmdbId: Int,
)

@Serializable
data class Config(
    val defaults: Defaults = Defaults(),
    val tmdb: TmdbConfig = TmdbConfig(),
    val ai: AiConfig = AiConfig(),
    @SerialName("rate_limit") val rateLimit: RateLimit = RateLimit(),
    val scan: ScanConfig = ScanConfig(),
    val libraries: List<LibraryConfig> = emptyList(),
    val overrides: List<Override> = emptyList(),
) {
    /** 合并 defaults 和片库自己的覆盖项. */
    fun effective(lib: LibraryConfig) = Effective(
        output = lib.output ?: defaults.output,
        existingNfo = lib.existingNfo ?: defaults.existingNfo,
        recognize = lib.recognize ?: defaults.recognize,
        confidenceThreshold = lib.confidenceThreshold ?: defaults.confidenceThreshold,
        language = lib.language ?: defaults.language,
        fallbackLanguage = lib.fallbackLanguage ?: defaults.fallbackLanguage,
    )

    data class Effective(
        val output: OutputMode,
        val existingNfo: ExistingNfo,
        val recognize: Recognize,
        val confidenceThreshold: Double,
        val language: String,
        val fallbackLanguage: String,
    )

    companion object {
        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

        fun load(file: File): Config = yaml.decodeFromString(serializer(), file.readText())
    }
}
