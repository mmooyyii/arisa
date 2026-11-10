package arisa.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 分段并发读取, 照搬 OpenList 的做法 (internal/net/request.go 的 downloader, 夸克驱动用 Concurrency 3 / PartSize 10MB):
 * 文件切成固定大小的段, 几个线程并发下载有上下界的 Range, 按顺序交给播放器, 下一段没到就等.
 *
 * 夸克对一直读到文件末尾的 `Range: bytes=N-` 限速到 100KB/s 左右 (SVIP 也一样),
 * 有上下界的 `bytes=N-M` 能跑满 (实测单连接 8~10MB/s).
 *
 * [contentLength] 是文件大小 (取直链时夸克给的): 请求的结束位置超过文件末尾时, 夸克不按 Range 返回,
 * 而是 200 加整个文件, 播放器就得从头读过去. MP4 的索引在文件末尾时 (先读末尾再回来播) 会卡住不动,
 * 所以每段都要裁到文件末尾为止.
 *
 * 第一段不攒, 收到多少就交给播放器多少 (和普通的单连接读取一样): 起播、跳转时播放器先要的只是文件头
 * 或者够播一秒的数据, 码率低的片子几百 KB 就够, 不用等一整段下完.
 * 后面的段是提前下的, 播放器还没读到那里, 所以先放在内存里, 整段下完再按顺序交出去.
 * 这些段从 [firstPartSize] 的两倍起每段翻倍, 到 [partSize] 为止: 跳转后紧接着要的几段小一点, 到得快.
 *
 * 整段大小的缓冲区用完放回 [pool] 接着用: 高码率的片子大约每秒读完一段, 每段新开 10MB 的数组的话,
 * 堆本来就被播放缓冲占了一大块, 垃圾回收会很频繁.
 */
