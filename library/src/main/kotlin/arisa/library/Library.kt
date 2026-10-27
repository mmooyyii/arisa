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
) {
    companion object {
        const val SCHEMA = 1
    }
}

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
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
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
