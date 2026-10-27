package arisa.scraper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 待确认: AI 拿不准、或者没处理的作品, 等用户在网页上确认.
 * 在这里的作品不会再交给 AI, 用户确认 (decide/skip) 或点「重新识别」后移出.
 */
@Serializable
data class ReviewEntry(
    val work: PendingWork,
    /** 为什么要人来确认. */
    val reason: String,
    /** AI 的猜测 (movie:ID / tv:ID), 可能为空. */
    val guess: String? = null,
    /** ai: AI 主动提问; unhandled: AI 跑完了也没处理; error: AI 运行出错 */
    val by: String = "ai",
    val at: Long = System.currentTimeMillis(),
)

@Serializable
private data class ReviewFile(val entries: List<ReviewEntry> = emptyList())

class ReviewStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }
    private val lock = File(file.path + ".lock")

    fun load(): List<ReviewEntry> = withFileLock(lock) { read() }

    fun keys(): Set<String> = load().mapTo(mutableSetOf()) { it.work.key }

    /** 同一作品只留最新的一条. */
    fun add(entry: ReviewEntry) = withFileLock(lock) {
        write(read().filter { it.work.key != entry.work.key } + entry)
    }

    fun remove(key: String): Boolean = withFileLock(lock) {
        val all = read()
        val left = all.filter { it.work.key != key }
        write(left)
        left.size != all.size
    }

    /** 已经不在待识别里的 (被映射了、被删了) 移出. */
    fun retain(pendingKeys: Set<String>) = withFileLock(lock) {
        val all = read()
        val left = all.filter { it.work.key in pendingKeys }
        if (left.size != all.size) write(left)
    }

    private fun read(): List<ReviewEntry> =
        if (file.exists()) json.decodeFromString(ReviewFile.serializer(), file.readText()).entries else emptyList()

    private fun write(entries: List<ReviewEntry>) {
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(ReviewFile.serializer(), ReviewFile(entries)))
        tmp.renameTo(file)
    }
}
