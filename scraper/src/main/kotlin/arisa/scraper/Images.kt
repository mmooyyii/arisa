package arisa.scraper

import okhttp3.OkHttpClient
import okhttp3.Request
import arisa.library.tmdbImage
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.withSign

/** 主色调和 BlurHash, 按图片路径缓存在磁盘. */
class Images(private val http: OkHttpClient, private val cacheDir: File) {
    data class Analysis(val color: String?, val blurHash: String?)

    fun analyze(tmdbPath: String?, size: String = "w185"): Analysis {
        if (tmdbPath == null) return Analysis(null, null)
        val key = sha1("$size$tmdbPath")
        val cache = File(cacheDir, "$key.txt")
        if (cache.exists()) {
            val (c, b) = cache.readText().split('\n', limit = 2) + listOf("", "")
            return Analysis(c.ifEmpty { null }, b.ifEmpty { null })
        }
        val result = runCatching {
            val bytes = download(tmdbImage(tmdbPath, size)!!) ?: return Analysis(null, null)
            val img = ImageIO.read(bytes.inputStream()) ?: return Analysis(null, null)
            Analysis(dominantColor(img), BlurHash.encode(img, 4, 3))
        }.getOrElse { Analysis(null, null) }
        cacheDir.mkdirs()
        cache.writeText("${result.color ?: ""}\n${result.blurHash ?: ""}")
        return result
    }

    fun download(url: String): ByteArray? =
        http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }

    /**
     * 主色调: 颜色量化成 4bit 一档, 按 "出现次数 × 饱和度" 取最大的一档.
     * 太暗太亮的像素不参与, 结果再压暗一点, 方便做背景渐变.
     */
    internal fun dominantColor(img: BufferedImage): String {
        val buckets = HashMap<Int, DoubleArray>()
        val step = max(1, max(img.width, img.height) / 64)
        for (y in 0 until img.height step step) for (x in 0 until img.width step step) {
            val rgb = img.getRGB(x, y)
            val r = rgb shr 16 and 0xff
            val g = rgb shr 8 and 0xff
            val b = rgb and 0xff
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            if (mx < 40 || mn > 225) continue
            val sat = if (mx == 0) 0.0 else (mx - mn) / mx.toDouble()
            val key = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
            val acc = buckets.getOrPut(key) { DoubleArray(4) }
            val w = 0.3 + sat
            acc[0] += r * w; acc[1] += g * w; acc[2] += b * w; acc[3] += w
        }
        val best = buckets.values.maxByOrNull { it[3] } ?: return "#202020"
        val scale = 0.7
        val r = (best[0] / best[3] * scale).roundToInt()
        val g = (best[1] / best[3] * scale).roundToInt()
        val b = (best[2] / best[3] * scale).roundToInt()
        return "#%02x%02x%02x".format(r, g, b)
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** BlurHash 编码, 算法见 https://github.com/woltapp/blurhash */
object BlurHash {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    fun encode(img: BufferedImage, componentsX: Int, componentsY: Int): String {
        // 先缩小, 编码只需要低频信息
        val w = minOf(img.width, 64)
        val h = maxOf(1, img.height * w / img.width)
        val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply { drawImage(img, 0, 0, w, h, null); dispose() }

        val factors = Array(componentsX * componentsY) { DoubleArray(3) }
        for (j in 0 until componentsY) for (i in 0 until componentsX) {
            val norm = if (i == 0 && j == 0) 1.0 else 2.0
            val f = factors[j * componentsX + i]
            for (y in 0 until h) for (x in 0 until w) {
                val basis = norm * cos(PI * i * x / w) * cos(PI * j * y / h)
                val rgb = small.getRGB(x, y)
                f[0] += basis * srgbToLinear(rgb shr 16 and 0xff)
                f[1] += basis * srgbToLinear(rgb shr 8 and 0xff)
                f[2] += basis * srgbToLinear(rgb and 0xff)
            }
            val scale = 1.0 / (w * h)
            f[0] *= scale; f[1] *= scale; f[2] *= scale
        }

        val sb = StringBuilder()
        sb.append(base83((componentsX - 1) + (componentsY - 1) * 9, 1))
        val ac = factors.drop(1)
        val maxValue: Double
        if (ac.isNotEmpty()) {
            val actual = ac.maxOf { f -> f.maxOf { abs(it) } }
            val quantised = (actual * 166 - 0.5).toInt().coerceIn(0, 82)
            maxValue = (quantised + 1) / 166.0
            sb.append(base83(quantised, 1))
        } else {
            maxValue = 1.0
            sb.append(base83(0, 1))
        }
        val dc = factors[0]
        sb.append(base83((linearToSrgb(dc[0]) shl 16) + (linearToSrgb(dc[1]) shl 8) + linearToSrgb(dc[2]), 4))
        for (f in ac) {
            fun q(v: Double) = (signPow(v / maxValue, 0.5) * 9 + 9.5).toInt().coerceIn(0, 18)
            sb.append(base83(q(f[0]) * 19 * 19 + q(f[1]) * 19 + q(f[2]), 2))
        }
        return sb.toString()
    }

    private fun base83(value: Int, length: Int): String {
        val sb = StringBuilder()
        for (i in 1..length) {
            val digit = (value / 83.0.pow(length - i).toInt()) % 83
            sb.append(CHARS[digit])
        }
        return sb.toString()
    }

    private fun srgbToLinear(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSrgb(v: Double): Int {
        val c = v.coerceIn(0.0, 1.0)
        return if (c <= 0.0031308) (c * 12.92 * 255 + 0.5).toInt() else ((1.055 * c.pow(1 / 2.4) - 0.055) * 255 + 0.5).toInt()
    }

    private fun signPow(v: Double, exp: Double) = abs(v).pow(exp).withSign(v.sign)
}
