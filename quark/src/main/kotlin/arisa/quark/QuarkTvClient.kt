package arisa.quark

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 夸克 TV 端开放接口: 扫码登录、列目录、取直链/转码流, 不能上传.
 * 参考 OpenList drivers/quark_uc_tv.
 *
 * 注意: code/refresh_token 换 access_token 走的是 TV 客户端厂商的服务器 (api.extscreen.com).
 */
class QuarkTvClient(
    private var session: TvSession,
    /** 登录态变化 (拿到/刷新 token) 时回调, 调用方负责持久化. */
    private val onSessionChange: (TvSession) -> Unit = {},
    private val http: OkHttpClient = defaultHttpClient,
) {
    @Serializable
    data class TvSession(
        val deviceId: String,
        val refreshToken: String? = null,
        val accessToken: String? = null,
    ) {
        companion object {
            fun newDevice() = TvSession(deviceId = hex("MD5", System.nanoTime().toString()))
        }
    }

    /** 扫码登录: [qrJpegBase64] 是二维码图片, 扫码后用 [queryToken] 调 [pollLogin]. */
    data class QrLogin(val qrJpegBase64: String, val queryToken: String)

    val currentSession: TvSession get() = session
    val isLoggedIn: Boolean get() = session.refreshToken != null

    fun startQrLogin(): QrLogin {
        val resp = request(
            "/oauth/authorize", "GET", mapOf(
                "auth_type" to "code",
                "client_id" to CLIENT_ID,
                "scope" to "netdisk",
                "qrcode" to "1",
                "qr_width" to "460",
                "qr_height" to "460",
            ), retryOnTokenError = false
        )
        return QrLogin(resp.stringField("qr_data"), resp.stringField("query_token"))
    }

    /** 还没扫码/没确认时返回 false, 登录成功返回 true. */
    fun pollLogin(queryToken: String): Boolean {
        val code = try {
            request(
                "/oauth/code", "GET", mapOf(
                    "client_id" to CLIENT_ID,
                    "scope" to "netdisk",
                    "query_token" to queryToken,
                ), retryOnTokenError = false
            ).stringField("code")
        } catch (_: QuarkException) {
            return false
        }
        if (code.isEmpty()) return false
        exchangeToken(code = code)
        return true
    }

    fun userInfo(): JsonObject = request("/user", "GET", mapOf("method" to "user_info"))

    fun list(parentFid: String = ROOT): List<QuarkFile> {
        val files = mutableListOf<QuarkFile>()
        val pageSize = 100
        var page = 0
        while (true) {
            val data = request(
                "/file", "GET", mapOf(
                    "method" to "list",
                    "parent_fid" to parentFid,
                    "order_by" to "1",
                    "desc" to "0",
                    "category" to "",
                    "source" to "",
                    "ex_source" to "",
                    "list_all" to "0",
                    "page_size" to pageSize.toString(),
                    "page_index" to page.toString(),
                )
            )["data"]?.jsonObject ?: break
            val resp = json.decodeFromJsonElement(ListData.serializer(), data)
            resp.files.mapTo(files) {
                QuarkFile(it.fid, it.filename, it.isdir == 1, it.size, it.category, it.updatedAt)
            }
            page++
            if (resp.files.isEmpty() || page.toLong() * pageSize >= resp.totalCount) break
        }
        return files
    }

    /** 原文件直链. */
    fun downloadLink(fid: String): QuarkLink {
        val data = request(
            "/file", "GET", mapOf(
                "method" to "download",
                "group_by" to "source",
                "fid" to fid,
                "resolution" to "low,normal,high,super,2k,4k",
                "support" to "dolby_vision",
            )
        )["data"]?.jsonObject ?: throw QuarkException("download: no data")
        val url = data.stringField("download_url").ifEmpty { throw QuarkException("download: empty url") }
        return QuarkLink(url, mapOf("User-Agent" to USER_AGENT), data.stringField("size").toLongOrNull())
    }

    /** 夸克转码后的各清晰度流. */
    fun streamingLinks(fid: String): List<StreamingVideo> {
        val data = request(
            "/file", "GET", mapOf(
                "method" to "streaming",
                "group_by" to "source",
                "fid" to fid,
                "resolution" to "low,normal,high,super,2k,4k",
                "support" to "dolby_vision",
            )
        )["data"]?.jsonObject ?: return emptyList()
        return json.decodeFromJsonElement(StreamingData.serializer(), data).videoInfo.filter { it.url.isNotEmpty() }
    }

    private fun request(
        path: String,
        method: String,
        query: Map<String, String>,
        retryOnTokenError: Boolean = true,
    ): JsonObject {
        if (retryOnTokenError && session.accessToken == null) refreshAccessToken()

        val (tm, token, reqId) = sign(method, path)
        val url = (API + path).toHttpUrl().newBuilder().apply {
            addQueryParameter("req_id", reqId)
            addQueryParameter("access_token", session.accessToken ?: "")
            deviceParams().forEach { (k, v) -> addQueryParameter(k, v) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", USER_AGENT)
            .header("x-pan-tm", tm)
            .header("x-pan-token", token)
            .header("x-pan-client-id", CLIENT_ID)
            .method(method, null)
            .build()
        val body = http.newCall(req).execute().use { it.body?.string() ?: "" }
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw QuarkException("$path: bad response: ${body.take(200)}") }

        val status = obj.intField("status")
        val errno = obj.intField("errno")
        val info = obj.stringField("error_info")
        if (retryOnTokenError && isTokenError(status, errno, info)) {
            refreshAccessToken()
            return request(path, method, query, retryOnTokenError = false)
        }
        if (status >= 400 || errno != 0) throw QuarkException("$path: status=$status errno=$errno $info")
        return obj
    }

    private fun isTokenError(status: Int, errno: Int, info: String): Boolean {
        val lower = info.trim().lowercase()
        return (status == -1 && (errno == 10001 || errno == 11001)) ||
            "access token" in lower || "access_token" in lower || "token无效" in lower || "token 无效" in lower
    }

    private fun refreshAccessToken() {
        val refresh = session.refreshToken ?: throw QuarkException("not logged in")
        exchangeToken(refreshToken = refresh)
    }

    private fun exchangeToken(code: String? = null, refreshToken: String? = null) {
        val (_, _, reqId) = sign("POST", "/token")
        val body = buildJsonObject {
            put("req_id", reqId)
            deviceParams().forEach { (k, v) -> put(k, v) }
            if (code != null) put("code", code)
            if (refreshToken != null) put("refresh_token", refreshToken)
        }
        val req = Request.Builder().url("$CODE_API/token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { it.body?.string() ?: "" }
        val resp = runCatching { json.decodeFromString(TokenResp.serializer(), text) }
            .getOrElse { throw QuarkException("token: bad response: ${text.take(200)}") }
        if (resp.code != 200 || resp.data?.refreshToken.isNullOrEmpty()) {
            throw QuarkException("token: ${resp.code} ${resp.message}")
        }
        session = session.copy(refreshToken = resp.data!!.refreshToken, accessToken = resp.data.accessToken)
        onSessionChange(session)
    }

    private fun deviceParams() = mapOf(
        "app_ver" to APP_VER,
        "device_id" to session.deviceId,
        "device_brand" to "Xiaomi",
        "platform" to "tv",
        "device_name" to DEVICE_MODEL,
        "device_model" to DEVICE_MODEL,
        "build_device" to DEVICE_MODEL,
        "build_product" to DEVICE_MODEL,
        "device_gpu" to "Adreno (TM) 550",
        "activity_rect" to "{}",
        "channel" to "GENERAL",
    )

    /** 返回 (毫秒时间戳, x-pan-token, req_id). */
    private fun sign(method: String, path: String): Triple<String, String, String> {
        val tm = System.currentTimeMillis().toString()
        val reqId = hex("MD5", session.deviceId + tm)
        val token = hex("SHA-256", "$method&$path&$tm&$SIGN_KEY")
        return Triple(tm, token, reqId)
    }

    @Serializable
    private data class ListData(
        @kotlinx.serialization.SerialName("total_count") val totalCount: Long = 0,
        val files: List<TvFile> = emptyList(),
    )

    @Serializable
    private data class TvFile(
        val fid: String,
        val filename: String = "",
        val category: Int = 0,
        val size: Long = 0,
        val isdir: Int = 0,
        @kotlinx.serialization.SerialName("updated_at") val updatedAt: Long = 0,
    )

    @Serializable
    private data class StreamingData(
        @kotlinx.serialization.SerialName("video_info") val videoInfo: List<StreamingVideo> = emptyList(),
    )

    @Serializable
    data class StreamingVideo(
        val resolution: String = "",
        val url: String = "",
        val size: Long = 0,
        val width: Int = 0,
        val height: Int = 0,
        val duration: Int = 0,
        val format: String = "",
    )

    @Serializable
    private data class TokenResp(
        val code: Int = 0,
        val message: String = "",
        val data: TokenData? = null,
    )

    @Serializable
    private data class TokenData(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String = "",
        @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String = "",
    )

    companion object {
        const val ROOT = "0"
        private const val API = "https://open-api-drive.quark.cn"
        private const val CODE_API = "http://api.extscreen.com/quarkdrive"
        private const val CLIENT_ID = "d3194e61504e493eb6222857bccfed94"
        private const val SIGN_KEY = "kw2dvtd7p4t3pjl2d9ed9yc8yej8kw2d"
        private const val APP_VER = "1.8.2.2"
        private const val DEVICE_MODEL = "M2004J7AC"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; U; Android 13; zh-cn; M2004J7AC Build/UKQ1.231108.001) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1"
    }
}
