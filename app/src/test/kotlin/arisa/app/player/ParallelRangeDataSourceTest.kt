package arisa.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.mockito.Mockito
import java.io.IOException
import java.util.Collections
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelRangeDataSourceTest {
    private val uri: Uri = Mockito.mock(Uri::class.java)
    private val data = Random(1).nextBytes(12_345)

    /** 发出去的请求 (起点, 长度), 按发出的先后. */
    private val requests = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())

    /** 假的上游: 按 Range 返回 [data] 里的一段; 可以让某些请求先失败几次. */
    private inner class Upstream(
        private val failFirst: Int = 0,
        /** 每个连接读到这么多字节后: 断一次 ([breakOnce]) 或者一直卡住不再给数据. */
        private val stopAfter: Int = Int.MAX_VALUE,
        private val breakOnce: Boolean = false,
        /** 只断从这个位置开始读的连接. */
        private val breakAt: Int = 0,
        /** 只卡从这个位置或更后面开始读的连接. */
        private val stallFrom: Int = 0,
        /** 从 [breakAt] 开始读的连接, 不是报错而是提前结束 (像服务器把连接正常关了). */
        private val endEarly: Boolean = false,
        /** 从这个位置或更后面开始读的连接, 读到 [stopAfter] 后每次都报错. */
        private val alwaysFailFrom: Int = Int.MAX_VALUE,
    ) : DataSource.Factory {
        private val broken = java.util.concurrent.atomic.AtomicBoolean()

        private val failures = java.util.concurrent.atomic.AtomicInteger(failFirst)

        override fun createDataSource(): DataSource = object : DataSource {
            private var start = 0
            private var pos = 0
            private var end = 0

            override fun addTransferListener(transferListener: TransferListener) {}

            override fun open(dataSpec: DataSpec): Long {
                requests += dataSpec.position to dataSpec.length
                if (failures.getAndDecrement() > 0) throw IOException("假装断网")
                // 夸克对超出文件末尾的 Range 会返回整个文件, 这里直接算错
                check(dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.position + dataSpec.length <= data.size) { "请求超出文件末尾: $dataSpec" }
                start = dataSpec.position.toInt()
                pos = start
                end = pos + dataSpec.length.toInt()
                return dataSpec.length
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (pos >= end) return C.RESULT_END_OF_INPUT
                if (pos - start >= stopAfter) {
                    if (start >= alwaysFailFrom) throw IOException("假装一直连不上")
                    if (endEarly && start == breakAt && broken.compareAndSet(false, true)) return C.RESULT_END_OF_INPUT
                    if (!breakOnce && !endEarly && start >= stallFrom) Thread.sleep(Long.MAX_VALUE)
                    if (breakOnce && start == breakAt && broken.compareAndSet(false, true)) throw IOException("假装连接断了")
                }
                // 每次只给一点, 像网络那样零零碎碎地到
                val n = minOf(length, end - pos, 37)
                System.arraycopy(data, pos, buffer, offset, n)
                pos += n
                return n
            }

            override fun getUri(): Uri = this@ParallelRangeDataSourceTest.uri
            override fun getResponseHeaders(): Map<String, List<String>> = mapOf("Content-Range" to listOf("bytes 0-0/${data.size}"))
            override fun close() {}
        }
    }

    private fun source(contentLength: Long? = data.size.toLong(), upstream: DataSource.Factory = Upstream()) =
        ParallelRangeDataSource({ upstream.createDataSource() }, contentLength, concurrency = 3, partSize = 1000, firstPartSize = 100)

    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()

    /** 读 [limit] 个字节 (默认读到头). */
    private fun DataSource.readBytes(limit: Int = Int.MAX_VALUE): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test fun readsWholeFile() {
        val s = source()
        assertEquals(data.size.toLong(), s.open(spec()))
        assertContentEquals(data, s.readBytes())
        s.close()
    }

    @Test fun partsStartSmallAndGrow() {
        val s = source()
        s.open(spec())
        s.readBytes()
        s.close()
        val sorted = requests.sortedBy { it.first }
        // 100, 200, 400, 800, 之后每段 1000, 最后一段裁到文件末尾
        assertEquals(listOf(100L, 200L, 400L, 800L, 1000L), sorted.take(5).map { it.second })
        assertEquals(0L to 100L, requests.first())
        assertTrue(sorted.all { it.second <= 1000 })
        assertEquals(data.size.toLong(), sorted.sumOf { it.second })
        assertEquals(data.size.toLong(), sorted.last().let { it.first + it.second })
    }

    @Test fun readsFromPosition() {
        val s = source()
        assertEquals(data.size - 5000L, s.open(spec(position = 5000)))
        assertContentEquals(data.copyOfRange(5000, data.size), s.readBytes())
        s.close()
    }

    @Test fun readsRequestedLength() {
        val s = source()
        assertEquals(2500L, s.open(spec(position = 300, length = 2500)))
        assertContentEquals(data.copyOfRange(300, 2800), s.readBytes())
        s.close()
    }

    @Test fun readsAtEndOfFile() {
        val s = source()
        assertEquals(45L, s.open(spec(position = data.size - 45L)))
        assertContentEquals(data.copyOfRange(data.size - 45, data.size), s.readBytes())
        s.close()
        assertEquals(0L, s.open(spec(position = data.size.toLong())))
        s.close()
    }

    @Test fun sizeFromContentRangeWhenUnknown() {
        // 不知道文件大小时第一段的结束位置由上游的 Content-Range 定; 假的上游不许越界, 所以从靠后的位置开始
        val s = ParallelRangeDataSource({ Upstream().createDataSource() }, contentLength = null, concurrency = 3, partSize = 1000, firstPartSize = 100)
        assertEquals(data.size - 2000L, s.open(spec(position = 2000)))
        assertContentEquals(data.copyOfRange(2000, 2100), s.readBytes(limit = 100))
        s.close()
    }

    /** 跳转: 读到一半关掉, 换个位置重新打开, 缓冲区复用后数据不能串. */
    @Test fun reopenAfterSeekKeepsDataIntact() {
        val s = source()
        val random = Random(2)
        repeat(30) {
            val position = random.nextInt(data.size)
            s.open(spec(position.toLong()))
            val limit = random.nextInt(1, 6000)
            val expected = data.copyOfRange(position, minOf(data.size, position + limit))
            assertContentEquals(expected, s.readBytes(limit), "从 $position 读 $limit")
            s.close()
        }
    }

    /** 第一段不等下完: 连接只给了一部分就卡住, 已经到的那些也要能读出来. */
    @Test fun firstPartIsHandedOverAsItArrives() {
        val s = source(upstream = Upstream(stopAfter = 60))
        s.open(spec())
        assertContentEquals(data.copyOfRange(0, 60), s.readBytes(limit = 60))
        s.close()
    }

    /** 后面的段也一样: 每个连接都只给 60 个字节就卡住, 第 1 段的 100 个读不全, 但第 1 段换成不卡的话第 2 段的前 60 个要读得到. */
    @Test fun laterPartsAreReadableWhileDownloading() {
        val s = source(upstream = Upstream(stopAfter = 60, stallFrom = 100))
        s.open(spec())
        assertContentEquals(data.copyOfRange(0, 160), s.readBytes(limit = 160))
        s.close()
    }

    @Test fun laterPartResumesAfterBrokenConnection() {
        val s = source(upstream = Upstream(stopAfter = 60, breakOnce = true, breakAt = 100))
        s.open(spec())
        assertContentEquals(data, s.readBytes())
        s.close()
        // 第 2 段 (100 起 200 个) 断在第 74 个字节, 从 174 接着要剩下的 126 个
        assertTrue(174L to 126L in requests)
    }

    /** 知道文件大小时, 连接提前结束不能当成文件到头: 要接着下, 数据中间不能缺. */
    @Test fun earlyEndOfStreamIsResumed() {
        for (at in listOf(0, 100)) {
            requests.clear()
            val s = source(upstream = Upstream(stopAfter = 60, endEarly = true, breakAt = at))
            s.open(spec())
            assertContentEquals(data, s.readBytes(), "从 $at 开始的那段提前结束")
            s.close()
        }
    }

    /** 一段怎么都下不了: 已经到的数据照样给出去, 读到缺的地方才报错. */
    @Test fun failureSurfacesAfterDeliveringWhatArrived() {
        // 第 1 段 (0 起 100 个) 正常; 从 100 起的连接一个字节都不给就断
        val s = source(upstream = Upstream(stopAfter = 0, alwaysFailFrom = 100, stallFrom = Int.MAX_VALUE))
        s.open(spec())
        val out = java.io.ByteArrayOutputStream()
        val got = runCatching {
            val buf = ByteArray(64)
            while (true) {
                val n = s.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
        }
        assertTrue(got.exceptionOrNull() is IOException)
        assertContentEquals(data.copyOfRange(0, 100), out.toByteArray())
        // 第 2 段连着 3 次没有进展才算失败
        assertEquals(listOf(100L, 100L, 100L), requests.map { it.first }.filter { it == 100L })
        s.close()
    }

    /** 断了但每次都有进展的连接不算失败, 一直接着下, 直到下完. */
    @Test fun keepsResumingWhileMakingProgress() {
        val s = source(upstream = Upstream(stopAfter = 60, alwaysFailFrom = 100, stallFrom = Int.MAX_VALUE))
        s.open(spec())
        // 只读前两段: 每断一次要等半秒再连, 整个文件读完要断一百多次
        assertContentEquals(data.copyOfRange(0, 300), s.readBytes(limit = 300))
        s.close()
        // 第 2 段 (100 起 200 个): 每次连上读 74 个就断, 从断的地方接着要
        assertEquals(listOf(100L, 174L, 248L), requests.map { it.first }.filter { it in 100L until 300L }.sorted())
    }

    @Test fun firstPartResumesAfterBrokenConnection() {
        val s = source(upstream = Upstream(stopAfter = 60, breakOnce = true))
        s.open(spec())
        assertContentEquals(data, s.readBytes())
        s.close()
        // 断在第 74 个字节 (每次给 37 个), 重连时从那里接着要剩下的 26 个
        assertTrue(74L to 26L in requests)
    }

    @Test fun retriesFailedParts() {
        val s = source(upstream = Upstream(failFirst = 2))
        s.open(spec())
        assertContentEquals(data, s.readBytes())
        s.close()
    }

    // ---------- 换节点 ----------

    private fun node(last: Int): java.net.InetAddress = java.net.InetAddress.getByAddress(byteArrayOf(10, 0, 0, last.toByte()))

    /**
     * 按 IP 表现不同的上游: [stallAfter] 个字节后卡住直到被关掉, 或者每次给 37 个字节前睡 [delayMs].
     * 记下每个 IP 收到的请求数.
     */
    private inner class NodeUpstream(
        private val stallAfter: Int = Int.MAX_VALUE,
        private val delayMs: Long = 0,
        /** 正常的 IP 每次也睡一下, 不然收完一段用时 0 毫秒, 量不出速度. */
        private val goodDelayMs: Long = 1,
    ) {
        val hits = java.util.concurrent.ConcurrentHashMap<Int, Int>()

        fun create(ip: java.net.InetAddress?, behaves: Boolean): DataSource = object : DataSource {
            private var start = 0
            private var pos = 0
            private var end = 0
            @Volatile private var closed = false

            override fun addTransferListener(transferListener: TransferListener) {}
            override fun open(dataSpec: DataSpec): Long {
                hits.merge(ip!!.address.last().toInt(), 1, Int::plus)
                requests += dataSpec.position to dataSpec.length
                start = dataSpec.position.toInt()
                pos = start
                end = pos + dataSpec.length.toInt()
                return dataSpec.length
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (pos >= end) return C.RESULT_END_OF_INPUT
                if (!behaves) {
                    if (pos - start >= stallAfter) while (!closed) Thread.sleep(5)
                    if (delayMs > 0) Thread.sleep(delayMs)
                } else {
                    Thread.sleep(goodDelayMs)
                }
                if (closed) throw IOException("连接被关了")
                val n = minOf(length, end - pos, 37)
                System.arraycopy(data, pos, buffer, offset, n)
                pos += n
                return n
            }
            override fun getUri(): Uri = this@ParallelRangeDataSourceTest.uri
            override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
            override fun close() { closed = true }
        }
    }

    private fun nodeSource(
        up: NodeUpstream,
        log: MutableList<String>,
        stallMs: Long = 60_000,
        slowBelow: Long = 0,
        // 10.0.0.1 表现不好, 10.0.0.2 正常
        nodes: DownloadNodes = DownloadNodes({ listOf(node(1), node(2)) }, minSample = 1),
    ): ParallelRangeDataSource {
        return ParallelRangeDataSource(
            { ip -> up.create(ip, behaves = ip == node(2)) }, data.size.toLong(), nodes, { synchronized(log) { log += it } },
            concurrency = 3, partSize = 1000, firstPartSize = 100,
            checkEveryMs = 10, stallMs = stallMs, windowMs = 50, slowBelow = slowBelow,
        )
    }

    /** 连上了但不给数据的连接, 等 [stallMs] 就关掉, 换个 IP 从断的地方接着下. */
    @Test fun stalledConnectionSwitchesNode() {
        val log = mutableListOf<String>()
        val up = NodeUpstream(stallAfter = 50)
        val s = nodeSource(up, log, stallMs = 200)
        s.open(spec())
        assertContentEquals(data, s.readBytes())
        s.close()
        assertTrue(log.any { it.startsWith("10.0.0.1 ") && "卡住" in it }, log.joinToString("\n"))
        assertTrue(up.hits.getValue(2) > 0)
    }

    /** 明显比别的节点慢的连接换掉; 数据照样完整. */
    @Test fun slowConnectionSwitchesNode() {
        val log = mutableListOf<String>()
        // 慢的每 20ms 给 37 个字节, 正常的每 5ms: 正常的一段 1000 个字节要 135ms, 够检查线程量出它同一时刻的速度
        val up = NodeUpstream(delayMs = 20, goodDelayMs = 5)
        val s = nodeSource(up, log, slowBelow = 100_000)
        s.open(spec())
        assertContentEquals(data, s.readBytes())
        s.close()
        assertTrue(log.any { it.startsWith("10.0.0.1 ") && "慢" in it }, log.joinToString("\n"))
        // 换掉以后一分钟内不再连它, 大部分数据是从 10.0.0.2 来的
        assertTrue(up.hits.getValue(2) > up.hits.getValue(1))
    }

    /** 每个连接结束都记一行: IP、起点、收了多少、首字节等了多久、速度、怎么结束的. */
    @Test fun logsEachConnection() {
        val log = mutableListOf<String>()
        val s = nodeSource(NodeUpstream(), log)
        s.open(spec())
        s.readBytes()
        s.close()
        assertTrue(log.isNotEmpty())
        assertTrue(log.all { Regex("""^10\.0\.0\.[12] 从 \d+ 收 \d+KB 首字节 (\d+ms|-) 速度 (\S+MB/s|-) .+""").matches(it) }, log.joinToString("\n"))
    }

    /** 播放器自己 close (跳转、起播时探测完文件结构) 打断的连接不算节点的错, 不能被拉黑. */
    @Test fun closingDoesNotBlameNodes() {
        val log = mutableListOf<String>()
        val nodes = DownloadNodes({ listOf(node(1), node(2)) }, minSample = 1)
        // 两个节点都很慢, close 时连接都还在下
        val up = NodeUpstream(delayMs = 20)
        val s = ParallelRangeDataSource(
            { ip -> up.create(ip, behaves = false) }, data.size.toLong(), nodes, { synchronized(log) { log += it } },
            concurrency = 3, partSize = 1000, firstPartSize = 100,
        )
        s.open(spec())
        s.readBytes(limit = 37)
        s.close()
        // 下载线程收尾要一点时间
        val deadline = System.currentTimeMillis() + 2000
        while (nodes.all().any { it.inUse > 0 } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(nodes.all().all { it.inUse == 0 })
        // 记完速度 (inUse 减一) 之后才写日志, 再等一下; 读日志要在锁里拷一份, 下载线程可能还在写
        Thread.sleep(100)
        val lines = synchronized(log) { log.toList() }
        assertTrue(nodes.all().all { it.avoidUntil == 0L }, lines.joinToString("\n"))
        assertTrue(lines.none { "出错" in it }, lines.joinToString("\n"))
    }
}
