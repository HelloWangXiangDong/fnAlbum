package com.fnalbum.tv

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 全屏查看。
 *
 * 播放策略（"点一次播放一次"，不自动重播）：
 * - 视频：进入即自动播放一次；按 OK 再播一次
 * - 动图（Live Photo）：进入时只显示静态图；按 OK 播放一次
 * - 播放结束回到静态图，不会循环
 */
class ViewerActivity : Activity() {

    private companion object {
        const val VIEW_SIZE = "m"
        const val CHROME_TIMEOUT = 3200L
    }

    private lateinit var img: ImageView
    private lateinit var video: VideoView
    private lateinit var progress: ProgressBar
    private lateinit var infoBar: View
    private lateinit var badgeBox: LinearLayout
    private lateinit var imgBadge: ImageView
    private lateinit var tvBadge: TextView
    private lateinit var tvFile: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvHint: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val hideChrome = Runnable { setChromeAlpha(0f) }

    private var boundId = -1L
    private var loadingMore = false
    private var playing = false

    /** 播放失败过的条目：避免自动播放时反复重试打转 */
    private var failedId = -1L

    /** 当前条目是否需要显示右上角媒体角标 */
    private var badgeVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()
        setContentView(R.layout.activity_viewer)

        img = findViewById(R.id.imgFull)
        video = findViewById(R.id.video)
        progress = findViewById(R.id.progress)
        infoBar = findViewById(R.id.infoBar)
        badgeBox = findViewById(R.id.viewerBadge)
        imgBadge = findViewById(R.id.imgViewerBadge)
        tvBadge = findViewById(R.id.tvViewerBadge)
        tvFile = findViewById(R.id.tvFile)
        tvMeta = findViewById(R.id.tvMeta)
        tvHint = findViewById(R.id.tvViewerHint)

