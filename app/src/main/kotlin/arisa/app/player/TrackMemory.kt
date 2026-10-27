package arisa.app.player

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 记住的一条轨道: 按语言和轨道名找回来 (换一集后轨道顺序可能不同). */
@Serializable
data class TrackChoice(val language: String? = null, val label: String? = null)

/** 一部作品上次选的音轨和字幕. text == null 且 textOff 为 true 表示关了字幕. */
@Serializable
data class TrackPrefs(
    val audio: TrackChoice? = null,
    val text: TrackChoice? = null,
    val textOff: Boolean = false,
)

/** 每部作品各记各的, key 是作品 id (未识别的视频用 fid). 本地存. */
class TrackMemory(context: Context) {
    private val prefs = context.getSharedPreferences("tracks", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), TrackPrefs.serializer())

    private fun all(): Map<String, TrackPrefs> =
        prefs.getString("all", null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()

    fun get(key: String): TrackPrefs? = all()[key]

    fun update(key: String, change: (TrackPrefs) -> TrackPrefs) {
        val all = all()
        prefs.edit().putString("all", json.encodeToString(serializer, all + (key to change(all[key] ?: TrackPrefs())))).apply()
    }
}
