package arisa.scraper

import arisa.library.LibraryCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 识别结论: 网盘里某个文件或目录 → TMDB 作品.
 * key 是 fid, 改名、移动后仍然有效. 一个文件归属于离它最近的、有映射的祖先 (含自己),
 * 所以给剧集目录做了映射, 以后放进去的新集数不用再识别.
 */
@Serializable
data class MappingEntry(
    /** "movie:693134" / "tv:1396" / "skip" (不是正片, 不入库). */
    val target: String,
    /** 做决定时的路径, 只给人看. */
    val path: String,
    /** 强制季号, 目录名看不出第几季时用. 0 表示特别篇. */
    val season: Int? = null,
    val note: String? = null,
    /** 谁做的决定: skill / user / nfo / rules */
    val by: String = "skill",
    val at: Long = System.currentTimeMillis(),
) {
    val isSkip get() = target == SKIP

    /** (类型, TMDB ID), skip 时为 null. */
    fun tmdb(): Pair<String, Int>? {
        if (isSkip) return null
        val (kind, id) = target.split(':', limit = 2)
        return kind to id.toInt()
    }

    companion object {
        const val SKIP = "skip"
        private val TARGET = Regex("(movie|tv):\\d+|skip")

        fun validTarget(target: String) = TARGET.matches(target)
    }
}

@Serializable
data class Mapping(val entries: Map<String, MappingEntry> = emptyMap()) {
    /** 文件对应的映射: 先看文件自己, 再从近到远看所在目录. 返回 (映射所在的 fid, 映射). */
    fun resolve(file: ScannedFile): Pair<String, MappingEntry>? {
        entries[file.file.fid]?.let { return file.file.fid to it }
        for (dir in file.dirs.asReversed()) {
            entries[dir.fid]?.let { return dir.fid to it }
        }
        return null
    }

    fun with(fid: String, entry: MappingEntry) = copy(entries = entries + (fid to entry))
    fun without(fid: String) = copy(entries = entries - fid)
}

/**
 * 映射表存在本地 ~/.arisa/mapping.json, 同时备份到网盘 /.arisa/mapping.json,
 * 换机器或本地丢了能从网盘恢复.
 */
class MappingStore(private val file: File, private val drive: Drive?) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }

    fun load(): Mapping {
        if (file.exists()) return json.decodeFromString(Mapping.serializer(), file.readText())
        val remote = drive?.let(::remoteFile)?.let { drive.download(it.fid).decodeToString() } ?: return Mapping()
        file.parentFile.mkdirs()
        file.writeText(remote)
        return json.decodeFromString(Mapping.serializer(), remote)
    }

    /**
     * 读-改-写, 加文件锁. 常驻服务、它启动的 AI、网页上的操作可能同时改映射表.
     */
    fun update(change: (Mapping) -> Mapping): Mapping = withFileLock(File(file.path + ".lock")) {
        change(load()).also(::save)
    }

    fun save(mapping: Mapping) {
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Mapping.serializer(), mapping))
        tmp.renameTo(file)
    }

    /** 本地和网盘内容不同时上传 (先传新的再删旧的). */
    fun backup() {
        val drive = drive ?: return
        if (!file.exists()) return
        val bytes = file.readBytes()
        val dirFid = drive.list(drive.rootFid).firstOrNull { it.isDir && it.name == LibraryCodec.DIR }?.fid
            ?: drive.mkdir(drive.rootFid, LibraryCodec.DIR)
        val old = drive.list(dirFid).filter { it.name == NAME }
        if (old.size == 1 && runCatching { drive.download(old[0].fid).contentEquals(bytes) }.getOrDefault(false)) return
        // 夸克不覆盖同名文件: 先用临时名上传, 删掉旧的, 再改回正式名字
        val fid = drive.upload(dirFid, "mapping-${System.currentTimeMillis()}.json", bytes)
        drive.delete(old.map { it.fid })
        drive.rename(fid, NAME)
    }

    private fun remoteFile(drive: Drive) =
        drive.list(drive.rootFid).firstOrNull { it.isDir && it.name == LibraryCodec.DIR }
            ?.let { dir -> drive.list(dir.fid).firstOrNull { it.name == NAME } }

    companion object {
        const val NAME = "mapping.json"
    }
}

/** 跨进程的互斥 (同一进程内也互斥). */
fun <T> withFileLock(lock: File, block: () -> T): T = synchronized(lock.canonicalPath.intern()) {
    lock.parentFile.mkdirs()
    java.io.RandomAccessFile(lock, "rw").use { raf -> raf.channel.lock().use { block() } }
}
