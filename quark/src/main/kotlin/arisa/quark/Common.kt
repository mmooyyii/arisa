package arisa.quark

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 两套接口共用的文件模型. */
data class QuarkFile(
    val fid: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    /** 夸克的分类, 1 = 视频. */
    val category: Int,
    /** 毫秒时间戳. */
    val updatedAt: Long,
    /** 夸克生成的缩略图 (视频是一帧截图), 没有时为 null. */
    val thumbnail: String? = null,
)

/** 取到的播放/下载地址, 请求时要带上 [headers]. */
data class QuarkLink(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val size: Long? = null,
)

open class QuarkException(message: String) : Exception(message)

/** 登录态失效, 需要重新登录. */
class QuarkAuthException(message: String) : QuarkException(message)

internal val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

val defaultHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

internal fun JsonObject.intField(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: 0

internal fun JsonObject.stringField(name: String): String =
    this[name]?.jsonPrimitive?.content ?: ""

internal fun hex(algorithm: String, bytes: ByteArray): String =
    MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

internal fun hex(algorithm: String, text: String): String = hex(algorithm, text.toByteArray())

