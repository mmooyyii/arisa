package quarkflix.scraper

import quarkflix.quark.QuarkFile
import quarkflix.quark.QuarkWebClient
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

    private fun <T> retry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
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
