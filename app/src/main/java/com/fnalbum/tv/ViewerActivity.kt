package com.fnalbum.tv

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
 *
 * 加载体验：准备阶段**不揭开播放画面**，静态图一直留在屏幕上、中间转一个 loading，
 * 等首帧真正渲染出来才切换过去，避免黑屏好几秒。
 *
 * 播放内核用 Media3/ExoPlayer，而不是系统 `VideoView`：动图短片是 MOV 容器 +
 * `lpcm`（未压缩 PCM）音轨，系统 MediaPlayer 会把这条音轨整条丢掉（画面正常、
 * 完全无声）；ExoPlayer 的 Mp4Extractor 认得这种抽样条目，能正常出声。
 */
class ViewerActivity : Activity() {

    private companion object {
        const val TAG = "FnAlbumTV"
        const val VIEW_SIZE = "m"
        const val CHROME_TIMEOUT = 3200L

        /** 准备阶段上限：本地网络下 20 秒还没就绪就放弃，不留一个永远转的 loading */
        const val PREPARE_TIMEOUT = 20_000L

        /** 首帧渲染回调不是所有片源都上报，用这个兜底计时保证静态图最终一定会被收掉 */
        const val REVEAL_FALLBACK_MS = 600L
    }

    private lateinit var img: ImageView
    private lateinit var video: SurfaceView
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
    private val revealFallback = Runnable { revealVideo() }
    private val prepareTimeout = Runnable { onPrepareTimeout() }

    private var boundId = -1L
    private var loadingMore = false
    private var playing = false

    /** 本次播放是否已经揭开画面（首帧渲染完成）。每播一次重置一次 */
    private var revealed = false

    /** 播放失败过的条目：避免自动播放时反复重试打转 */
    private var failedId = -1L

    /** 当前条目是否需要显示右上角媒体角标 */
    private var badgeVisible = false

    /** 当前播放器。每次播放都新建一个，播完/换图立刻 release */
    private var player: ExoPlayer? = null

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
     *
     * 关键点：**准备阶段不揭开画面**。加载过程中静态图一直留在屏幕上，只在中间转一个
     * loading；等首帧真正渲染出来才把静态图收掉。这样就不会先露出播放面的黑底、
     * 黑屏好几秒。静态图能盖住视频，靠的是布局里播放面排在静态图下面。
     */
    private fun playOnce() {
        val photo = currentPhoto() ?: return
        if (!photo.isPlayable) return

        val url = Api.mediaUrl(photo)
        if (url.isEmpty()) return

        // 每次重播都从零开始：先停掉上一次的播放器
        stopPlayback()

        playing = true
        revealed = false
        // 播放面必须先恢复成整屏：SurfaceView 不可见时不会创建 surface，
        // 一帧都不会被渲染出来。它在布局里位于静态图下方，所以看不见。
        progress.visibility = View.VISIBLE
        img.visibility = View.VISIBLE
        video.visibility = View.VISIBLE
        resetVideoSize()

        val p = newPlayer()
        player = p
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (playing && player === p) revealVideo()
            }

