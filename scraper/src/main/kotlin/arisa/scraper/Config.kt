package arisa.scraper

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
    @SerialName("confidence_threshold") val confidenceThreshold: Double? = null,
    val language: String? = null,
    @SerialName("fallback_language") val fallbackLanguage: String? = null,
)

@Serializable
data class Defaults(
    val output: OutputMode = OutputMode.INDEX_NFO,
    @SerialName("existing_nfo") val existingNfo: ExistingNfo = ExistingNfo.TRUST,
    /** auto 命令自动接受规则匹配的门槛, 低于它的交给 skill. */
    @SerialName("confidence_threshold") val confidenceThreshold: Double = 0.97,
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
data class RateLimit(
    @SerialName("list_per_sec") val listPerSec: Double = 2.0,
    @SerialName("upload_per_sec") val uploadPerSec: Double = 1.0,
)

@Serializable
data class ScanConfig(
    /** 保留几个历史索引. */
    @SerialName("keep_indexes") val keepIndexes: Int = 5,
)

/** 常驻服务里自动识别用的 AI (DeepSeek). */
@Serializable
data class AgentConfig(
    val enabled: Boolean = true,
    /** DeepSeek API key, 也可以用环境变量 ARISA_DEEPSEEK_API_KEY. 为空时不启用 AI. */
    @SerialName("api_key") val apiKey: String = "",
    /** 花费按 Flash 的价格算, 换成 pro 等更贵的模型时网页上的花费会偏低. */
    val model: String = "deepseek-chat",
    /** 一次交给 AI 的作品数. */
    @SerialName("batch_size") val batchSize: Int = 20,
    /** 单次运行的时间上限. */
    @SerialName("timeout_minutes") val timeoutMinutes: Int = 30,
) {
    /** 打开了并且有 key. */
    val on get() = enabled && apiKey.isNotBlank()
}

/** 常驻服务. */
@Serializable
data class ServeConfig(
    val port: Int = 7893,
    /** 秒, 多久看一次夸克的「最近」. */
    @SerialName("poll_seconds") val pollSeconds: Int = 90,
    /** 每天几点全量重扫 (移动、删除在「最近」里看不到). */
    @SerialName("full_scan_hour") val fullScanHour: Int = 4,
)

@Serializable
data class Config(
    val defaults: Defaults = Defaults(),
    val tmdb: TmdbConfig = TmdbConfig(),
    @SerialName("rate_limit") val rateLimit: RateLimit = RateLimit(),
    val scan: ScanConfig = ScanConfig(),
    val agent: AgentConfig = AgentConfig(),
    val serve: ServeConfig = ServeConfig(),
    val libraries: List<LibraryConfig> = emptyList(),
) {
    /** 合并 defaults 和片库自己的覆盖项. */
    fun effective(lib: LibraryConfig) = Effective(
        output = lib.output ?: defaults.output,
        existingNfo = lib.existingNfo ?: defaults.existingNfo,
        confidenceThreshold = lib.confidenceThreshold ?: defaults.confidenceThreshold,
        language = lib.language ?: defaults.language,
        fallbackLanguage = lib.fallbackLanguage ?: defaults.fallbackLanguage,
    )

    data class Effective(
        val output: OutputMode,
        val existingNfo: ExistingNfo,
        val confidenceThreshold: Double,
        val language: String,
        val fallbackLanguage: String,
    )

    companion object {
        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

        /** key 也可以用环境变量传, 优先级高于配置文件. */
        fun load(file: File, env: Map<String, String> = System.getenv()): Config {
            val config = if (file.exists()) yaml.decodeFromString(serializer(), file.readText()) else Config()
            return config.copy(
                tmdb = config.tmdb.copy(apiKey = env["ARISA_TMDB_API_KEY"]?.takeIf { it.isNotBlank() } ?: config.tmdb.apiKey),
                agent = config.agent.copy(apiKey = env["ARISA_DEEPSEEK_API_KEY"]?.takeIf { it.isNotBlank() } ?: config.agent.apiKey),
            )
        }
    }
}
