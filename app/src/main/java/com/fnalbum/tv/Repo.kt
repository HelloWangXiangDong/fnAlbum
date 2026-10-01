package com.fnalbum.tv

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 照片数据仓库。首页和全屏页共享同一份数据，避免在 Activity 之间传递大列表。
 */
object Repo {

    const val CHUNK = 300

    val photos = ArrayList<Photo>()

    var albumId = 0

    /** 当前光标所在的全局下标，两个页面共享 */
    var index = 0

    @Volatile
    var hasMore = true
        private set

    private val mutex = Mutex()
    private val knownIds = HashSet<Long>()

    fun reset(albumId: Int, albumName: String) {
        photos.clear()
        knownIds.clear()
        this.albumId = albumId
        index = 0
        hasMore = true
    }

    /** 保证下标 [upTo] 处的元素已加载，返回当前总数 */
    suspend fun ensureLoaded(upTo: Int): Int = mutex.withLock {
        while (hasMore && photos.size <= upTo) {
            val offset = photos.size
            val chunk = if (albumId <= 0) {
                Api.getAllPhotos(offset, CHUNK)
            } else {
                Api.getAlbumPhotos(albumId, offset, CHUNK)
            }
            if (chunk.isEmpty()) {
                hasMore = false
                break
            }
            // 防御重复（服务端排序不稳定时可能出现）
            var added = 0
            chunk.forEach { p -> if (knownIds.add(p.id)) { photos.add(p); added++ } }

            if (chunk.size < CHUNK || added == 0) hasMore = false
        }
        photos.size
    }
}
