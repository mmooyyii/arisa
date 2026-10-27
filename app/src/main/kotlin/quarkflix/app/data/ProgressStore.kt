package quarkflix.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import quarkflix.library.Episode
import quarkflix.library.Library
import quarkflix.library.MediaItem
import quarkflix.library.MediaType
import quarkflix.library.PlaybackProgress

/** 观看进度, key = 文件 fid. 本地存. */
class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), PlaybackProgress.serializer())

    private val _all = MutableStateFlow(
        prefs.getString("all", null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()
    )
    val all: StateFlow<Map<String, PlaybackProgress>> = _all

    fun get(fid: String) = _all.value[fid]

    fun save(fid: String, positionMs: Long, durationMs: Long) {
        _all.value = _all.value + (fid to PlaybackProgress(positionMs, durationMs, System.currentTimeMillis()))
        prefs.edit().putString("all", json.encodeToString(serializer, _all.value)).apply()
    }

    /** 继续观看: 看了一半的, 以及看完一集后的下一集. 按最近观看排序. */
    fun continueWatching(library: Library, progress: Map<String, PlaybackProgress>): List<ContinueEntry> {
        val entries = mutableListOf<ContinueEntry>()
        for (item in library.items) {
            when (item.type) {
                MediaType.MOVIE -> {
                    val (file, p) = item.files.mapNotNull { f -> progress[f.fid]?.let { f to it } }.maxByOrNull { it.second.updatedAt } ?: continue
                    if (!p.finished && p.positionMs > 60_000) entries += ContinueEntry(item, null, file.fid, p, p.updatedAt)
                }
                MediaType.TV -> {
                    val episodes = item.seasons.flatMap { it.episodes }
                    val watched = episodes.mapIndexedNotNull { i, e ->
                        e.files.mapNotNull { f -> progress[f.fid] }.maxByOrNull { it.updatedAt }?.let { Triple(i, e, it) }
                    }.maxByOrNull { it.third.updatedAt } ?: continue
                    val (index, episode, p) = watched
                    if (!p.finished) {
                        if (p.positionMs > 60_000) entries += ContinueEntry(item, episode, episode.files.first().fid, p, p.updatedAt)
                    } else {
                        val next = episodes.getOrNull(index + 1) ?: continue
                        entries += ContinueEntry(item, next, next.files.first().fid, null, p.updatedAt)
                    }
                }
            }
        }
        return entries.sortedByDescending { it.updatedAt }
    }

    data class ContinueEntry(
        val item: MediaItem,
        val episode: Episode?,
        val fid: String,
        val progress: PlaybackProgress?,
        val updatedAt: Long,
    )
}
