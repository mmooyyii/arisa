package arisa.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 分段并发读取, 做法来自 OpenList (internal/net/request.go 的 downloader, 夸克驱动用 Concurrency 3 / PartSize 10MB):
 * 文件切成段, 几个线程并发下载有上下界的 Range, 按顺序交给播放器.
 *
 * 夸克对一直读到文件末尾的 `Range: bytes=N-` 限速到 100KB/s 左右 (SVIP 也一样),
 * 有上下界的 `bytes=N-M` 能跑满 (实测单个请求 10MB/s 上下).
 *
 * [contentLength] 是文件大小 (取直链时夸克给的): 请求的结束位置超过文件末尾时, 夸克不按 Range 返回,
 * 而是 200 加整个文件, 播放器就得从头读过去. MP4 的索引在文件末尾时 (先读末尾再回来播) 会卡住不动,
 * 所以每段都要裁到文件末尾为止.
 *
 * 数据不攒, 收到多少就能读多少: 每段下载到内存里, 播放器读到某一段时, 这段已经到了的部分马上能读,
 * 不用等整段下完. 起播、跳转时播放器要的只是文件头或者够播一秒的数据, 等整段到齐是白等.
 *
 * 段的大小从 [firstPartSize] 起每段翻倍, 到 [partSize] 为止. 实测 (百兆宽带, 跳转后连续到齐 N 字节的耗时,
 * 其中 0.2 秒是夸克开始回数据前的等待): 1+2+4MB 三个请求同时发, 1MB 0.41 秒、5MB 0.55 秒;
 * 只发一个请求是 0.34 秒和 0.68 秒. 所以一打开就并发, 但前几个请求要小, 都落在马上要播的那几 MB 上;
 * 一上来就是 10MB 一段的话, 后两个请求下的是十几 MB 以后的数据, 只会分走带宽.
 *
 * 连哪个服务器: [nodes] 不为空时, 每次连接前从里面挑一个 IP (见 [DownloadNodes]), [connector] 按 IP 建连接.
 * 另有一个线程每 [checkEveryMs] 看一遍正在下载的连接: [stallMs] 内一个字节都没收到的 (卡住),
 * 或者最近 [windowMs] 的速度低于 [slowBelow] 而且不到同一时刻别的节点上的连接的 2/5 的 (慢), 断开换个 IP 从断的地方接着下.
 * 只和同一时刻的比: 2026-11-25 在投影仪上看到过所有节点一起掉到 0.3~0.8MB/s 十几秒, 那是家里这头的问题, 换节点没用.
 * 不这样的话, 卡住的连接要等 OkHttp 读超时, 慢的节点会一直用下去. 每个连接结束时在 [log] 里记一行.
 *
 * 整段大小的缓冲区用完放回 [pool] 接着用: 高码率的片子大约每秒读完一段, 每段新开 10MB 的数组的话,
 * 堆本来就被播放缓冲占了一大块, 垃圾回收会很频繁.
 */