        if (Repo.photos.isEmpty()) {
            finish()
            return
        }
        render()
    }

    override fun onPause() {
        // 离开页面时立刻停止播放，避免声音继续
        stopPlayback()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideChrome)
        stopPlayback()
        scope.cancel()
        super.onDestroy()
    }

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun currentPhoto(): Photo? = Repo.photos.getOrNull(Repo.index)

    // -------------------------------------------------------------- 渲染

    private fun render() {
        val photos = Repo.photos
        if (photos.isEmpty()) {
            finish()
            return
        }
        Repo.index = Repo.index.coerceIn(0, photos.size - 1)
        val photo = photos[Repo.index]

        val changed = photo.id != boundId
        if (changed) {
            // 换图先停掉上一张的播放
            stopPlayback()
            boundId = photo.id
            progress.visibility = View.VISIBLE
            img.visibility = View.VISIBLE
            img.load(Api.thumbUrl(photo, VIEW_SIZE)) {
                size(screenW(), screenH())
                crossfade(180)
                listener(
                    onSuccess = { _, _ -> if (!playing) progress.visibility = View.GONE },
                    onError = { _, _ -> if (!playing) progress.visibility = View.GONE }
                )
            }
        }

        updateBadge(photo)
        tvFile.text = photo.fileName.ifEmpty { "IMG_${photo.id}" }
        tvMeta.text = buildString {
            append("${photo.width}×${photo.height}")
            append("    ${photo.dateLabel} ${photo.timeLabel}")
            append("    ${Repo.index + 1} / ${photos.size}")
            when (photo.mediaKind) {
                MediaKind.VIDEO -> append("    ▶ 视频")
                MediaKind.LIVE -> append("    ◉ 动图")
                MediaKind.NONE -> Unit
            }
        }
        tvHint.text = hintFor(photo)

        showChrome()
        prefetchNeighbours()

        // 视频：进入即自动播放一次（动图不动，等用户按 OK）
        if (changed && photo.isVideo && photo.id != failedId) playOnce()
    }

    private fun updateBadge(photo: Photo) {
        badgeVisible = photo.mediaKind != MediaKind.NONE
        when (photo.mediaKind) {
            MediaKind.VIDEO -> {
                badgeBox.visibility = View.VISIBLE
                imgBadge.setImageResource(R.drawable.ic_badge_video)
                tvBadge.text = "视频"
            }

            MediaKind.LIVE -> {
                badgeBox.visibility = View.VISIBLE
                imgBadge.setImageResource(R.drawable.ic_badge_live)
                tvBadge.text = "动图"
            }

            MediaKind.NONE -> badgeBox.visibility = View.GONE
        }
    }

    private fun hintFor(photo: Photo): String = when (photo.mediaKind) {
        MediaKind.VIDEO -> "← → 上一张 / 下一张     OK 再播一次     BACK 返回"
        MediaKind.LIVE -> "← → 上一张 / 下一张     OK 播放动图     BACK 返回"
        MediaKind.NONE -> "← → 上一张 / 下一张     OK 信息栏     BACK 返回"
    }

    // -------------------------------------------------------------- 播放

    /**
     * 播放一次。不循环，播完自动回到静态图。
     * 视频与动图共用同一条媒体流（/api/v1/stream/v/{id}）。
     */
    private fun playOnce() {
        val photo = currentPhoto() ?: return
        if (!photo.isPlayable) return

        val url = Api.mediaUrl(photo)
        if (url.isEmpty()) return

        // 每次重播都从零开始
        stopPlayback()

        playing = true
        progress.visibility = View.VISIBLE
        img.visibility = View.INVISIBLE
        video.visibility = View.VISIBLE

        video.setOnPreparedListener { mp ->
            mp.isLooping = false
            progress.visibility = View.GONE
            video.start()
        }
        video.setOnCompletionListener {
            // 播完即止，回到静态图
            stopPlayback()
            showChrome()
        }
        video.setOnErrorListener { _, what, extra ->
            failedId = photo.id
            stopPlayback()
            tvHint.text = "播放失败（错误 $what/$extra）"
            showChrome()
            true
        }

        runCatching { video.setVideoURI(Uri.parse(url), Api.mediaHeaders()) }
            .onFailure {
                failedId = photo.id
                stopPlayback()
                tvHint.text = "播放失败：${it.message}"
            }
    }

    private fun stopPlayback() {
        if (!playing && video.visibility != View.VISIBLE) return
        playing = false
        runCatching { video.stopPlayback() }
        video.visibility = View.GONE
        progress.visibility = View.GONE
        img.visibility = View.VISIBLE
    }

    // -------------------------------------------------------------- 信息栏

    private fun showChrome() {
        setChromeAlpha(1f)
        handler.removeCallbacks(hideChrome)
        handler.postDelayed(hideChrome, CHROME_TIMEOUT)
    }

    private fun setChromeAlpha(alpha: Float) {
        val show = alpha > 0f
        if (show) {
            infoBar.visibility = View.VISIBLE
            tvHint.visibility = View.VISIBLE
            if (badgeVisible) badgeBox.visibility = View.VISIBLE
        }
        infoBar.animate().alpha(alpha).setDuration(200).start()
        tvHint.animate().alpha(alpha).setDuration(200).start()
        if (badgeVisible) badgeBox.animate().alpha(alpha).setDuration(200).start()
        if (!show) {
            handler.postDelayed({
                if (infoBar.alpha == 0f) {
                    infoBar.visibility = View.INVISIBLE
                    tvHint.visibility = View.INVISIBLE
                    if (badgeVisible) badgeBox.visibility = View.GONE
                }
            }, 220)
        }
    }

    private fun prefetchNeighbours() {
        val loader = imageLoader
        for (delta in intArrayOf(1, -1, 2, -2)) {
            val i = Repo.index + delta
            if (i in Repo.photos.indices) {
                loader.enqueue(
                    ImageRequest.Builder(this)
                        .data(Api.thumbUrl(Repo.photos[i], VIEW_SIZE))
                        .size(screenW(), screenH())
                        .build()
                )
            }
        }
    }

    private fun screenW() = resources.displayMetrics.widthPixels

    private fun screenH() = resources.displayMetrics.heightPixels

    // ------------------------------------------------------------ 遥控器

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP -> {
                step(-1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN -> {
                step(1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                val photo = currentPhoto()
                if (photo != null && photo.isPlayable) {
                    // 手动点击允许重试（清掉上一次的失败标记）
                    failedId = -1L
                    playOnce()
                } else if (infoBar.visibility == View.VISIBLE && infoBar.alpha > 0.5f) {
                    handler.removeCallbacks(hideChrome)
                    setChromeAlpha(0f)
                } else {
                    showChrome()
                }
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                stopPlayback()
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun step(delta: Int) {
        val target = Repo.index + delta
        if (target < 0) return
        if (target >= Repo.photos.size) {
            if (!Repo.hasMore || loadingMore) return
            loadingMore = true
            scope.launch {
                try {
                    Repo.ensureLoaded(target)
                } catch (e: Exception) {
                    // 忽略，保持当前图片
                }
                loadingMore = false
                if (target < Repo.photos.size) {
                    Repo.index = target
                    render()
                }
            }
            return
        }
        Repo.index = target
        render()
    }
}
