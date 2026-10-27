package arisa.scraper

import arisa.quark.QuarkFile
import arisa.quark.QuarkWebClient
import java.io.File

/** 刮削器用到的网盘操作, 抽出来方便用本地目录测试. */
interface Drive {
    val rootFid: String
    fun list(fid: String): List<QuarkFile>
    fun download(fid: String): ByteArray
    fun upload(parentFid: String, name: String, bytes: ByteArray): String
    fun delete(fids: List<String>)
    fun mkdir(parentFid: String, name: String): String
    fun rename(fid: String, newName: String)

    /** 网盘最近的变化 (转存、上传…), 不支持的实现返回空. */
    fun recent(): List<arisa.quark.RecentRecord> = emptyList()

    /** 最近删除的 (回收站), 不支持的实现返回空. */
    fun recycled(): List<arisa.quark.RecycledFile> = emptyList()

    /** 夸克为视频生成的缩略图. */
    fun thumbnail(file: QuarkFile): ByteArray = error("不支持缩略图")

    fun resolve(path: String): QuarkFile? {
        var current = QuarkFile(rootFid, "", true, 0, 0, 0)
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            current = list(current.fid).firstOrNull { it.name == part } ?: return null
        }
        return current
    }
}

/** 按每秒次数限速, 避免触发夸克风控. */
class RateLimiter(perSecond: Double) {
    private val intervalMs = (1000 / perSecond).toLong()
    private var last = 0L

    @Synchronized
    fun acquire() {
        val wait = last + intervalMs - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        last = System.currentTimeMillis()
    }
}

class QuarkDrive(private val client: QuarkWebClient, rate: RateLimit) : Drive {
    private val listLimiter = RateLimiter(rate.listPerSec)
    private val writeLimiter = RateLimiter(rate.uploadPerSec)
    override val rootFid = QuarkWebClient.ROOT

    override fun list(fid: String) = retry { listLimiter.acquire(); client.list(fid) }
    override fun download(fid: String) = retry { listLimiter.acquire(); client.download(fid) }
    override fun upload(parentFid: String, name: String, bytes: ByteArray) =
        retry { writeLimiter.acquire(); client.upload(parentFid, name, bytes) }
    override fun delete(fids: List<String>) { if (fids.isNotEmpty()) retry { writeLimiter.acquire(); client.delete(fids) } }
    override fun mkdir(parentFid: String, name: String) = retry { writeLimiter.acquire(); client.mkdir(parentFid, name) }
    override fun rename(fid: String, newName: String) = retry { writeLimiter.acquire(); client.rename(fid, newName) }
    override fun recent() = retry { listLimiter.acquire(); client.recent() }
    override fun recycled() = retry { listLimiter.acquire(); client.recycled() }
    override fun thumbnail(file: QuarkFile) =
        retry { client.thumbnail(file.thumbnail ?: throw IllegalStateException("夸克没有为这个文件生成缩略图")) }

    private fun <T> retry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: arisa.quark.QuarkAuthException) {
                throw LoginExpiredException("夸克登录失效, 运行 arisa-scraper login 重新登录 (${e.message})")
            } catch (e: Exception) {
                if (++attempt >= 3) throw e
                Thread.sleep(2000L * attempt)
            }
        }
    }
}

/** 用本地目录模拟网盘, fid 就是相对路径. 测试和离线调试用. */
class LocalDrive(private val root: File) : Drive {
    override val rootFid = ""

    private fun file(fid: String) = if (fid.isEmpty()) root else File(root, fid)

    override fun list(fid: String): List<QuarkFile> =
        (file(fid).listFiles() ?: emptyArray()).sortedBy { it.name }.map {
            val rel = it.relativeTo(root).path
            QuarkFile(rel, it.name, it.isDirectory, if (it.isFile) it.length() else 0, if (it.isFile) 1 else 0, it.lastModified())
        }

    override fun download(fid: String) = file(fid).readBytes()

    override fun upload(parentFid: String, name: String, bytes: ByteArray): String {
        val f = File(file(parentFid), name)
        f.writeBytes(bytes)
        return f.relativeTo(root).path
    }

