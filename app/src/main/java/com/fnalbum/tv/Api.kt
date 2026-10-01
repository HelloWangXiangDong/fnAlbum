package com.fnalbum.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 认证类错误：token 失效 / 账号密码错误 / 连不上服务器 */
class FnAuthException(message: String) : Exception(message)

/**
 * 飞牛相册 API 客户端。
 * 登录链路：WebSocket + RSA/AES 加密，取回 token；
 * 业务链路：HTTP + AccessToken 头 + authx 签名头。
 */
object Api {

    /** 全量照片查询用的超大时间范围 */
    private const val TIME_MIN = "1970:01:01 00:00:00"
    private const val TIME_MAX = "2099:12:31 23:59:59"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    @Volatile
    var account: Account? = null
        private set

    @Volatile
    private var token: String = ""

    fun isReady(): Boolean = account != null && token.isNotEmpty()

    /** 供图片加载器动态取用 */
    fun tokenForImages(): String = token

    private fun rsaLen(b64: String): Int = try {
        android.util.Base64.decode(b64, android.util.Base64.NO_WRAP).size
    } catch (t: Throwable) {
        -1
    }

    // ---------------------------------------------------------------- 登录

    /**
     * WebSocket 加密登录，成功后返回带 token 的账号副本。
     */
    suspend fun login(acc: Account): Account = withContext(Dispatchers.IO) {
        android.util.Log.i("FnAlbum", "login start ${acc.wsUrl()}")
        val result = withTimeout(25_000) { wsLogin(acc) }
        android.util.Log.i("FnAlbum", "login ok token=${result.take(8)}...")
        account = acc.copy(token = result)
        token = result
        acc.copy(token = result)
    }

    private suspend fun wsLogin(acc: Account): String = suspendCancellableCoroutine { cont ->
        val request = Request.Builder().url(acc.wsUrl()).build()
        var publicKeyReceived = false

        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                android.util.Log.i("FnAlbum", "ws open, send getRSAPub")
                webSocket.send("""{"req":"util.crypto.getRSAPub","reqid":"r1"}""")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.i("FnAlbum", "ws closed code=$code reason=$reason")
                if (cont.isActive) {
                    cont.resumeWithException(FnAuthException("连接被服务器关闭（code=$code）"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                android.util.Log.i("FnAlbum", "ws msg: ${text.take(160)}")
                try {
                    val obj = JSONObject(text)

                    if (!publicKeyReceived && obj.has("pub")) {
                        publicKeyReceived = true
                        val pem = obj.getString("pub")
                        // 服务端 si 是 18 位整数的【字符串】，必须原样回传：
                        // 若按数字序列化，服务端按字符串字段解析会失败（errno 8192）。
                        val siRaw: Any = obj.opt("si") ?: 0
                        android.util.Log.i(
                            "FnAlbum",
                            "si=$siRaw (${siRaw.javaClass.simpleName}) keys=${obj.keys().asSequence().toList()}"
                        )

                        val payload = JSONObject().apply {
                            put("req", "user.login")
                            put("user", acc.user)
                            put("password", acc.password)
                            put("stay", true)
                            put("deviceType", "tv")
                            put("deviceName", "FnAlbumTV")
                            put("did", FnCrypto.randomString(24, FnCrypto.DID_CHARS))
                            put("si", siRaw)
                            put("reqid", "login-" + System.currentTimeMillis())
                        }
                        val (iv, rsa, aes) = FnCrypto.encryptLogin(payload.toString(), pem)
                        android.util.Log.i(
                            "FnAlbum",
                            "payload=$payload ivLen=${iv.length} rsaLen=${rsaLen(rsa)} rsaB64Len=${rsa.length} aesLen=${aes.length}"
                        )
                        val envelope = JSONObject().apply {
                            put("req", "encrypted")
                            put("iv", iv)
                            put("rsa", rsa)
                            put("aes", aes)
                        }
                        webSocket.send(envelope.toString())
                        return
                    }

                    if (obj.optString("result") == "succ" && obj.has("token")) {
                        val t = obj.getString("token")
                        runCatching { webSocket.close(1000, null) }
                        if (cont.isActive) cont.resume(t)
                        return
                    }

                    val errno = obj.optInt("errno", 0)
                    if (errno != 0) {
                        runCatching { webSocket.close(1000, null) }
                        val msg = when (errno) {
                            131072 -> "账号或密码错误"
                            8192 -> "服务器要求加密登录"
                            else -> "登录失败（errno=$errno）"
                        }
                        if (cont.isActive) cont.resumeWithException(FnAuthException(msg))
                    }
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 登录成功后会主动断开连接，此时 okhttp 会报 EOF，属正常现象，不必记 error
                if (cont.isActive) {
                    android.util.Log.e("FnAlbum", "ws failure: ${t.javaClass.simpleName} ${t.message}", t)
                    cont.resumeWithException(
                        FnAuthException("无法连接 ${acc.host}:${acc.port}（${t.message ?: "网络错误"}）")
                    )
                } else {
                    android.util.Log.i("FnAlbum", "ws closed after done: ${t.javaClass.simpleName}")
                }
            }
        }

        val socket = http.newWebSocket(request, listener)
        cont.invokeOnCancellation { runCatching { socket.cancel() } }
    }

    // ---------------------------------------------------------------- 请求

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val acc = account ?: throw FnAuthException("尚未登录")
            if (token.isEmpty()) throw FnAuthException("尚未登录")

            val signedPath = "/p$path"
            val authx = FnCrypto.buildAuthx("GET", signedPath, params, null)
            val query = FnCrypto.encodeQuery(params)
            val url = acc.baseUrl() + signedPath + if (query.isEmpty()) "" else "?$query"

            val request = Request.Builder()
                .url(url)
                .header("AccessToken", token)
                .header("authx", authx)
                .header("User-Agent", "FnAlbumTV/1.0")
                .header("Accept", "application/json")
                .build()

            val bodyText = http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) throw FnAuthException("服务器无响应（HTTP ${resp.code}）")
                body
            }

