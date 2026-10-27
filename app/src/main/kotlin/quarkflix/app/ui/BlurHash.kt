package quarkflix.app.ui

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.withSign

/** BlurHash 解码成小图, 作为大图加载前的占位. 算法见 https://github.com/woltapp/blurhash */
object BlurHashDecoder {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"
    private val cache = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 64
    }

    fun decode(hash: String?, width: Int = 32, height: Int = 20): Bitmap? {
        if (hash == null || hash.length < 6) return null
        synchronized(cache) { cache["$hash$width$height"]?.let { return it } }
        val bitmap = runCatching { decodeInternal(hash, width, height) }.getOrNull() ?: return null
        synchronized(cache) { cache["$hash$width$height"] = bitmap }
        return bitmap
    }

    private fun decodeInternal(hash: String, width: Int, height: Int): Bitmap {
        val sizeFlag = decode83(hash, 0, 1)
        val nx = sizeFlag % 9 + 1
        val ny = sizeFlag / 9 + 1
        require(hash.length == 4 + 2 * nx * ny)
        val maxAc = (decode83(hash, 1, 2) + 1) / 166f
        val colors = Array(nx * ny) { i ->
            if (i == 0) {
                val v = decode83(hash, 2, 6)
                floatArrayOf(srgbToLinear(v shr 16), srgbToLinear(v shr 8 and 255), srgbToLinear(v and 255))
            } else {
                val v = decode83(hash, 4 + i * 2, 6 + i * 2)
                floatArrayOf(
                    signPow(((v / (19 * 19)) - 9) / 9f) * maxAc,
                    signPow(((v / 19 % 19) - 9) / 9f) * maxAc,
                    signPow(((v % 19) - 9) / 9f) * maxAc,
                )
            }
        }
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var r = 0f; var g = 0f; var b = 0f
            for (j in 0 until ny) for (i in 0 until nx) {
                val basis = (cos(PI * x * i / width) * cos(PI * y * j / height)).toFloat()
                val c = colors[j * nx + i]
                r += c[0] * basis; g += c[1] * basis; b += c[2] * basis
            }
            pixels[y * width + x] = (0xff shl 24) or (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun decode83(s: String, from: Int, to: Int): Int {
        var v = 0
        for (i in from until to) v = v * 83 + CHARS.indexOf(s[i])
        return v
    }

    private fun srgbToLinear(v: Int): Float {
        val c = v / 255f
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(v: Float): Int {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) (c * 12.92f * 255 + 0.5f).toInt() else ((1.055f * c.pow(1 / 2.4f) - 0.055f) * 255 + 0.5f).toInt()
    }

    private fun signPow(v: Float) = (v * v).withSign(v)
}