            // 裸 SurfaceView 会把解码帧硬拉满整个 surface（= 拉伸变形），
            // 必须自己按片源比例把播放面缩进来，多出来的地方留黑边
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (player !== p) return
                fitVideo(videoSize)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!playing || player !== p) return
                when (playbackState) {
                    // 兜底：万一首帧回调没上报，也别让 loading 一直转
                    Player.STATE_READY -> {
                        logAudioState(p)
                        handler.removeCallbacks(revealFallback)
                        handler.postDelayed(revealFallback, REVEAL_FALLBACK_MS)
                    }

                    // 播完即止，回到静态图，不循环
                    Player.STATE_ENDED -> {
                        stopPlayback()
                        showChrome()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== p) return
                failedId = photo.id
                Log.w(TAG, "播放失败：${error.errorCodeName}", error)
                stopPlayback()
                tvHint.text = "播放失败（${error.errorCodeName}）"
                showChrome()
            }
        })

        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        p.prepare()
        p.playWhenReady = true

        handler.removeCallbacks(prepareTimeout)
        handler.postDelayed(prepareTimeout, PREPARE_TIMEOUT)
    }

    /** 新建一个播放器。媒体流只校验 AccessToken，不需要 authx 签名 */
    private fun newPlayer(): ExoPlayer {
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(Api.mediaHeaders())
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                // 显式声明走系统媒体流，避免默认音频属性在某些盒子上不路由输出
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                setVideoSurfaceView(video)
            }
    }

    /** 播放面恢复成整屏（下次播放前重置上一支片源留下的尺寸） */
    private fun resetVideoSize() {
        val lp = video.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.CENTER
        video.layoutParams = lp
    }

    /**
     * 按片源比例把播放面缩进屏幕内，等比例居中，多出来的地方留黑边。
     *
     * 系统 `VideoView` 的 `onMeasure` 会自动做这件事，换成裸 `SurfaceView` 就没有了 ——
     * 不处理的话解码帧会被硬拉满整个 surface，画面横向/纵向拉伸变形。
     */
    private fun fitVideo(videoSize: VideoSize) {
        var vw = videoSize.width
        var vh = videoSize.height
        // 解码器没应用旋转的话，这里要自己把宽高换过来
        if (videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270) {
            val t = vw; vw = vh; vh = t
        }
        if (vw <= 0 || vh <= 0) return

        val videoAspect = vw * videoSize.pixelWidthHeightRatio / vh
        val screenW = screenW().toFloat()
        val screenH = screenH().toFloat()
        if (videoAspect <= 0f || screenW <= 0f || screenH <= 0f) return

        var w = screenW
        var h = screenH
        if (videoAspect > screenW / screenH) {
            h = screenW / videoAspect        // 片源更宽 → 上下留黑边
        } else {
            w = screenH * videoAspect        // 片源更高 → 左右留黑边
        }

        val lp = video.layoutParams as? FrameLayout.LayoutParams ?: return
        val tw = w.toInt()
        val th = h.toInt()
        if (lp.width == tw && lp.height == th) return
        lp.width = tw
        lp.height = th
        lp.gravity = Gravity.CENTER
        video.layoutParams = lp
    }

    /**
     * 播放器就绪后把整条音频链路的状态写进 logcat（**不上屏**），
     * 用来区分「没声音」是 App 侧还是设备侧：
     *
     *   adb logcat -s FnAlbumTV:I
     *
     * 重点看两处：系统媒体音量是不是 0 / 被静音，输出设备是不是你以为的那个（电视/耳机）。
     */
    private fun logAudioState(p: ExoPlayer) {
        val groups = p.currentTracks.groups
        val audioGroups = groups.count { it.type == C.TRACK_TYPE_AUDIO }
        val selected = groups.count { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }

        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val vol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
        val muted = am?.isStreamMute(AudioManager.STREAM_MUSIC) ?: false
        val outs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.joinToString("|") { "${it.type}:${it.productName}" } ?: "-"

        Log.i(
            TAG,
            "音频链路 音轨组=$audioGroups 已选=$selected player音量=${p.volume} " +
                "会话=${p.audioSessionId} 系统媒体音量=$vol/$maxVol 静音=$muted 输出设备=$outs"
        )
    }

    /** 首帧已经画出来了：这时才收掉静态图，让视频画面露出来（幂等） */
    private fun revealVideo() {
        if (!playing || revealed) return
        revealed = true
        handler.removeCallbacks(revealFallback)
        handler.removeCallbacks(prepareTimeout)
        progress.visibility = View.GONE
        img.visibility = View.INVISIBLE
    }

    /** 准备阶段卡太久：放弃播放，退回静态图，别让 loading 一直转 */
    private fun onPrepareTimeout() {
        if (!playing || revealed) return
        stopPlayback()
        tvHint.text = "加载超时，按 OK 重试"
        showChrome()
    }

    private fun stopPlayback() {
        handler.removeCallbacks(revealFallback)
        handler.removeCallbacks(prepareTimeout)
        revealed = false
        playing = false

        // release 会把挂在播放器上的监听一并断开，迟到的回调不会再触发
        val p = player
        player = null
        if (p != null) runCatching {
            p.stop()
            p.release()
        }

        // 先把静态图放回来，再收掉播放面，避免中间闪一帧黑
        img.visibility = View.VISIBLE
        video.visibility = View.GONE
        progress.visibility = View.GONE
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
