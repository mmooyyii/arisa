package arisa.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import arisa.quark.QuarkWebClient

/**
 * 夸克登录态: 网页版扫码拿到的 Cookie, 存在 SharedPreferences.
 * 用网页接口而不是 TV 接口, 是因为 TV 端登录的设备数有上限.
 */
class SessionStore(context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private val _cookie = MutableStateFlow(prefs.getString("cookie", null))
    /** 没登录时为 null. */
    val cookie: StateFlow<String?> = _cookie

    /** 演示模式: 不登录, 用内置的片库数据看界面. */
    var demo: Boolean
        get() = prefs.getBoolean("demo", false)
        set(value) = prefs.edit().putBoolean("demo", value).apply()

    val isLoggedIn get() = _cookie.value != null

    var client: QuarkWebClient = newClient()
        private set

    private fun newClient() = QuarkWebClient(_cookie.value.orEmpty(), onCookieChange = ::save, http = http)

    private fun save(cookie: String?) {
        prefs.edit().putString("cookie", cookie).apply()
        _cookie.value = cookie
    }

    /** 扫码拿到 Cookie 后调用. 先请求一次接口, 让服务端下发 __puus (没有它下载会被拒). */
    fun login(cookie: String) {
        val c = QuarkWebClient(cookie, onCookieChange = {}, http = http)
        c.member()
        client = QuarkWebClient(c.currentCookie, onCookieChange = ::save, http = http)
        save(c.currentCookie)
    }

    fun logout() {
        save(null)
        client = newClient()
        demo = false
    }
}
