package arisa.scraper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest

interface BangumiApi {
    fun searchAnime(keyword: String): List<Bangumi.Subject>
    fun subject(id: Int): Bangumi.Subject
}

/**
 * Bangumi 番组计划 (bgm.tv), 动画的中文名以它为准. 公开接口不需要 key, 但要求带能识别应用的 User-Agent.
 * 有磁盘缓存, 和 TMDB 一样 7 天过期.
 */
class Bangumi(
    private val http: OkHttpClient,
    private val cacheDir: File,
    private val cacheTtlMs: Long = 7L * 24 * 3600 * 1000,
) : BangumiApi {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override fun searchAnime(keyword: String): List<Subject> {
        val body = buildJsonObject {
            put("keyword", keyword)
            putJsonObject("filter") { putJsonArray("type") { add(ANIME) } }
        }.toString()
        return json.decodeFromString(SearchPage.serializer(), cached("search:$body") {
            Request.Builder().url("$API/v0/search/subjects?limit=20")
                .post(body.toRequestBody("application/json".toMediaType()))
        }).data
    }

    override fun subject(id: Int): Subject =
        json.decodeFromString(Subject.serializer(), cached("subject:$id") { Request.Builder().url("$API/v0/subjects/$id") })

    private fun cached(key: String, request: () -> Request.Builder): String {
        val file = File(cacheDir, sha1(key) + ".json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < cacheTtlMs) return file.readText()
        val text = http.newCall(request().header("User-Agent", USER_AGENT).build()).execute().use { r ->
            check(r.isSuccessful) { "Bangumi ${r.code}" }
            r.body!!.string()
        }
        cacheDir.mkdirs()
        file.writeText(text)
        return text
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Serializable
    data class SearchPage(val data: List<Subject> = emptyList())

    @Serializable
    data class Subject(
        val id: Int,
        val name: String = "",
        @SerialName("name_cn") val nameCn: String = "",
        val date: String? = null,
        /** TV / OVA / 剧场版 / WEB … */
        val platform: String? = null,
        val eps: Int? = null,
        val summary: String? = null,
    ) {
        val displayName get() = nameCn.ifBlank { name }
        val year get() = date?.take(4)?.toIntOrNull()
    }

    companion object {
        private const val API = "https://api.bgm.tv"
        private const val ANIME = 2
        const val USER_AGENT = "arisa/0.1 (personal media library scraper)"

        /**
         * 给 TMDB 的动画作品找对应的 Bangumi 条目: 用原名 (日文) 搜, 名字对上且年份相差不超过 1 年的才算.
         * TMDB 常把多季合成一部, 这里取最早的那一季 (通常就是作品的主条目).
         */
        fun match(api: BangumiApi, original: String, year: Int?, movie: Boolean): Subject? {
            val target = normalize(original)
            if (target.isEmpty()) return null
            val scored = api.searchAnime(original).mapNotNull { s ->
                val sYear = s.year
                val names = listOf(s.name, s.nameCn).map(::normalize)
                val nameScore = when {
                    target in names -> 2.0
                    else -> return@mapNotNull null
                }
                val yearScore = when {
                    year == null || sYear == null -> 0.0
                    sYear == year -> 1.0
                    kotlin.math.abs(sYear - year) <= 1 -> 0.5
                    else -> return@mapNotNull null
                }
                val isMovie = s.platform == "剧场版"
                val platformScore = if (isMovie == movie) 0.5 else 0.0
                s to nameScore + yearScore + platformScore
            }
            return scored.maxWithOrNull(compareBy<Pair<Subject, Double>> { it.second }.thenByDescending { it.first.date ?: "9999" })?.first
        }

        private fun normalize(s: String) = s.lowercase().replace(Regex("[\\s\\p{Punct}·：:，,。！!？?～~'’\"“”「」『』【】]"), "")
    }
}

/** bangumi search 的输出, 给 AI 和网页用. */
fun bangumiSearchJson(api: BangumiApi, keyword: String) = buildJsonArray {
    api.searchAnime(keyword).take(10).forEach { s ->
        add(buildJsonObject {
            put("bangumi", s.id)
            put("name", s.name)
            if (s.nameCn.isNotBlank()) put("name_cn", s.nameCn)
            s.date?.let { put("date", it) }
            s.platform?.let { put("platform", it) }
            s.eps?.let { put("eps", it) }
        })
    }
}
