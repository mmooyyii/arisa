package arisa.scraper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

fun httpClient(proxy: String?): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .apply {
        if (!proxy.isNullOrBlank()) {
            val uri = URI(proxy)
            val type = if (uri.scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
            proxy(Proxy(type, InetSocketAddress(uri.host, uri.port)))
        }
    }
    .build()

/**
 * TMDB v3 接口, 响应缓存在磁盘上 (默认 7 天), 重复扫描不重复请求.
 * api_key 支持 v3 key 和 v4 read access token (eyJ 开头).
 */
interface TmdbApi {
    fun searchMovie(query: String, year: Int?, language: String): List<Tmdb.SearchResult>
    fun searchTv(query: String, year: Int?, language: String): List<Tmdb.SearchResult>
    fun movie(id: Int, language: String): Tmdb.Details
    fun tv(id: Int, language: String): Tmdb.Details
    fun season(tvId: Int, number: Int, language: String): Tmdb.SeasonDetails
    fun findByImdb(imdbId: String): Pair<Tmdb.MediaKind, Int>?
}

class Tmdb(
    private val apiKey: String,
    private val http: OkHttpClient,
    private val cacheDir: File,
    private val cacheTtlMs: Long = 7L * 24 * 3600 * 1000,
) : TmdbApi {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override fun searchMovie(query: String, year: Int?, language: String): List<SearchResult> =
        get("/search/movie", mapOf("query" to query, "language" to language) + optional("year", year))
            .let { json.decodeFromString(SearchPage.serializer(), it).results }

    override fun searchTv(query: String, year: Int?, language: String): List<SearchResult> =
        get("/search/tv", mapOf("query" to query, "language" to language) + optional("first_air_date_year", year))
            .let { json.decodeFromString(SearchPage.serializer(), it).results }

    override fun movie(id: Int, language: String): Details = details("/movie/$id", language, "credits,images,external_ids,release_dates")

    override fun tv(id: Int, language: String): Details = details("/tv/$id", language, "credits,images,external_ids,content_ratings")

    override fun season(tvId: Int, number: Int, language: String): SeasonDetails =
        get("/tv/$tvId/season/$number", mapOf("language" to language))
            .let { json.decodeFromString(SeasonDetails.serializer(), it) }

    /** IMDb ID → TMDB, 读已有 NFO 时用. */
    override fun findByImdb(imdbId: String): Pair<MediaKind, Int>? {
        val r = json.decodeFromString(FindResult.serializer(), get("/find/$imdbId", mapOf("external_source" to "imdb_id")))
        r.movieResults.firstOrNull()?.let { return MediaKind.MOVIE to it.id }
        r.tvResults.firstOrNull()?.let { return MediaKind.TV to it.id }
        return null
    }

    private fun details(path: String, language: String, append: String): Details {
        val lang = language.substringBefore('-')
        return get(path, mapOf(
            "language" to language,
            "append_to_response" to append,
            // Logo/背景图 只拿中文、英文和无文字的
            "include_image_language" to "$lang,en,null",
        )).let { json.decodeFromString(Details.serializer(), it) }
    }

    private fun optional(key: String, value: Any?) = if (value == null) emptyMap() else mapOf(key to value.toString())

    private fun get(path: String, query: Map<String, String>): String {
        // api.themoviedb.org 在国内不通, 备用域名 api.tmdb.org 可以直连
        val url = "https://api.tmdb.org/3$path".toHttpUrl().newBuilder().apply {
            query.toSortedMap().forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val cacheFile = File(cacheDir, sha1(url.toString()) + ".json")
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < cacheTtlMs) {
            return cacheFile.readText()
        }
        val req = Request.Builder().url(url.newBuilder().apply {
            if (!isBearer) addQueryParameter("api_key", apiKey)
        }.build()).apply {
            if (isBearer) header("Authorization", "Bearer $apiKey")
            header("Accept", "application/json")
        }.build()

        var attempt = 0
        while (true) {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    cacheDir.mkdirs()
                    cacheFile.writeText(body)
                    return body
                }
                // 429 限流时退避重试
                if (resp.code == 429 && attempt < 5) {
                    Thread.sleep(1000L shl attempt)
                    attempt++
                } else {
                    throw IllegalStateException("TMDB $path ${resp.code}: ${body.take(200)}")
                }
            }
        }
    }

    private val isBearer get() = apiKey.startsWith("eyJ")

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    enum class MediaKind { MOVIE, TV }

    @Serializable
    data class SearchPage(val results: List<SearchResult> = emptyList())

    @Serializable
    data class SearchResult(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        @SerialName("original_title") val originalTitle: String? = null,
        @SerialName("original_name") val originalName: String? = null,
        @SerialName("release_date") val releaseDate: String? = null,
        @SerialName("first_air_date") val firstAirDate: String? = null,
        val overview: String? = null,
        val popularity: Double = 0.0,
        @SerialName("vote_count") val voteCount: Int = 0,
        @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
        @SerialName("original_language") val originalLanguage: String? = null,
    ) {
        val displayTitle get() = title ?: name ?: ""
        val displayOriginal get() = originalTitle ?: originalName
        val year get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
    }

    @Serializable
    data class FindResult(
        @SerialName("movie_results") val movieResults: List<SearchResult> = emptyList(),
        @SerialName("tv_results") val tvResults: List<SearchResult> = emptyList(),
    )

    @Serializable
    data class Details(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        @SerialName("original_title") val originalTitle: String? = null,
        @SerialName("original_name") val originalName: String? = null,
        @SerialName("release_date") val releaseDate: String? = null,
        @SerialName("first_air_date") val firstAirDate: String? = null,
        val overview: String? = null,
        val tagline: String? = null,
        val genres: List<Genre> = emptyList(),
        @SerialName("vote_average") val voteAverage: Double? = null,
        val runtime: Int? = null,
        @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
        @SerialName("poster_path") val posterPath: String? = null,
        @SerialName("backdrop_path") val backdropPath: String? = null,
        @SerialName("imdb_id") val imdbId: String? = null,
        @SerialName("external_ids") val externalIds: ExternalIds? = null,
        val credits: Credits? = null,
        val images: Images? = null,
        val seasons: List<SeasonSummary> = emptyList(),
        @SerialName("release_dates") val releaseDates: ReleaseDates? = null,
        @SerialName("content_ratings") val contentRatings: ContentRatings? = null,
    ) {
        val displayTitle get() = title ?: name ?: ""
        val displayOriginal get() = originalTitle ?: originalName
        val year get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
    }

    @Serializable
    data class Genre(val id: Int, val name: String)

    @Serializable
    data class ExternalIds(@SerialName("imdb_id") val imdbId: String? = null)

    @Serializable
    data class Credits(val cast: List<Cast> = emptyList())

    @Serializable
    data class Cast(
        val name: String,
        val character: String? = null,
        @SerialName("profile_path") val profilePath: String? = null,
    )

    @Serializable
    data class Images(
        val backdrops: List<Image> = emptyList(),
        val logos: List<Image> = emptyList(),
        val posters: List<Image> = emptyList(),
    )

    @Serializable
    data class Image(
        @SerialName("file_path") val filePath: String,
        @SerialName("iso_639_1") val language: String? = null,
        @SerialName("vote_average") val voteAverage: Double = 0.0,
        val width: Int = 0,
        val height: Int = 0,
    )

    @Serializable
    data class SeasonSummary(
        @SerialName("season_number") val seasonNumber: Int,
        val name: String? = null,
        @SerialName("poster_path") val posterPath: String? = null,
        @SerialName("episode_count") val episodeCount: Int = 0,
    )

    @Serializable
    data class SeasonDetails(
        @SerialName("season_number") val seasonNumber: Int,
        val name: String? = null,
        val overview: String? = null,
        @SerialName("poster_path") val posterPath: String? = null,
        val episodes: List<EpisodeDetails> = emptyList(),
    )

    @Serializable
    data class EpisodeDetails(
        @SerialName("episode_number") val episodeNumber: Int,
        @SerialName("season_number") val seasonNumber: Int,
        val name: String? = null,
        val overview: String? = null,
        @SerialName("still_path") val stillPath: String? = null,
        @SerialName("air_date") val airDate: String? = null,
        val runtime: Int? = null,
    )

    @Serializable
    data class ReleaseDates(val results: List<ReleaseDateCountry> = emptyList())

    @Serializable
    data class ReleaseDateCountry(
        @SerialName("iso_3166_1") val country: String,
        @SerialName("release_dates") val releaseDates: List<ReleaseDate> = emptyList(),
    )

    @Serializable
    data class ReleaseDate(val certification: String = "")

    @Serializable
    data class ContentRatings(val results: List<ContentRating> = emptyList())

    @Serializable
    data class ContentRating(@SerialName("iso_3166_1") val country: String, val rating: String = "")
}
