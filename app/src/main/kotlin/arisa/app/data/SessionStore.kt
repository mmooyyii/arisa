package arisa.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import arisa.quark.QuarkTvClient

/** 夸克 TV 端登录态, 存在 SharedPreferences. */
class SessionStore(context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<QuarkTvClient.TvSession> = _state

    init {
        // 设备 ID 要固定下来, 登录绑定在它上面
        if (!prefs.contains("tv")) save(_state.value)
    }

    /** 演示模式: 不登录, 用内置的片库数据看界面. */
    var demo: Boolean
        get() = prefs.getBoolean("demo", false)
        set(value) = prefs.edit().putBoolean("demo", value).apply()

    val isLoggedIn get() = _state.value.refreshToken != null

    var client: QuarkTvClient = newClient()
        private set

    private fun newClient() = QuarkTvClient(_state.value, onSessionChange = ::save, http = http)

    private fun load(): QuarkTvClient.TvSession =
        prefs.getString("tv", null)?.let { runCatching { json.decodeFromString(QuarkTvClient.TvSession.serializer(), it) }.getOrNull() }
            ?: QuarkTvClient.TvSession.newDevice()

    private fun save(s: QuarkTvClient.TvSession) {
        prefs.edit().putString("tv", json.encodeToString(QuarkTvClient.TvSession.serializer(), s)).apply()
        _state.value = s
    }

    fun logout() {
        val fresh = QuarkTvClient.TvSession(deviceId = _state.value.deviceId)
        save(fresh)
        client = newClient()
        demo = false
    }
}
