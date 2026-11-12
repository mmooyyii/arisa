package arisa.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.CompletableDeferred
import org.mockito.Mockito
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PendingSubtitleDataSourceTest {
    private fun uri(scheme: String, host: String): Uri = Mockito.mock(Uri::class.java).also {
        Mockito.`when`(it.scheme).thenReturn(scheme)
        Mockito.`when`(it.host).thenReturn(host)
    }

    /** 视频的数据源: 记下有没有被打开. */
    private class Upstream : DataSource {
        var opened = false
        var closed = false
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long { opened = true; return 3 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { buffer[offset] = 7; return 1 }
        override fun getUri(): Uri? = null
        override fun close() { closed = true }
    }

    private fun DataSource.readAll(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(5)
        while (true) {
            val n = read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out.write(buf, 0, n)
        }
    }

    private fun tempFile(text: String): File = File.createTempFile("sub", ".srt").apply { deleteOnExit(); writeText(text) }

    @Test
    fun `字幕没下完时打开不等, 读的时候等到下完`() {
        val file = CompletableDeferred<File?>()
        val source = PendingSubtitleDataSource(Upstream(), mapOf("abc" to PendingSubtitleDataSource.Subtitle(file)))
        // 打开马上返回: 播放器靠这个认为字幕轨道准备好了, 不耽误视频
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(DataSpec.Builder().setUri(uri("arisa-subtitle", "abc")).build()))

        val result = AtomicReference<ByteArray>()
        val done = CountDownLatch(1)
        thread { result.set(source.readAll()); done.countDown() }
        assertFalse(done.await(200, TimeUnit.MILLISECONDS), "字幕没下完, 读应该还在等")

        file.complete(tempFile("1\n00:00:01,000 --> 00:00:02,000\n你好\n"))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\n你好\n", result.get().decodeToString())
        source.close()
    }

    @Test
    fun `字幕下载失败时给一份空的, 不报错`() {
        val empty = "WEBVTT\n\n".toByteArray()
        val source = PendingSubtitleDataSource(Upstream(), mapOf("abc" to PendingSubtitleDataSource.Subtitle(CompletableDeferred<File?>(value = null), empty)))
        source.open(DataSpec.Builder().setUri(uri("arisa-subtitle", "abc")).build())
        assertEquals("WEBVTT\n\n", source.readAll().decodeToString())
    }

    @Test
    fun `下载被取消时也给空的`() {
        val file = CompletableDeferred<File?>().apply { cancel() }
        val source = PendingSubtitleDataSource(Upstream(), mapOf("abc" to PendingSubtitleDataSource.Subtitle(file)))
        source.open(DataSpec.Builder().setUri(uri("arisa-subtitle", "abc")).build())
        assertEquals(0, source.readAll().size)
    }

    @Test
    fun `等字幕的线程被打断就退出`() {
        val source = PendingSubtitleDataSource(Upstream(), mapOf("abc" to PendingSubtitleDataSource.Subtitle(CompletableDeferred())))
        source.open(DataSpec.Builder().setUri(uri("arisa-subtitle", "abc")).build())
        val error = AtomicReference<Throwable>()
        val t = thread { runCatching { source.readAll() }.onFailure(error::set) }
        Thread.sleep(100)
        t.interrupt()
        t.join(5000)
        assertFalse(t.isAlive)
        assertIs<InterruptedIOException>(error.get())
    }

    @Test
    fun `视频的地址原样交给上游`() {
        val upstream = Upstream()
        val source = PendingSubtitleDataSource(upstream, emptyMap())
        assertEquals(3, source.open(DataSpec.Builder().setUri(uri("https", "example.com")).build()))
        assertTrue(upstream.opened)
        val buf = ByteArray(1)
        assertEquals(1, source.read(buf, 0, 1))
        assertEquals(7, buf[0])
        source.close()
        assertTrue(upstream.closed)
    }
}