@UnstableApi
class ParallelRangeDataSource(
    private val upstream: DataSource.Factory,
    private val contentLength: Long? = null,
    private val concurrency: Int = 3,
    private val partSize: Int = 10 * 1024 * 1024,
    private val firstPartSize: Int = 1024 * 1024,
) : DataSource {
    /** 下好的一段: [buf] 的前 [length] 个字节. */
    private class Part(val buf: ByteArray, val length: Int)

    private var spec: DataSpec? = null
    private var executor: ExecutorService? = null
    private val pending = ArrayDeque<Future<Part>>()
    /** 空闲的整段缓冲区 (大小都是 [partSize]), 跟着这个数据源一直留着, 跳转后重新 open 也接着用. */
    private val pool = ArrayDeque<ByteArray>()
    /** 正在下载的连接, close 时关掉好让线程退出. */
    private val active = Collections.synchronizedSet(mutableSetOf<DataSource>())
    private val listeners = mutableListOf<TransferListener>()
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private var uri: Uri? = null

    /** 下一个要排进队列的段, 相对 spec.position. */
    private var nextOffset = 0L
    /** 要读的总长度, 相对 spec.position. */
    private var total = 0L
    /** 已经排了几段, 用来算下一段多大. */
    private var parts = 0
    /** 第一段的连接, 边收边交给播放器; 读完就是 null. */
    private var head: DataSource? = null
    /** 第一段读到哪了 (相对 spec.position) 和还剩多少. */
    private var headPos = 0L
    private var headLeft = 0L
    private var current: Part? = null
    private var currentPos = 0

    /** 第 [index] 段的大小: 1MB (边收边给), 2MB, 4MB, 8MB, 之后都是 [partSize]. */
    private fun sizeOf(index: Int): Int = if (index >= 30) partSize else minOf(partSize.toLong(), firstPartSize.toLong() shl index).toInt()

    private fun acquire(size: Int): ByteArray = if (size == partSize) pool.pollFirst() ?: ByteArray(partSize) else ByteArray(size)

    /** 读完的段把缓冲区还回去. 只在没有线程还在往里写的时候调. */
    private fun recycle(part: Part?) {
        if (part != null && part.buf.size == partSize && pool.size <= concurrency) pool.addLast(part.buf)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        executor = Executors.newFixedThreadPool(concurrency)
        // 要读的长度: 播放器指定的, 或者到文件末尾
        val known = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            contentLength != null -> contentLength - dataSpec.position
            else -> null
        }
        if (known != null && known <= 0) return 0
        // 第一段只把连接打开, 数据在 read 里边收边给; 不知道文件大小时从 Content-Range 拿
        val firstSize = if (known == null) sizeOf(0).toLong() else minOf(sizeOf(0).toLong(), known)
        headPos = 0
        headLeft = firstSize
        head = openHead(dataSpec)
        total = known ?: responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
            ?.substringAfter('/')?.toLongOrNull()?.minus(dataSpec.position)
            ?: firstSize
        current = null
        currentPos = 0
        nextOffset = firstSize
        parts = 1
        fill()
        return total
    }

    /** 保持 [concurrency] 个段在下载. */
    private fun fill() {
        val s = spec ?: return
        val ex = executor ?: return
        while (pending.size < concurrency && nextOffset < total) {
            val offset = nextOffset
            val len = minOf(sizeOf(parts).toLong(), total - offset).toInt()
            val buf = acquire(len)
            pending += ex.submit<Part> { fetch(s, offset, buf, len) }
            nextOffset += len
            parts++
        }
    }

    /** 打开第一段剩下的部分 (从 [headPos] 起 [headLeft] 个字节), 失败重试 3 次. */
    private fun openHead(s: DataSpec): DataSource {
        var last: IOException? = null
        repeat(PART_RETRIES) { attempt ->
            val src = upstream.createDataSource().also { d -> listeners.forEach(d::addTransferListener) }
            active += src
            try {
                src.open(s.subrange(headPos, headLeft))
                responseHeaders = src.responseHeaders
                uri = src.uri
                return src
            } catch (e: IOException) {
                active -= src
                runCatching { src.close() }
                last = e
                if (attempt < PART_RETRIES - 1) Thread.sleep(500L * (attempt + 1))
            }
        }
        throw last!!
    }

    private fun closeHead() {
        head?.let { active -= it; runCatching { it.close() } }
        head = null
    }

    /** 从第一段的连接读; 读完了返回 null. 中途断了就从断的地方重新连. */
    private fun readHead(buffer: ByteArray, offset: Int, length: Int): Int? {
        var failures = 0
        while (headLeft > 0) {
            val src = head ?: openHead(spec ?: return null).also { head = it }
            val n = try {
                src.read(buffer, offset, minOf(length.toLong(), headLeft).toInt())
            } catch (e: IOException) {
                if (e is InterruptedIOException || ++failures >= PART_RETRIES) throw e
                closeHead()
                continue
            }
            if (n == C.RESULT_END_OF_INPUT) break // 文件比预想的短
            if (n > 0) {
                headPos += n
                headLeft -= n
                if (headLeft == 0L) closeHead()
                return n
            }
        }
        headLeft = 0
        closeHead()
        return null
    }

    /** 下载一段到 [buf] 里, 失败重试 3 次. */
    private fun fetch(s: DataSpec, offset: Long, buf: ByteArray, length: Int): Part {
        var last: IOException? = null
        repeat(PART_RETRIES) { attempt ->
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val src = upstream.createDataSource().also { d -> listeners.forEach(d::addTransferListener) }
            active += src
            try {
                src.open(s.subrange(offset, length.toLong()))
                var read = 0
                while (read < length) {
                    val n = src.read(buf, read, length - read)
                    if (n == C.RESULT_END_OF_INPUT) break
                    read += n
                }
                return Part(buf, read)
            } catch (e: IOException) {
                last = e
                if (attempt < PART_RETRIES - 1) Thread.sleep(500L * (attempt + 1))
            } finally {
                active -= src
                runCatching { src.close() }
            }
        }
        throw last!!
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (headLeft > 0) readHead(buffer, offset, length)?.let { return it }
        var part = current
        while (part == null || currentPos >= part.length) {
            val next = pending.pollFirst() ?: return C.RESULT_END_OF_INPUT
            recycle(part)
            current = null
            part = try {
                next.get()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            } catch (e: ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause)
            }
            current = part
            currentPos = 0
            fill()
        }
        val n = minOf(length, part.length - currentPos)
        System.arraycopy(part.buf, currentPos, buffer, offset, n)
        currentPos += n
        return n
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun close() {
        // 已经下完的段, 缓冲区可以留着用; 还在下的那些线程可能还在往里写, 不能再用, 丢掉
        pending.forEach { f ->
            if (f.isDone && !f.isCancelled) recycle(runCatching { f.get() }.getOrNull()) else f.cancel(true)
        }
        pending.clear()
        synchronized(active) { active.forEach { runCatching { it.close() } } }
        head = null
        headLeft = 0
        executor?.shutdownNow()
        executor = null
        recycle(current)
        current = null
        parts = 0
        spec = null
    }

    class Factory(private val upstream: DataSource.Factory, private val contentLength: Long?) : DataSource.Factory {
        override fun createDataSource(): DataSource = ParallelRangeDataSource(upstream, contentLength)
    }

    private companion object {
        const val PART_RETRIES = 3
    }
}
