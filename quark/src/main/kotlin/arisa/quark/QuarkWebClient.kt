package arisa.quark

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 夸克网页端接口 (Cookie 登录): 列目录、取直链、上传、删除、建目录.
 * 参考 OpenList drivers/quark_uc.
 */
class QuarkWebClient(
    private var cookie: String,
    /** 服务端下发新的 __puus 时回调, 调用方负责持久化. */
    private val onCookieChange: (String) -> Unit = {},
    private val http: OkHttpClient = defaultHttpClient,
) {
    val currentCookie: String get() = cookie

    fun member(): JsonObject = request("/member", "GET", mapOf(
        "fetch_subscribe" to "false", "_ch" to "home", "fetch_identity" to "false",
    ))

    fun list(parentFid: String = ROOT): List<QuarkFile> {
        val files = mutableListOf<QuarkFile>()
        val size = 100
        var page = 1
        while (true) {
            val obj = request("/file/sort", "GET", mapOf(
                "pdir_fid" to parentFid,
                "_page" to page.toString(),
                "_size" to size.toString(),
                "_fetch_total" to "1",
                "fetch_all_file" to "1",
                "fetch_risk_file_name" to "1",
                "_sort" to "file_type:asc,file_name:asc",
            ))
            val resp = json.decodeFromJsonElement(SortResp.serializer(), obj)
            resp.data.list.mapTo(files) {
                it.toQuarkFile()
            }
            if (resp.data.list.isEmpty() || page * size >= resp.metadata.total) break
            page++
        }
        return files
    }

    /**
     * 网页版首页的 "最近": 每次转存、上传、播放各一条, 新的在前.
     * 用来发现网盘变化, 比遍历目录便宜得多.
     */
    fun recent(size: Int = 50): List<RecentRecord> {
        val obj = request("/behavior/recent/list", "GET", mapOf("item_size" to size.toString()))
        val resp = json.decodeFromJsonElement(RecentResp.serializer(), obj)
        return resp.data.list.map { r -> RecentRecord(r.source, r.maxTm, r.files.map { it.toQuarkFile() }) }
    }

    /** 回收站, 最近删除的在前. 「最近」里看不到删除, 靠这个发现. */
    fun recycled(size: Int = 50): List<RecycledFile> {
        val obj = request("/file/recycle/list", "GET", mapOf("_page" to "1", "_size" to size.toString()))
        val resp = json.decodeFromJsonElement(RecycleResp.serializer(), obj)
        return resp.data.list.map { RecycledFile(it.fid, unescapeHtml(it.fileName), it.fileType == 0, it.pdirFid?.ifEmpty { null }, it.moveRecycleAt) }
    }

    private fun WebFile.toQuarkFile() =
        QuarkFile(fid, unescapeHtml(fileName), !file, size, category, updatedAt, thumbnail?.ifEmpty { null }, pdirFid?.ifEmpty { null }, duration, videoWidth, videoHeight)

    /** 按 "/电影/沙丘" 这样的路径找文件或目录, 不存在返回 null. */
    fun resolve(path: String): QuarkFile? {
        var current = QuarkFile(ROOT, "", true, 0, 0, 0)
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            current = list(current.fid).firstOrNull { it.name == part } ?: return null
        }
        return current
    }

    /** 直链, 请求时要带 Cookie/Referer/User-Agent. */
    fun downloadLink(fid: String): QuarkLink {
        val body = buildJsonObject { put("fids", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(fid)) }) }
        val obj = request("/file/download", "POST", body = body)
        val resp = json.decodeFromJsonElement(DownResp.serializer(), obj)
        val item = resp.data.firstOrNull() ?: throw QuarkException("download: no data")
        return QuarkLink(
            item.downloadUrl,
            mapOf("Cookie" to cookie, "Referer" to REFERER, "User-Agent" to USER_AGENT),
            item.size,
        )
    }

    fun download(fid: String): ByteArray {
        val link = downloadLink(fid)
        val req = Request.Builder().url(link.url).apply { link.headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.newCall(req).execute().use {
            if (!it.isSuccessful) throw QuarkException("download ${it.code}")
            it.body!!.bytes()
        }
    }

    /** 夸克为视频生成的缩略图 (列目录时给的 thumbnail 地址), 要带 Cookie. */
    fun thumbnail(url: String): ByteArray {
        val req = Request.Builder().url(url)
            .header("Cookie", cookie).header("Referer", REFERER).header("User-Agent", USER_AGENT).build()
        return http.newCall(req).execute().use {
            if (!it.isSuccessful) throw QuarkException("缩略图 ${it.code}")
            it.body!!.bytes()
        }
    }

    fun mkdir(parentFid: String, name: String): String {
        val obj = request("/file", "POST", body = buildJsonObject {
            put("dir_init_lock", false)
            put("dir_path", "")
            put("file_name", name)
            put("pdir_fid", parentFid)
        })
        return obj["data"]?.jsonObject?.stringField("fid") ?: throw QuarkException("mkdir: no fid")
    }

    fun delete(fids: List<String>) {
        request("/file/delete", "POST", body = buildJsonObject {
            put("action_type", 1)
            put("exclude_fids", buildJsonArray {})
            put("filelist", buildJsonArray { fids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        })
    }

    fun rename(fid: String, newName: String) {
        request("/file/rename", "POST", body = buildJsonObject {
            put("fid", fid)
            put("file_name", newName)
        })
    }

    /**
     * 上传一个内存里的小文件 (NFO、索引、图片), 返回新文件的 fid.
     * 夸克不会覆盖同名文件, 需要覆盖时调用方先删旧的.
     */
    fun upload(parentFid: String, name: String, bytes: ByteArray, mimeType: String = guessMime(name)): String {
        val now = System.currentTimeMillis()
        val preObj = request("/file/upload/pre", "POST", body = buildJsonObject {
            put("ccp_hash_update", true)
            put("dir_name", "")
            put("file_name", name)
            put("format_type", mimeType)
            put("l_created_at", now)
            put("l_updated_at", now)
            put("pdir_fid", parentFid)
            put("size", bytes.size)
        })
        val pre = json.decodeFromJsonElement(UpPreResp.serializer(), preObj)

        // 先报 hash, 网盘里已有相同内容时直接秒传
        val hashObj = request("/file/update/hash", "POST", body = buildJsonObject {
            put("md5", hex("MD5", bytes))
            put("sha1", hex("SHA-1", bytes))
            put("task_id", pre.data.taskId)
        })
        val hash = json.decodeFromJsonElement(HashResp.serializer(), hashObj)
        if (hash.data.finish) return hash.data.fid.ifEmpty { pre.data.fid }

        val partSize = pre.metadata.partSize.takeIf { it > 0 } ?: (bytes.size.coerceAtLeast(1))
        val etags = mutableListOf<String>()
        var offset = 0
        var partNumber = 1
        do {
            val end = minOf(offset + partSize, bytes.size)
            etags += uploadPart(pre, mimeType, partNumber, bytes.copyOfRange(offset, end))
            offset = end
            partNumber++
        } while (offset < bytes.size)

        commit(pre, etags)
        request("/file/upload/finish", "POST", body = buildJsonObject {
            put("obj_key", pre.data.objKey)
            put("task_id", pre.data.taskId)
        })
        return pre.data.fid
    }

    private fun uploadPart(pre: UpPreResp, mimeType: String, partNumber: Int, part: ByteArray): String {
        val date = gmtDate()
        val authMeta = "PUT\n\n$mimeType\n$date\nx-oss-date:$date\nx-oss-user-agent:$OSS_UA\n" +
            "/${pre.data.bucket}/${pre.data.objKey}?partNumber=$partNumber&uploadId=${pre.data.uploadId}"
        val authKey = uploadAuth(pre, authMeta)
        val url = ossUrl(pre).toHttpUrl().newBuilder()
            .addQueryParameter("partNumber", partNumber.toString())
            .addQueryParameter("uploadId", pre.data.uploadId)
            .build()
        val req = Request.Builder().url(url)
            .put(part.toRequestBody(mimeType.toMediaType()))
            .header("Authorization", authKey)
            .header("Content-Type", mimeType)
            .header("Referer", "$REFERER/")
            .header("x-oss-date", date)
            .header("x-oss-user-agent", OSS_UA)
            .build()
        return http.newCall(req).execute().use {
            if (it.code != 200) throw QuarkException("upload part ${it.code}: ${it.body?.string()?.take(300)}")
            it.header("Etag") ?: throw QuarkException("upload part: no etag")
        }
    }

    private fun commit(pre: UpPreResp, etags: List<String>) {
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<CompleteMultipartUpload>\n")
            etags.forEachIndexed { i, etag ->
                append("<Part>\n<PartNumber>${i + 1}</PartNumber>\n<ETag>$etag</ETag>\n</Part>\n")
            }
            append("</CompleteMultipartUpload>")
        }
        val contentMd5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(xml.toByteArray()))
        val callback = Base64.getEncoder().encodeToString(json.encodeToString(Callback.serializer(), pre.data.callback).toByteArray())
        val date = gmtDate()
        val authMeta = "POST\n$contentMd5\napplication/xml\n$date\nx-oss-callback:$callback\nx-oss-date:$date\n" +
            "x-oss-user-agent:$OSS_UA\n/${pre.data.bucket}/${pre.data.objKey}?uploadId=${pre.data.uploadId}"
        val authKey = uploadAuth(pre, authMeta)
        val url = ossUrl(pre).toHttpUrl().newBuilder().addQueryParameter("uploadId", pre.data.uploadId).build()
        val req = Request.Builder().url(url)
            // 用字节数组: 字符串版本会给 Content-Type 加上 "; charset=utf-8", 和签名里的对不上
            .post(xml.toByteArray().toRequestBody("application/xml".toMediaType()))
            .header("Authorization", authKey)
            .header("Content-MD5", contentMd5)
            .header("Content-Type", "application/xml")
            .header("Referer", "$REFERER/")
            .header("x-oss-callback", callback)
            .header("x-oss-date", date)
            .header("x-oss-user-agent", OSS_UA)
            .build()
        http.newCall(req).execute().use {
            if (it.code != 200) throw QuarkException("upload commit ${it.code}: ${it.body?.string()?.take(300)}")
        }
    }

    private fun uploadAuth(pre: UpPreResp, authMeta: String): String {
        val obj = request("/file/upload/auth", "POST", body = buildJsonObject {
            put("auth_info", pre.data.authInfo)
            put("auth_meta", authMeta)
            put("task_id", pre.data.taskId)
        })
        return obj["data"]?.jsonObject?.stringField("auth_key")?.ifEmpty { null }
            ?: throw QuarkException("upload auth: no key")
    }

    /** upload_url 形如 http://xxx, 拼成 https://bucket.xxx/objKey */
    private fun ossUrl(pre: UpPreResp): String =
        "https://${pre.data.bucket}.${pre.data.uploadUrl.substringAfter("://")}/${pre.data.objKey}"

    private fun request(
        path: String,
        method: String,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
    ): JsonObject {
        val url = (API + path).toHttpUrl().newBuilder().apply {
            addQueryParameter("pr", "ucpro")
            addQueryParameter("fr", "pc")
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url)
            .header("Cookie", cookie)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", REFERER)
            .header("User-Agent", USER_AGENT)
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { resp ->
            updateCookie(resp)
            resp.body?.string() ?: ""
        }
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw QuarkException("$path: bad response: ${text.take(200)}") }
        val status = obj.intField("status")
        val code = obj.intField("code")
        val message = obj.stringField("message")
        if (status == 401 || "login" in message.lowercase() || "登录" in message) {
            throw QuarkAuthException("$path: 登录失效 status=$status code=$code $message")
        }
        if (status >= 400 || code != 0) throw QuarkException("$path: status=$status code=$code $message")
        return obj
    }

    /** 夸克会滚动下发 __puus, 不更新的话 cookie 过一阵就失效. */
    private fun updateCookie(resp: Response) {
        val puus = resp.headers("Set-Cookie")
            .map { it.substringBefore(';') }
            .firstOrNull { it.startsWith("__puus=") } ?: return
        val parts = cookie.split(';').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("__puus=") }
        val updated = (parts + puus).joinToString("; ")
        if (updated != cookie) {
            cookie = updated
            onCookieChange(cookie)
        }
    }

    private fun gmtDate(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date())

    private fun unescapeHtml(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")

    private fun guessMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "nfo", "xml" -> "application/xml"
        "json" -> "application/json"
        "gz" -> "application/gzip"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    @Serializable
    private data class SortResp(val data: SortData = SortData(), val metadata: SortMeta = SortMeta())

    @Serializable
    private data class SortData(val list: List<WebFile> = emptyList())

    @Serializable
    private data class SortMeta(@SerialName("_total") val total: Int = 0)

    @Serializable
    private data class WebFile(
        val fid: String,
        @SerialName("file_name") val fileName: String = "",
        val category: Int = 0,
        val size: Long = 0,
        val file: Boolean = true,
        @SerialName("updated_at") val updatedAt: Long = 0,
        val thumbnail: String? = null,
        @SerialName("pdir_fid") val pdirFid: String? = null,
        val duration: Int = 0,
        @SerialName("video_width") val videoWidth: Int = 0,
        @SerialName("video_height") val videoHeight: Int = 0,
    )

    @Serializable
    private data class RecentResp(val data: RecentData = RecentData())

    @Serializable
    private data class RecentData(val list: List<RecentItem> = emptyList())

    @Serializable
    private data class RecentItem(
        val source: String = "",
        @SerialName("max_tm") val maxTm: Long = 0,
        val files: List<WebFile> = emptyList(),
    )

    @Serializable
    private data class RecycleResp(val data: RecycleData = RecycleData())

    @Serializable
    private data class RecycleData(val list: List<RecycleItem> = emptyList())

    @Serializable
    private data class RecycleItem(
        val fid: String,
        @SerialName("file_name") val fileName: String = "",
        @SerialName("file_type") val fileType: Int = 1,
        @SerialName("pdir_fid") val pdirFid: String? = null,
        @SerialName("move_recycle_at") val moveRecycleAt: Long = 0,
    )

    @Serializable
    private data class DownResp(val data: List<DownItem> = emptyList())

    @Serializable
    private data class DownItem(
        @SerialName("download_url") val downloadUrl: String = "",
        val size: Long? = null,
    )

    @Serializable
    private data class UpPreResp(val data: UpPreData, val metadata: UpPreMeta = UpPreMeta())

    @Serializable
    private data class UpPreData(
        @SerialName("task_id") val taskId: String = "",
        @SerialName("upload_id") val uploadId: String = "",
        @SerialName("obj_key") val objKey: String = "",
        @SerialName("upload_url") val uploadUrl: String = "",
        val fid: String = "",
        val bucket: String = "",
        val callback: Callback = Callback(),
        @SerialName("auth_info") val authInfo: String = "",
    )

    @Serializable
    private data class Callback(
        val callbackUrl: String = "",
        val callbackBody: String = "",
    )

    @Serializable
    private data class UpPreMeta(@SerialName("part_size") val partSize: Int = 0)

    @Serializable
    private data class HashResp(val data: HashData = HashData())

    @Serializable
    private data class HashData(val finish: Boolean = false, val fid: String = "")

    companion object {
        const val ROOT = "0"
        private const val API = "https://drive.quark.cn/1/clouddrive"
        private const val REFERER = "https://pan.quark.cn"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
        private const val OSS_UA = "aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit"
    }
}
