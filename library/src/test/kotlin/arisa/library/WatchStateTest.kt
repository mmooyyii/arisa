package arisa.library

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchStateTest {
    private fun file(fid: String, duration: Int = 0) = MediaFile(fid = fid, name = "$fid.mkv", path = "/$fid.mkv", size = 1, duration = duration)
    private fun done(at: Long = 1) = PlaybackProgress(100 * 60_000L, 100 * 60_000L, at)
    private fun half(at: Long = 1) = PlaybackProgress(50 * 60_000L, 100 * 60_000L, at)

    private val movie = MediaItem(id = "movie:1", type = MediaType.MOVIE, title = "电影", files = listOf(file("m1"), file("m1-4k")))
    private val show = MediaItem(
        id = "tv:2", type = MediaType.TV, title = "剧集",
        seasons = listOf(
            Season(1, episodes = listOf(Episode(1, 1, files = listOf(file("e1"))), Episode(1, 2, files = listOf(file("e2"), file("e2b"))))),
            Season(2, episodes = listOf(Episode(2, 1, files = listOf(file("e3"))), Episode(2, 2))), // 最后一集没有文件, 不算
        ),
    )
    private val episodes = show.seasons.flatMap { it.episodes }.filter { it.files.isNotEmpty() }.map { it.files }

    @Test fun movie() {
        assertEquals(WatchState.Unwatched, WatchState.of(movie, emptyMap()))
        assertEquals(WatchState.Unwatched, WatchState.of(movie, mapOf("m1" to PlaybackProgress(30_000, 100 * 60_000L, 1))))
        assertEquals(WatchState.InProgress(0.5f), WatchState.of(movie, mapOf("m1" to half())))
        // 任何一个版本看完就算看完
        assertEquals(WatchState.Watched, WatchState.of(movie, mapOf("m1" to half(2), "m1-4k" to done(1))))
    }

    @Test fun show() {
        assertEquals(WatchState.Unwatched, WatchState.of(show, emptyMap()))
        assertEquals(WatchState.Remaining(3), WatchState.of(show, mapOf("e1" to half())))
        assertEquals(WatchState.Remaining(2), WatchState.of(show, mapOf("e1" to done())))
        assertEquals(WatchState.Remaining(1), WatchState.of(show, mapOf("e1" to done(), "e2b" to done())))
        assertEquals(WatchState.Watched, WatchState.of(show, mapOf("e1" to done(), "e2b" to done(), "e3" to done())))
    }

    @Test fun collection() {
        val all = mapOf("m1" to done(), "e1" to done(), "e2" to done(), "e3" to done())
        assertEquals(WatchState.Watched, WatchState.of(listOf(movie, show), all))
        assertEquals(WatchState.Unwatched, WatchState.of(listOf(movie, show), all - "e3"))
        assertEquals(WatchState.Unwatched, WatchState.of(emptyList(), all))
    }

    @Test fun markWatched() {
        val before = mapOf("e2b" to done(5), "e3" to half(6))
        val marks = WatchMark.mark(episodes, watched = true, progress = before, now = 1000)
        // 第 2 集已经有看完的版本, 用它, 不另外标第一个版本
        assertEquals(setOf("e1", "e2b", "e3"), marks.keys)
        val after = before + marks
        assertEquals(WatchState.Watched, WatchState.of(show, after))
        // 最后一集最新: 「继续观看」找到它, 后面没有下一集
        assertEquals("e3", after.maxBy { it.value.updatedAt }.key)
        // 知道时长的用原来的时长
        assertEquals(100 * 60_000L, marks.getValue("e3").durationMs)
    }

    @Test fun markWatchedUsesFileDuration() {
        val marks = WatchMark.mark(listOf(listOf(file("x", duration = 90))), watched = true, progress = emptyMap(), now = 1000)
        assertEquals(PlaybackProgress(90_000, 90_000, 1000), marks["x"])
        val unknown = WatchMark.mark(listOf(listOf(file("y"))), watched = true, progress = emptyMap(), now = 1000)
        assertEquals(true, unknown.getValue("y").finished)
    }

    @Test fun markUnwatched() {
        val before = mapOf("e1" to done(5), "e2" to half(6), "e2b" to done(7), "e3" to done(8))
        val marks = WatchMark.mark(episodes, watched = false, progress = before, now = 1000)
        assertEquals(setOf("e1", "e2", "e2b", "e3"), marks.keys)
        val after = before + marks
        assertEquals(WatchState.Unwatched, WatchState.of(show, after))
        // 条目还在 (不然同步时会从别的设备合并回来), 而且比原来的新
        assertEquals(true, marks.values.all { it.positionMs == 0L && it.updatedAt >= 1000 })
        // 第一集最新: 详情页的播放按钮回到第一集
        assertEquals("e1", after.maxBy { it.value.updatedAt }.key)
    }
}
