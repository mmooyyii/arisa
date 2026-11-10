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
                    if (!breakOnce) Thread.sleep(Long.MAX_VALUE)
                    // 只断从文件开头读的那个连接 (第一段), 后面的段有自己的重试
                    if (start == 0 && broken.compareAndSet(false, true)) throw IOException("假装连接断了")
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
        ParallelRangeDataSource(upstream, contentLength, concurrency = 3, partSize = 1000, firstPartSize = 100)

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
        val s = ParallelRangeDataSource(Upstream(), contentLength = null, concurrency = 3, partSize = 1000, firstPartSize = 100)
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
}
