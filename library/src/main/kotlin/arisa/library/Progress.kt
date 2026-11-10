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