            val json = try {
                JSONObject(bodyText)
            } catch (t: Throwable) {
                throw FnAuthException("响应格式异常，请检查地址与端口")
            }

            when (val code = json.optInt("code", -1)) {
                0 -> json
                401, 5001 -> throw FnAuthException("登录状态已失效")
                5000 -> throw Exception("签名校验失败（code=5000）")
                else -> throw Exception("接口错误（code=$code ${json.optString("msg")}）")
            }
        }

    /** 解析相对流地址为绝对 URL */
    fun absoluteUrl(relative: String): String {
        val acc = account ?: return relative
        return if (relative.startsWith("http")) relative
        else acc.baseUrl() + if (relative.startsWith("/")) relative else "/p$relative"
    }

    /** 缩略图地址：size 取 xxs/xs/s/m/o */
    fun thumbUrl(photo: Photo, size: String): String {
        val acc = account ?: return ""
        return "${acc.baseUrl()}/p/api/v1/stream/p/t/${photo.id}/$size/${photo.uuid}"
    }

    /**
     * 可播放媒体流地址（视频 / 实况照片短片）。
     * 优先用服务端给的 videoUrl，缺省时按 id 兜底拼装。
     */
    fun mediaUrl(photo: Photo): String {
        val acc = account ?: return ""
        val rel = photo.videoUrl.ifEmpty { "/api/v1/stream/v/${photo.id}" }
        return when {
            rel.startsWith("http") -> rel
            rel.startsWith("/p/") -> acc.baseUrl() + rel
            rel.startsWith("/") -> acc.baseUrl() + "/p" + rel
            else -> acc.baseUrl() + "/p/" + rel
        }
    }

    /** 媒体流请求头：该接口只校验 AccessToken，不需要 authx 签名 */
    fun mediaHeaders(): Map<String, String> = mapOf(
        "AccessToken" to token,
        "User-Agent" to "FnAlbumTV/1.0"
    )

    // ------------------------------------------------------------- 业务接口

    suspend fun getStat(): JSONObject = get("/api/v1/user_photo/stat").optJSONObject("data") ?: JSONObject()

    suspend fun getAlbums(): List<Album> {
        val json = get(
            "/api/v1/album/list",
            mapOf(
                "sort_direction" to "asc",
                "sort_by" to "created_at",
                "offset" to "0",
                "limit" to "1000"
            )
        )
        val arr = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Album(
                albumId = o.optInt("albumId"),
                albumName = o.optString("albumName"),
                photoCount = o.optInt("photoCount"),
                videoCount = o.optInt("videoCount")
            )
        }
    }

    /** 全部照片（跨相册），按时间倒序分页 */
    suspend fun getAllPhotos(offset: Int, limit: Int): List<Photo> {
        val json = get(
            "/api/v1/gallery/getList",
            mapOf(
                "start_time" to TIME_MIN,
                "end_time" to TIME_MAX,
                "offset" to offset.toString(),
                "limit" to limit.toString()
            )
        )
        return parsePhotos(json.optJSONObject("data")?.optJSONArray("list"))
    }

    /** 指定相册内的照片 */
    suspend fun getAlbumPhotos(albumId: Int, offset: Int, limit: Int): List<Photo> {
        val json = get(
            "/api/v1/album/photos",
            mapOf(
                "album_id" to albumId.toString(),
                "sort_by" to "date_time",
                "sort_direction" to "desc",
                "offset" to offset.toString(),
                "limit" to limit.toString()
            )
        )
        return parsePhotos(json.optJSONObject("data")?.optJSONArray("list"))
    }

    private fun parsePhotos(arr: JSONArray?): List<Photo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val thumb = o.optJSONObject("additional")?.optJSONObject("thumbnail")
            val uuid = o.optString("photoUUID").ifEmpty {
                thumb?.optString("sUrl")
                    ?.substringAfterLast('/')
                    .orEmpty()
            }
            Photo(
                id = o.optLong("id"),
                category = o.optString("category", "photo"),
                fileType = o.optString("fileType"),
                fileName = o.optString("fileName"),
                dateTime = o.optString("dateTime"),
                width = o.optInt("width"),
                height = o.optInt("height"),
                uuid = uuid,
                ownerName = o.optString("ownerName"),
                // isLive=1 表示实况照片；videoUrl 是它的短片流地址（普通照片为空串）
                isLive = o.optInt("isLive", 0) == 1,
                videoUrl = thumb?.optString("videoUrl").orEmpty()
            )
        }
    }
}
