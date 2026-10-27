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
 */
@UnstableApi
class ParallelRangeDataSource(
    private val upstream: DataSource.Factory,
    private val contentLength: Long? = null,
    private val concurrency: Int = 3,
    private val partSize: Long = 10L * 1024 * 1024,
) : DataSource {
    private var spec: DataSpec? = null
    private var executor: ExecutorService? = null
    private val pending = ArrayDeque<Future<ByteArray>>()
    /** 正在下载的连接, close 时关掉好让线程退出. */
    private val active = Collections.synchronizedSet(mutableSetOf<DataSource>())
    private val listeners = mutableListOf<TransferListener>()
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private var uri: Uri? = null

    /** 下一个要排进队列的段, 相对 spec.position. */
    private var nextOffset = 0L
    /** 要读的总长度, 相对 spec.position. */
    private var total = 0L
    private var current: ByteArray? = null
    private var currentPos = 0

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
        // 第一段同步下; 不知道文件大小时从 Content-Range 拿
        val first = fetch(dataSpec, 0, if (known == null) partSize else minOf(partSize, known), headers = true)
        total = known ?: responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
            ?.substringAfter('/')?.toLongOrNull()?.minus(dataSpec.position)
            ?: first.size.toLong()
        current = first
        currentPos = 0
        nextOffset = first.size.toLong()
        fill()
        return total
    }

    /** 保持 [concurrency] 个段在下载. */
    private fun fill() {
        val s = spec ?: return
        val ex = executor ?: return
        while (pending.size < concurrency && nextOffset < total) {
            val offset = nextOffset
            val len = minOf(partSize, total - offset)
            pending += ex.submit<ByteArray> { fetch(s, offset, len, headers = false) }
            nextOffset += len
        }
    }

    /** 下载一段, 失败重试 3 次. */
    private fun fetch(s: DataSpec, offset: Long, length: Long, headers: Boolean): ByteArray {
        var last: IOException? = null
        repeat(PART_RETRIES) { attempt ->
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val src = upstream.createDataSource().also { d -> listeners.forEach(d::addTransferListener) }
            active += src
            try {
                src.open(s.subrange(offset, length))
                if (headers) {
                    responseHeaders = src.responseHeaders
                    uri = src.uri
                }
                val buf = ByteArray(length.toInt())
                var read = 0
                while (read < buf.size) {
                    val n = src.read(buf, read, buf.size - read)
                    if (n == C.RESULT_END_OF_INPUT) break
                    read += n
                }
                return if (read == buf.size) buf else buf.copyOf(read)
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
        var buf = current
        while (buf == null || currentPos >= buf.size) {
            val next = pending.pollFirst() ?: return C.RESULT_END_OF_INPUT
            buf = try {
                next.get()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            } catch (e: ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause)
            }
            current = buf
            currentPos = 0
            fill()
        }
        val n = minOf(length, buf.size - currentPos)
        System.arraycopy(buf, currentPos, buffer, offset, n)
        currentPos += n
        return n
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun close() {
        pending.forEach { it.cancel(true) }
        pending.clear()
        synchronized(active) { active.forEach { runCatching { it.close() } } }
        executor?.shutdownNow()
        executor = null
        current = null
        spec = null
    }

    class Factory(private val upstream: DataSource.Factory, private val contentLength: Long?) : DataSource.Factory {
        override fun createDataSource(): DataSource = ParallelRangeDataSource(upstream, contentLength)
    }

    private companion object {
        const val PART_RETRIES = 3
    }
}
