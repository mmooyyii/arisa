package arisa.library

/**
 * 观看进度 (文件 fid → [PlaybackProgress]) 的合并和清理规则.
 *
 * 清理是一条每次同步都重新套用的规则, 不是「删一次」: 旧版 App 不清理, 它写在网盘上的文件里还留着这些条目,
 * 新版每次合并后再按同一条规则筛掉, 所以不会被旧版带回来.
 */
object ProgressSync {
    /** 片库里已经没有的文件, 最后一次观看过了这么久才清掉它的进度. */
    const val KEEP_MISSING_MS = 30L * 24 * 3600 * 1000

    /** 同一个文件取更新时间新的. */
    fun merge(a: Map<String, PlaybackProgress>, b: Map<String, PlaybackProgress>): Map<String, PlaybackProgress> =
        (a.keys + b.keys).associateWith { fid -> listOfNotNull(a[fid], b[fid]).maxBy { it.updatedAt } }

    /**
     * 去掉片库里已经没有的文件 (网盘上删掉了) 的进度, 这样进度条数不会超过片库的文件数太多.
     *
     * 最近 [KEEP_MISSING_MS] 内看过的先留着: 这台电视缓存的片库可能比别的电视旧, 还没有刚加的片子;
     * 目录临时从片库配置里拿掉又加回来时进度也还在.
     * 片库是空的 (索引坏了、还没刮削) 时什么都不清.
     */
    fun prune(progress: Map<String, PlaybackProgress>, library: Library, now: Long): Map<String, PlaybackProgress> {
        val fids = fids(library)
        if (fids.isEmpty()) return progress
        return progress.filter { (fid, p) -> fid in fids || now - p.updatedAt < KEEP_MISSING_MS }
    }

    /** 片库里所有视频文件的 fid, 包括未识别的. */
    fun fids(library: Library): Set<String> = buildSet {
        for (item in library.items) {
            item.files.mapTo(this) { it.fid }
            for (s in item.seasons) for (e in s.episodes) e.files.mapTo(this) { it.fid }
        }
        library.unmatched.mapTo(this) { it.fid }
    }
}

/** 一部作品看到哪了, 海报上的标记用. */
sealed interface WatchState {
    /** 没看过, 或者只看了开头不到一分钟. */
    data object Unwatched : WatchState
    /** 电影看了一半. [fraction] 0~1. */
    data class InProgress(val fraction: Float) : WatchState
    /** 剧集开始看了, 还有 [remaining] 集没看完. */
    data class Remaining(val remaining: Int) : WatchState
    data object Watched : WatchState

    companion object {
        /** 看了不到这么久的不算开始看 (和「继续观看」的规则一样). */
        const val STARTED_MS = 60_000L

        fun of(item: MediaItem, progress: Map<String, PlaybackProgress>): WatchState {
            if (item.type == MediaType.MOVIE) {
                val all = item.files.mapNotNull { progress[it.fid] }
                if (all.any { it.finished }) return Watched
                val p = all.maxByOrNull { it.updatedAt } ?: return Unwatched
                return if (p.positionMs > STARTED_MS) InProgress(p.positionMs.toFloat() / p.durationMs.coerceAtLeast(1)) else Unwatched
            }
            var total = 0
            var watched = 0
            var started = false
            for (s in item.seasons) for (e in s.episodes) {
                if (e.files.isEmpty()) continue
                total++
                val all = e.files.mapNotNull { progress[it.fid] }
                if (all.any { it.finished }) watched++ else if (all.any { it.positionMs > STARTED_MS }) started = true
            }
            return when {
                total == 0 -> Unwatched
                watched == total -> Watched
                watched > 0 || started -> Remaining(total - watched)
                else -> Unwatched
            }
        }

        /** 合集: 里面的作品全看完才算看完, 否则不标. */
        fun of(items: List<MediaItem>, progress: Map<String, PlaybackProgress>): WatchState =
            if (items.isNotEmpty() && items.all { of(it, progress) == Watched }) Watched else Unwatched
    }
}

/**
 * 手动标记已看 / 未看时要写的进度. 返回 fid → 进度, 调用方存进去.
 *
 * 标成未看不是把进度删掉 (删掉的话下次和网盘同步又会从别的设备合并回来), 而是写一条位置为 0 的新进度盖过去.
 * 一次标很多集时更新时间按集数错开一毫秒: 「继续观看」和详情页都是找最后看的那一集,
 * 标成已看时最后一集最新 (后面没有下一集, 不会再推荐), 标成未看时第一集最新 (播放按钮回到第一集).
 */
object WatchMark {
    /** [files]: 每一集 (电影就是一项) 的各个版本, 按集数顺序. */
    fun mark(
        files: List<List<MediaFile>>,
        watched: Boolean,
        progress: Map<String, PlaybackProgress>,
        now: Long,
    ): Map<String, PlaybackProgress> = buildMap {
        files.forEachIndexed { i, versions ->
            val at = now + if (watched) i else files.size - 1 - i
            if (watched) {
                // 已经有看完的版本就只更新它的时间 (所有集的时间要按顺序排好); 没有的话标第一个版本
                val f = versions.firstOrNull { progress[it.fid]?.finished == true } ?: versions.firstOrNull() ?: return@forEachIndexed
                val duration = progress[f.fid]?.durationMs?.takeIf { it > 0 } ?: (f.duration * 1000L).coerceAtLeast(1)
                put(f.fid, PlaybackProgress(duration, duration, at))
            } else {
                for (f in versions) {
                    val p = progress[f.fid] ?: continue
                    put(f.fid, PlaybackProgress(0, p.durationMs, at))
                }
            }
        }
    }
}
