package arisa.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 刮削结果的汇总索引, mac mini 写进夸克 `/.arisa/library-<时间戳>.json.gz`, TV 读.
 * 图片字段存 TMDB 的路径 (如 "/abc.jpg"), 用 [tmdbImage] 拼成 URL.
 */
@Serializable
data class Library(
    val schema: Int = SCHEMA,
    val generatedAt: Long,
    val items: List<MediaItem> = emptyList(),
    /** 没识别出来的视频, TV 上放在"未识别"里, 仍然能播. */
    val unmatched: List<MediaFile> = emptyList(),
    /** 合集: TMDB 上同一个系列的电影, 或者同一个顶层目录里的多部作品, TV 首页合成一张卡片. */
    val collections: List<MediaCollection> = emptyList(),
    /**
     * 搜索用的拼音, 刮削时生成: 名字 (片名、原名、合集名、演员、导演、类型) → 逐字读音.
     * 名字去掉空格标点后每个字一项, 用空格分开; 多音字的几个读音用 | 隔开, 非汉字原样.
     * 如 "长安3" → "chang|zhang an 3". 只收含汉字的名字, 见 [LibrarySearch].
     */
    val pinyin: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SCHEMA = 1
    }
}

/** 一个合集: TMDB 上的电影系列, 或者一个顶层目录里识别出的多部作品 (正篇、新编集版、剧场版…). */
@Serializable
data class MediaCollection(
    /** "tmdb:<TMDB 系列 id>" 或 "dir:<顶层目录 fid>". App 只把它当成一个不重复的字符串. */
    val id: String,
    val name: String,
    /** 封面用哪部作品的图, [MediaItem.id]. */
    val cover: String,
    /** 作品 [MediaItem.id], 按年份排. */
    val items: List<String>,
)

@Serializable
enum class MediaType {
    @SerialName("movie") MOVIE,
    @SerialName("tv") TV,
}

@Serializable
data class MediaItem(
    /** "movie:693134" / "tv:1396", 没有 TMDB ID 时用 "fid:<目录 fid>". */
    val id: String,
    val type: MediaType,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    /** 动画在 Bangumi 上的条目, 片名取自它. */
    val bangumiId: Int? = null,
    val title: String,
    val originalTitle: String? = null,
    /** 别名 (其他地区的译名、英文名、旧译名), 只用来搜索. */
    val aliases: List<String> = emptyList(),
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    /** 出品国家/地区, ISO 3166 代码, 如 "CN" "US". */
    val countries: List<String> = emptyList(),
    /** 原始语言, ISO 639-1 代码, 如 "zh" "ja". */
    val language: String? = null,
    /** TMDB 评分, 0~10. */
    val rating: Double? = null,
    /** 分钟. */
    val runtime: Int? = null,
    val certification: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    /** 片名艺术字, 透明 PNG/SVG. */
    val logo: String? = null,
    val art: Artwork = Artwork(),
    val cast: List<CastMember> = emptyList(),
    /** 电影的导演; 剧集没有固定导演, 用 TMDB 的主创 (created_by). */
    val directors: List<String> = emptyList(),
    /** 电影的文件 (可能有多个版本). */
    val files: List<MediaFile> = emptyList(),
    /** 剧集的季. */
    val seasons: List<Season> = emptyList(),
    /** 最早的文件加入网盘的时间, 毫秒. */
    val addedAt: Long = 0,
    val match: MatchInfo = MatchInfo(),
)

/** 刮削时预先算好的颜色和占位图, TV 直接用. */
@Serializable
data class Artwork(
    /** 主色调, #RRGGBB. */
    val color: String? = null,
    val posterBlurHash: String? = null,
    val backdropBlurHash: String? = null,
)

@Serializable
data class CastMember(
    val name: String,
    val character: String? = null,
    val profile: String? = null,
)

@Serializable
data class Season(
    val number: Int,
    val name: String? = null,
    val overview: String? = null,
    val poster: String? = null,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class Episode(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val overview: String? = null,
    val still: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
    val files: List<MediaFile> = emptyList(),
)

@Serializable
data class MediaFile(
    val fid: String,
    val name: String,
    /** 网盘里的完整路径. */
    val path: String,
    val size: Long,
    val updatedAt: Long = 0,
    /** 时长, 秒, 0 表示不知道. */
    val duration: Int = 0,
    /** 视频旁边的外挂字幕. */
    val subtitles: List<SubtitleFile> = emptyList(),
)

@Serializable
data class SubtitleFile(
    val fid: String,
    val name: String,
    /** 从文件名猜的语言, BCP 47 (zh-Hans / zh-Hant / zh / en / ja …), 猜不出为 null. */
    val language: String? = null,
)

@Serializable
data class MatchInfo(
    /** nfo / rules / ai / manual / none */
    val source: String = "none",
    val confidence: Double = 0.0,
)

/** 观看进度, TV 本地存. key = 文件 fid. */
@Serializable
data class PlaybackProgress(
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 0.92
}

object LibraryCodec {
    const val DIR = ".arisa"
    private const val PREFIX = "library-"
    private const val SUFFIX = ".json.gz"

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun fileName(generatedAt: Long) = "$PREFIX$generatedAt$SUFFIX"

    /** 从目录里的文件名挑出最新的索引, 返回 null 表示没有. */
    fun latest(names: Collection<String>): String? =
        names.filter { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
            .maxByOrNull { it.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull() ?: 0 }

    fun isIndexFile(name: String) = name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    fun encode(library: Library): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.encodeToString(Library.serializer(), library).toByteArray()) }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Library {
        val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.decodeToString()
        return json.decodeFromString(Library.serializer(), text)
    }
}

/** TMDB 图片尺寸: w92 w154 w185 w342 w500 w780 w1280 original. */
fun tmdbImage(path: String?, size: String): String? =
    path?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/$size$it" }
