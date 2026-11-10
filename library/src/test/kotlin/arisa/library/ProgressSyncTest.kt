package arisa.library

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressSyncTest {
    private val day = 24 * 3600 * 1000L
    private val now = 1000 * day

    private fun file(fid: String) = MediaFile(fid = fid, name = "$fid.mkv", path = "/$fid.mkv", size = 1)
    private fun p(updatedAt: Long, position: Long = 1) = PlaybackProgress(position, 100, updatedAt)

    private val lib = Library(
        generatedAt = 0,
        items = listOf(
            MediaItem(id = "movie:1", type = MediaType.MOVIE, title = "电影", files = listOf(file("m1"))),
            MediaItem(
                id = "tv:2", type = MediaType.TV, title = "剧集",
                seasons = listOf(Season(1, episodes = listOf(Episode(1, 1, files = listOf(file("e1"), file("e1b")))))),
            ),
        ),
        unmatched = listOf(file("u1")),
    )

    @Test fun mergeTakesNewer() {
        val a = mapOf("x" to p(1, position = 10), "y" to p(5, position = 20))
        val b = mapOf("x" to p(2, position = 30), "z" to p(3, position = 40))
        assertEquals(mapOf("x" to p(2, position = 30), "y" to p(5, position = 20), "z" to p(3, position = 40)), ProgressSync.merge(a, b))
    }

    @Test fun fidsCoverMoviesEpisodesAndUnmatched() {
        assertEquals(setOf("m1", "e1", "e1b", "u1"), ProgressSync.fids(lib))
    }

    @Test fun pruneDropsOldProgressOfMissingFiles() {
        val progress = mapOf(
            "m1" to p(0),                 // 在片库里, 多久以前看的都留着
            "e1b" to p(0),
            "u1" to p(0),
            "gone-old" to p(now - 31 * day),    // 文件没了, 超过 30 天
            "gone-recent" to p(now - 29 * day), // 文件没了, 但最近看过 (可能是这台电视的片库旧)
        )
        assertEquals(setOf("m1", "e1b", "u1", "gone-recent"), ProgressSync.prune(progress, lib, now).keys)
    }

    @Test fun pruneIsIdempotent() {
        val progress = mapOf("m1" to p(0), "gone-old" to p(0))
        val once = ProgressSync.prune(progress, lib, now)
        assertEquals(once, ProgressSync.prune(once, lib, now))
        // 旧版 App 把清掉的条目又带回来, 合并后再清一次结果一样
        assertEquals(once, ProgressSync.prune(ProgressSync.merge(once, progress), lib, now))
    }

    @Test fun pruneKeepsEverythingWhenLibraryIsEmpty() {
        val progress = mapOf("gone-old" to p(0))
        assertEquals(progress, ProgressSync.prune(progress, Library(generatedAt = 0), now))
    }

    @Test fun pruneKeepsEntriesFromTheFutureWhenClockIsWrong() {
        // 电视时间没校准 (now 很小) 时不乱删
        val progress = mapOf("gone" to p(now))
        assertEquals(progress, ProgressSync.prune(progress, lib, now = 0))
    }
}
