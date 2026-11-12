package arisa.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * 外挂字幕还在下载时就能开播: 字幕的地址写成 `arisa-subtitle://<fid>`, 播放器来读的时候在这里等它下完.
 *
 * 播放器给每条外挂字幕单独开一个线程读, 这里等着不耽误视频: 打开 ([open]) 马上返回, 播放器这时就认为字幕轨道准备好了,
 * 等的是后面的 [read]; 字幕没到期间画面照常播, 到了以后字幕自己出来 (Media3 的字幕渲染器不等字幕加载).
 *
 * 其他地址 (视频) 原样交给 [upstream].
 */
@UnstableApi
class PendingSubtitleDataSource(
    private val upstream: DataSource,
    private val subtitles: Map<String, Subtitle>,
) : DataSource {
    /**
     * @param file 下载好的字幕文件; 下载失败是 null.
     * @param empty 下载失败时给播放器的内容: 一条字幕都没有, 但格式是对的 (WebVTT 没有文件头会被当成解析出错).
     */
    class Subtitle(val file: Deferred<File?>, val empty: ByteArray = ByteArray(0))

    private var uri: Uri? = null
    private var subtitle: Subtitle? = null
    private var input: InputStream? = null
    private var skip = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != SCHEME) return upstream.open(dataSpec)
        uri = dataSpec.uri
        subtitle = subtitles[dataSpec.uri.host] ?: Subtitle(CompletableDeferred<File?>(value = null))
        skip = dataSpec.position
        // 还不知道多大, 读到头为止
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val s = subtitle ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        val stream = input ?: run {
            val file = try {
                runBlocking { s.file.await() }
            } catch (e: InterruptedException) {
                // 播放器不要这条字幕了 (换集、退出), 线程被打断
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            } catch (e: CancellationException) {
                null
            }
            (file?.inputStream() ?: ByteArrayInputStream(s.empty)).also {
                var left = skip
                while (left > 0) left -= it.skip(left).takeIf { n -> n > 0 } ?: break
                input = it
            }
        }
        val n = stream.read(buffer, offset, length)
        return if (n < 0) C.RESULT_END_OF_INPUT else n
    }

    override fun getUri(): Uri? = if (subtitle != null) uri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (subtitle != null) emptyMap() else upstream.responseHeaders

    override fun close() {
        if (subtitle == null) return upstream.close()
        runCatching { input?.close() }
        input = null
        subtitle = null
        uri = null
    }

    class Factory(private val upstream: DataSource.Factory, private val subtitles: Map<String, Subtitle>) : DataSource.Factory {
        override fun createDataSource(): DataSource = PendingSubtitleDataSource(upstream.createDataSource(), subtitles)
    }

    companion object {
        const val SCHEME = "arisa-subtitle"

        fun uri(fid: String): Uri = Uri.parse("$SCHEME://$fid")
    }
}