@UnstableApi
class ParallelRangeDataSource(
    private val connector: Connector,
    private val contentLength: Long? = null,
    private val nodes: DownloadNodes? = null,
    private val log: (String) -> Unit = {},
    private val concurrency: Int = 3,
    private val partSize: Int = 10 * 1024 * 1024,
    private val firstPartSize: Int = 1024 * 1024,
    private val checkEveryMs: Long = 1000,
    private val stallMs: Long = 8000,
    private val windowMs: Long = 3000,
    /** 字节每秒. 比这快的连接不算慢, 不换: 3 个连接加起来够播 4K 原盘. */
    private val slowBelow: Long = 2L * 1024 * 1024,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : DataSource {
    /** 建一个连 [node] 的上游数据源; [node] 为 null 时按域名照常解析. */
    fun interface Connector {
        fun create(node: InetAddress?): DataSource
    }

    /** 一个连接: 下载线程在用, 检查线程看它的速度, 要换的话把 [abort] 填上再关掉 [src]. */
    private class Conn(val node: DownloadNodes.Node?, val src: DataSource, val start: Long, val offset: Long) {
        @Volatile var opened = false
        @Volatile var firstByteAt = 0L
        @Volatile var lastByteAt = 0L
        val bytes = AtomicLong()
        @Volatile var abort: String? = null
        // 下面几个只有检查线程读写
        var markAt = 0L
        var markBytes = 0L
        /** 最近一个窗口的速度, 字节每秒; 还没满一个窗口是 -1. */
        var rate = -1L
    }
    /**
     * 一段: 下载线程往 [buf] 里写, 读的线程跟在后面读. 除了 [buf] 的内容, 其他状态都在 [lock] 里读写;
     * [buf] 里 [written] 之前的字节不会再变, 读的线程在锁里拿到 [written] 后可以在锁外拷贝.
     *
     * @param exact 这段一定有 [size] 个字节 (知道文件大小时). 上游提前结束就是连接出了问题, 要接着下,
     *   不能当成文件到头了, 否则播放器拿到的数据中间会缺一块.
     */
    private class Part(val offset: Long, val buf: ByteArray, val size: Int, val exact: Boolean) {
        val lock = ReentrantLock()
        val changed = lock.newCondition()
        /** 连上了 (拿到了响应头). */
        var opened = false
        var headers: Map<String, List<String>> = emptyMap()
        var uri: Uri? = null
        var written = 0
        /** 下载线程不会再写了: 下完了, 或者不知道文件大小时读到了文件末尾. */
        var done = false
        var error: IOException? = null

        /** 等到有新状态: [ready] 返回 true, 或者下载结束、失败. */
        inline fun await(ready: () -> Boolean) {
            try {
                while (!ready() && !done && error == null) changed.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
        }
    }

    private var spec: DataSpec? = null
    private var executor: ExecutorService? = null
    private var checker: ScheduledExecutorService? = null
    /** 这次 open 的下载线程该停了 (播放器 close 了). 每次 open 一个新的, 旧线程看的还是自己那个. */
    private var stopped = AtomicBoolean()
    /** 排在 [current] 后面的段, 正在下载或者已经下完. */
    private val pending = ArrayDeque<Part>()
    /** 空闲的整段缓冲区 (大小都是 [partSize]), 跟着这个数据源一直留着, 跳转后重新 open 也接着用. */
    private val pool = ArrayDeque<ByteArray>()
    /** 正在下载的连接, close 时关掉好让线程退出. */
    private val active = Collections.synchronizedSet(mutableSetOf<Conn>())
    private val listeners = mutableListOf<TransferListener>()
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private var uri: Uri? = null

    /** 下一个要排进队列的段, 相对 spec.position. */
    private var nextOffset = 0L
    /** 要读的总长度, 相对 spec.position. */
    private var total = 0L
    /** 已经排了几段, 用来算下一段多大. */
    private var parts = 0
    /** 播放器正在读的段, 和读到了哪. */
    private var current: Part? = null
    private var currentPos = 0

    /** 第 [index] 段的大小: 1MB, 2MB, 4MB, 8MB, 之后都是 [partSize]. */
    private fun sizeOf(index: Int): Int = if (index >= 30) partSize else minOf(partSize.toLong(), firstPartSize.toLong() shl index).toInt()

    private fun acquire(size: Int): ByteArray = if (size == partSize) pool.pollFirst() ?: ByteArray(partSize) else ByteArray(size)

    /** 把缓冲区还回去. 下载线程还可能往里写的 (没下完) 不能再用, 丢掉. */
    private fun recycle(part: Part?) {
        if (part == null || part.buf.size != partSize || pool.size > concurrency) return
        if (part.lock.withLock { part.done }) pool.addLast(part.buf)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        // 要读的长度: 播放器指定的, 或者到文件末尾
        val known = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            contentLength != null -> contentLength - dataSpec.position
            else -> null
        }
        if (known != null && known <= 0) return 0
        stopped = AtomicBoolean()
        executor = Executors.newFixedThreadPool(concurrency)
        checker = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ runCatching { check() } }, checkEveryMs, checkEveryMs, TimeUnit.MILLISECONDS)
        }
        nextOffset = 0
        parts = 0
        currentPos = 0
        if (known != null) {
            total = known
            fill()
        } else {
            // 不知道文件大小: 先只发第一段, 从它的 Content-Range 拿
            total = sizeOf(0).toLong()
            fill(exact = false)
        }
        val first = pending.removeFirst()
        current = first
        // 等第一段连上 (失败的话重试完了从这里抛出去), 数据在 read 里边到边给
        first.lock.withLock {
            first.await { first.opened }
            if (!first.opened) first.error?.let { throw it }
            responseHeaders = first.headers
            uri = first.uri
        }
        if (known == null) {
            responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
                ?.substringAfter('/')?.toLongOrNull()?.minus(dataSpec.position)?.takeIf { it > 0 }?.let { total = it }
        }
        fill()
        return total
    }

    /** 播放器正在读的那段后面, 保持排着 [concurrency] 段 (同时下载的最多 [concurrency] 个, 多出来的那段等空出线程). */
    private fun fill(exact: Boolean = true) {
        val s = spec ?: return
        val ex = executor ?: return
        while (pending.size < concurrency && nextOffset < total) {
            val len = minOf(sizeOf(parts).toLong(), total - nextOffset).toInt()
            val part = Part(nextOffset, acquire(len), len, exact)
            pending += part
            val stop = stopped
            ex.execute { fetch(s, part, stop) }
            nextOffset += len
            parts++
        }
    }

    /**
     * 下载一段, 边下边更新 [Part.written]. 断了、被检查线程换掉了, 换个 IP 从断的地方接着下;
     * 出错 (不算被换掉的) 连着 3 次没有进展算失败.
     */
    private fun fetch(s: DataSpec, part: Part, stop: AtomicBoolean) {
        var failures = 0
        var error: IOException? = null
        try {
            var at = 0
            while (at < part.size) {
                if (stop.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException()
                val node = nodes?.pick()
                val src = connector.create(node?.address).also { d -> listeners.forEach(d::addTransferListener) }
                val conn = Conn(node, src, clock(), part.offset + at)
                active += conn
                var end = "完成"
                try {
                    src.open(s.subrange(part.offset + at, (part.size - at).toLong()))
                    conn.opened = true
                    part.lock.withLock {
                        if (!part.opened) {
                            part.opened = true
                            part.headers = src.responseHeaders
                            part.uri = src.uri
                            part.changed.signalAll()
                        }
                    }
                    while (at < part.size) {
                        val n = src.read(part.buf, at, part.size - at)
                        if (n == C.RESULT_END_OF_INPUT) {
                            if (part.exact) throw IOException("连接提前结束: 第 ${part.offset + at} 字节, 这段还差 ${part.size - at}")
                            end = "到文件末尾"
                            return
                        }
                        if (n > 0) {
                            at += n
                            failures = 0
                            val now = clock()
                            if (conn.firstByteAt == 0L) conn.firstByteAt = now
                            conn.lastByteAt = now
                            conn.bytes.addAndGet(n.toLong())
                            part.lock.withLock {
                                part.written = at
                                part.changed.signalAll()
                            }
                        }
                    }
                } catch (e: IOException) {
                    // 播放器关掉的: 不是节点的问题, 也不用再下了
                    if (stop.get()) {
                        end = STOPPED
                        throw InterruptedIOException()
                    }
                    // 检查线程关掉的: 不算出错, 换个 IP 接着下
                    val abort = conn.abort
                    if (abort != null) {
                        end = abort
                        continue
                    }
                    end = "出错 ${e.message}"
                    if (e is InterruptedIOException || ++failures >= PART_RETRIES) throw e
                    Thread.sleep(500L * failures)
                } finally {
                    active -= conn
                    runCatching { src.close() }
                    finish(conn, end)
                }
            }
        } catch (e: InterruptedException) {
            error = InterruptedIOException()
        } catch (e: IOException) {
            error = e
        } catch (e: Throwable) {
            error = IOException(e)
        } finally {
            part.lock.withLock {
                if (error == null) part.done = true else part.error = error
                part.changed.signalAll()
            }
        }
    }

    /** 一个连接用完: 速度记到节点上, 写一行日志. */
    private fun finish(conn: Conn, end: String) {
        val bytes = conn.bytes.get()
        val millis = if (conn.firstByteAt > 0) conn.lastByteAt - conn.firstByteAt else 0
        val bad = end != "完成" && end != "到文件末尾" && end != STOPPED
        conn.node?.let { nodes?.release(it, bytes, millis, bad) }
        val wait = if (conn.firstByteAt > 0) "${conn.firstByteAt - conn.start}ms" else "-"
        val speed = if (millis > 0) "%.2fMB/s".format(bytes * 1000.0 / millis / 1048576) else "-"
        log("${conn.node ?: "-"} 从 ${conn.offset} 收 ${bytes / 1024}KB 首字节 $wait 速度 $speed $end")
    }

    /** 检查线程: 卡住的、慢的连接关掉, 下载线程会换个 IP 接着下. */
    private fun check() {
        val now = clock()
        val conns = synchronized(active) { active.toList() }
        for (c in conns) {
            val got = c.bytes.get()
            if (c.markAt == 0L) {
                c.markAt = now
                c.markBytes = got
            } else if (now - c.markAt >= windowMs) {
                c.rate = (got - c.markBytes) * 1000 / (now - c.markAt)
                c.markAt = now
                c.markBytes = got
            }
        }
        for (c in conns) {
            if (c.abort != null || !c.opened) continue
            val since = if (c.lastByteAt > 0) c.lastByteAt else c.start
            val reason = when {
                now - since >= stallMs -> "卡住 ${(now - since) / 1000} 秒"
                // 只和同一时刻别的节点上的连接比: 大家一起慢是家里这头的问题 (Wi-Fi、别的设备在下载), 换节点没用
                c.rate in 0 until slowBelow && c.node != null && nodes?.hasOther(c.node) == true -> {
                    val ref = conns.filter { it !== c && it.node != c.node && it.rate >= 0 }.maxOfOrNull { it.rate } ?: 0
                    if (c.rate * 5 < ref * 2) "慢 %.2fMB/s, 别的节点 %.2fMB/s".format(c.rate / 1048576.0, ref / 1048576.0) else null
                }
                else -> null
            } ?: continue
            c.abort = reason
            runCatching { c.src.close() }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val part = current ?: return C.RESULT_END_OF_INPUT
            // 等这一段有新数据. 已经到了的先给出去, 都给完了才报下载的错
            val available = part.lock.withLock {
                part.await { part.written > currentPos }
                if (part.written <= currentPos) part.error?.let { throw it }
                part.written - currentPos
            }
            if (available > 0) {
                val n = minOf(length, available)
                System.arraycopy(part.buf, currentPos, buffer, offset, n)
                currentPos += n
                return n
            }
            // 这段下完了也读完了, 换下一段
            recycle(part)
            current = pending.pollFirst()
            currentPos = 0
            fill()
        }
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun close() {
        // 先让下载线程停下来 (关连接、打断), 再收缓冲区
        stopped.set(true)
        checker?.shutdownNow()
        checker = null
        synchronized(active) { active.forEach { runCatching { it.src.close() } } }
        executor?.shutdownNow()
        executor = null
        recycle(current)
        current = null
        pending.forEach(::recycle)
        pending.clear()
        spec = null
    }

    class Factory(
        private val connector: Connector,
        private val contentLength: Long?,
        private val nodes: DownloadNodes? = null,
        private val log: (String) -> Unit = {},
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ParallelRangeDataSource(connector, contentLength, nodes, log)
    }

    private companion object {
        const val PART_RETRIES = 3
        const val STOPPED = "播放器关闭"
    }
}
