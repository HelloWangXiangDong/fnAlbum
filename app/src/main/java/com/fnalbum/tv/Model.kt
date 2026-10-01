package com.fnalbum.tv

/** 角标类型：决定网格与全屏页右上角显示什么图标 */
enum class MediaKind { NONE, VIDEO, LIVE }

/** 一张照片或视频 */
data class Photo(
    val id: Long,
    val category: String,
    val fileType: String,
    val fileName: String,
    val dateTime: String,
    val width: Int,
    val height: Int,
    val uuid: String,
    val ownerName: String,
    /** 服务端 isLive 标记：iPhone 实况照片 */
    val isLive: Boolean = false,
    /** 可播放的短片地址（视频与实况照片都有，普通照片为空） */
    val videoUrl: String = ""
) {
    val isVideo: Boolean get() = category.equals("video", true)

    /**
     * 实况照片：静态图 + 可播放短片。
     * 必须同时具备 isLive 标记与短片地址，否则拿不到画面，角标也不该出现。
     */
    val isLivePhoto: Boolean get() = isLive && videoUrl.isNotEmpty()

    /** 可播放媒体：视频，或短片已就绪的实况照片 */
    val isPlayable: Boolean get() = isVideo || isLivePhoto

    val mediaKind: MediaKind
        get() = when {
            isVideo -> MediaKind.VIDEO
            isLivePhoto -> MediaKind.LIVE
            else -> MediaKind.NONE
        }

    /** "2026:10:01 13:46:25" -> "2026-10-01" */
    val dateLabel: String
        get() = dateTime.take(10).replace(':', '-').trim()

    /** "2026:10:01 13:46:25" -> "13:46:25" */
    val timeLabel: String
        get() = if (dateTime.length >= 19) dateTime.substring(11) else ""
}

/** 相册 */
data class Album(
    val albumId: Int,
    val albumName: String,
    val photoCount: Int,
    val videoCount: Int
) {
    val total: Int get() = photoCount + videoCount
}

/** 一个已保存的账号（含登录后的 token） */
data class Account(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val ssl: Boolean = false,
    val token: String = "",
    val alias: String = ""
) {
    val key: String get() = "${if (ssl) "https" else "http"}://$host:$port/$user"

    val displayName: String
        get() = if (alias.isNotEmpty()) "$alias ($user@$host)" else "$user@$host:$port"

    fun baseUrl(): String = "${if (ssl) "https" else "http"}://$host:$port"

    fun wsUrl(): String = "${if (ssl) "wss" else "ws"}://$host:$port/websocket?type=main"
}
