package arisa.quark

import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.UUID

/**
 * 夸克网页版 (pan.quark.cn) 扫码登录, 拿到和浏览器一样的 Cookie, 给 [QuarkWebClient] 用.
 * 网页登录不占 TV 端的设备名额 (TV 端登录多了会报「设备数超限」).
 */
class QuarkWebLogin(private val http: OkHttpClient = defaultHttpClient) {
    /** [qrContent] 是二维码里的链接, 要自己画成图. */
    data class QrLogin(val qrContent: String, val token: String)

    private val cookies = linkedMapOf<String, String>()

    fun start(): QrLogin {
        val obj = get(
            "$UOP/cas/ajax/getTokenForQrcodeLogin",
            mapOf("client_id" to CLIENT_ID, "v" to "1.2", "request_id" to UUID.randomUUID().toString()),
        )
        val token = obj["data"]!!.jsonObject["members"]!!.jsonObject.stringField("token")
        val content = "https://su.quark.cn/4_eMHBJ".toHttpUrl().newBuilder()
            .addQueryParameter("token", token)
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("ssb", "weblogin")
            .addQueryParameter("uc_param_str", "")
            .addQueryParameter("uc_biz_str", "S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0")
            .build().toString()
        return QrLogin(content, token)
    }

    /** 还没扫码/没确认时返回 null, 确认后返回 Cookie. */
    fun poll(token: String): String? {
        val obj = get(
            "$UOP/cas/ajax/getServiceTicketByQrcodeToken",
            mapOf("client_id" to CLIENT_ID, "v" to "1.2", "token" to token, "request_id" to UUID.randomUUID().toString()),
        )
        if (obj.intField("status") != 2000000) return null
        val ticket = obj["data"]!!.jsonObject["members"]!!.jsonObject.stringField("service_ticket")
        get("$REFERER/account/info", mapOf("st" to ticket, "lw" to "scan"))
        if ("__pus" !in cookies) throw QuarkException("扫码登录没有拿到 Cookie")
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun get(url: String, query: Map<String, String>): kotlinx.serialization.json.JsonObject {
        val req = Request.Builder()
            .url(url.toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build())
            .header("User-Agent", QuarkWebClient.USER_AGENT)
            .header("Referer", "$REFERER/")
            .apply { if (cookies.isNotEmpty()) header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }) }
            .build()
        val text = http.newCall(req).execute().use { resp ->
            collect(resp)
            resp.body?.string().orEmpty()
        }
        return runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw QuarkException("$url: bad response: ${text.take(200)}") }
    }

    private fun collect(resp: Response) {
        for (h in resp.headers("Set-Cookie")) {
            val pair = h.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty()) cookies[name] = pair.substringAfter('=', "")
        }
    }

    private companion object {
        const val UOP = "https://uop.quark.cn"
        const val REFERER = "https://pan.quark.cn"
        const val CLIENT_ID = "532"
    }
}