    override fun delete(fids: List<String>) = fids.forEach { file(it).deleteRecursively() }

    override fun mkdir(parentFid: String, name: String): String {
        val f = File(file(parentFid), name)
        f.mkdirs()
        return f.relativeTo(root).path
    }

    override fun rename(fid: String, newName: String) {
        val f = file(fid)
        check(f.renameTo(File(f.parentFile, newName))) { "rename failed: $fid" }
    }
}

/**
 * 目录列表缓存在本地, 只有被 [invalidate] 的目录才重新列, 常驻服务靠它做增量扫描.
 * 失效的目录保留旧列表、只标记过期, 这样 [isListed] 仍然知道它在片库里.
 * 写操作会让相关目录失效. 移动、删除不一定能从「最近」里看出来, 所以要定期 [invalidateAll].
 */
class CachingDrive(private val inner: Drive, private val file: java.io.File) : Drive {
    @kotlinx.serialization.Serializable
    private data class Cache(val listings: Map<String, List<QuarkFile>> = emptyMap(), val stale: Set<String> = emptySet())

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val listings: MutableMap<String, List<QuarkFile>>
    private val stale: MutableSet<String>

    init {
        val cache = runCatching { json.decodeFromString(Cache.serializer(), file.readText()) }.getOrDefault(Cache())
        listings = cache.listings.toMutableMap()
        stale = cache.stale.toMutableSet()
    }

    override val rootFid get() = inner.rootFid

    override fun list(fid: String): List<QuarkFile> {
        synchronized(this) { if (fid !in stale) listings[fid]?.let { return it } }
        val list = inner.list(fid)
        synchronized(this) {
            listings[fid] = list
            stale -= fid
        }
        return list
    }

    /** 缓存里的文件信息 (含缩略图地址). */
    @Synchronized
    fun cached(fid: String): QuarkFile? = listings.values.firstNotNullOfOrNull { l -> l.firstOrNull { it.fid == fid } }

    /** 重新列一次文件所在目录, 拿到最新的信息 (缩略图地址会过期). */
    fun fresh(fid: String): QuarkFile? {
        val parent = synchronized(this) { listings.entries.firstOrNull { (_, l) -> l.any { it.fid == fid } }?.key } ?: return null
        synchronized(this) { stale += parent }
        return list(parent).firstOrNull { it.fid == fid }
    }

    /** 列过的目录 (含已过期的). */
    @Synchronized
    fun isListed(fid: String) = fid in listings

    @Synchronized
    fun invalidate(fid: String) {
        if (fid in listings) stale += fid
    }

    /** 目录被删了: 丢掉它的列表. */
    @Synchronized
    fun forget(fid: String) {
        listings.remove(fid)
        stale.remove(fid)
    }

    @Synchronized
    fun invalidateAll() {
        listings.clear()
        stale.clear()
    }

    @Synchronized
    fun save() {
        file.parentFile.mkdirs()
        val tmp = java.io.File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Cache.serializer(), Cache(listings, stale)))
        tmp.renameTo(file)
    }

    override fun download(fid: String) = inner.download(fid)
    override fun recent() = inner.recent()
    override fun recycled() = inner.recycled()
    override fun thumbnail(file: QuarkFile) = inner.thumbnail(file)

    override fun upload(parentFid: String, name: String, bytes: ByteArray): String =
        inner.upload(parentFid, name, bytes).also { invalidate(parentFid) }

    override fun delete(fids: List<String>) {
        inner.delete(fids)
        invalidateParentsOf(fids)
        synchronized(this) { fids.forEach { listings.remove(it); stale.remove(it) } }
    }

    override fun mkdir(parentFid: String, name: String): String =
        inner.mkdir(parentFid, name).also { invalidate(parentFid) }

    override fun rename(fid: String, newName: String) {
        inner.rename(fid, newName)
        invalidateParentsOf(listOf(fid))
    }

    @Synchronized
    private fun invalidateParentsOf(fids: List<String>) {
        listings.forEach { (dir, l) -> if (l.any { it.fid in fids }) stale += dir }
    }
}
