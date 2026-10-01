package com.fnalbum.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private companion object {
        const val MAX_CELLS = 12
        /** 6/9 宫格格子较大，用 m（1920×1080）保证清晰度 */
        const val THUMB_BIG = "m"
        /** 12 宫格格子小，s（427×240）已接近原生分辨率，翻页更跟手 */
        const val THUMB_SMALL = "s"
    }

    private lateinit var prefs: Prefs
    private lateinit var grid: PhotoGridLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvAlbumCount: TextView
    private lateinit var tvAccount: TextView
    private lateinit var tvHint: TextView
    private lateinit var loadingBox: View
    private lateinit var tvLoading: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val cells = ArrayList<View>(MAX_CELLS)

    private var pageSize = 6
    private var pageStart = 0
    private var uiLocked = false
    private var bootstrapped = false
    private var lastGridW = -1
    private var lastGridH = -1

    // ------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()

        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid)
        tvTitle = findViewById(R.id.tvTitle)
        tvAlbumCount = findViewById(R.id.tvAlbumCount)
        tvAccount = findViewById(R.id.tvAccount)
        tvHint = findViewById(R.id.tvHint)
        loadingBox = findViewById(R.id.loadingBox)
        tvLoading = findViewById(R.id.tvLoading)

        buildCells()
        applyStyle()

        // 尺寸变化后重新绑定，保证图片按格子大小解码
        grid.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            val w = r - l
            val h = b - t
            if (w != or - ol || h != ot - ob) {
                if (w != lastGridW || h != lastGridH) {
                    lastGridW = w
                    lastGridH = h
                    render()
                }
            }
        }

        Repo.albumId = prefs.albumId
        pageSize = pageSizeOf(prefs.gridStyle)
        updateHeader()

        if (!bootstrapped) {
            bootstrapped = true
            scope.launch { bootstrap() }
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        // 从全屏页返回时同步光标位置
        if (Repo.photos.isNotEmpty()) {
            val target = Repo.index.coerceIn(0, Repo.photos.size - 1)
            val newStart = pageStartFor(target)
            if (newStart != pageStart) {
                pageStart = newStart
                render()
            } else {
                updateSelection()
                updateHeader()
            }
        }
    }

    override fun onDestroy() {
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

    // -------------------------------------------------------------- 网格

    private fun buildCells() {
        cells.clear()
        val inflater = LayoutInflater.from(this)
        repeat(MAX_CELLS) {
            val cell = inflater.inflate(R.layout.item_cell, grid, false)
            cell.visibility = View.GONE
            grid.addView(cell)
            cells.add(cell)
        }
    }

    private fun pageSizeOf(style: Int): Int = when (style) {
        9 -> 9
        12 -> 12
        else -> 6
    }

    private fun applyStyle() {
        val style = prefs.gridStyle
        val (c, r) = when (style) {
            9 -> 3 to 3
            12 -> 4 to 3
            else -> 3 to 2
        }
        grid.cols = c
        grid.rows = r
        pageSize = c * r
        // 档位随宫格变化，清掉绑定标记让所有格子按新尺寸重新取图
        cells.forEach { it.findViewById<ImageView>(R.id.imgThumb).tag = null }
        tvHint.text = "← → 切换照片    ↑ ↓ 翻页    OK 全屏    MENU / M 菜单（$style 宫格）"
    }

    private fun pageStartFor(i: Int): Int = if (pageSize <= 0) 0 else (i / pageSize) * pageSize

    private fun render() {
        val cw = grid.cellWidth()
        val ch = grid.cellHeight()
        if (cw <= 0 || ch <= 0) {
            grid.post { if (!isFinishing && !isDestroyed) render() }
            return
        }

        for (i in 0 until MAX_CELLS) {
            val cell = cells[i]
            if (i >= pageSize) {
                cell.visibility = View.GONE
                continue
            }
            val photoIndex = pageStart + i
            if (photoIndex < Repo.photos.size) {
                cell.visibility = View.VISIBLE
                bindCell(cell, Repo.photos[photoIndex], cw, ch)
                cell.isSelected = photoIndex == Repo.index
            } else {
                cell.visibility = View.INVISIBLE
                cell.findViewById<ImageView>(R.id.imgThumb).setImageDrawable(null)
                cell.findViewById<ImageView>(R.id.imgThumb).tag = null
                cell.isSelected = false
            }
        }
        updateHeader()
    }

    private fun bindCell(cell: View, photo: Photo, cw: Int, ch: Int) {
        val img = cell.findViewById<ImageView>(R.id.imgThumb)
        val badgeBox = cell.findViewById<View>(R.id.badgeBox)
        val imgBadge = cell.findViewById<ImageView>(R.id.imgBadge)
        val tvBadge = cell.findViewById<TextView>(R.id.tvBadge)
        val label = cell.findViewById<TextView>(R.id.tvCellDate)

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

        label.visibility = View.VISIBLE
        label.text = photo.dateLabel

        if (img.tag == photo.id) return
        img.tag = photo.id
        val size = if (prefs.gridStyle == 12) THUMB_SMALL else THUMB_BIG
        img.load(Api.thumbUrl(photo, size)) {
            size(cw, ch)
            crossfade(150)
        }
    }

    private fun updateSelection() {
        for (i in 0 until pageSize) {
            val photoIndex = pageStart + i
            if (i < cells.size) cells[i].isSelected = photoIndex == Repo.index
        }
    }

    private fun updateHeader() {
        tvTitle.text = prefs.albumName
        val page = if (pageSize <= 0) 1 else (Repo.index / pageSize) + 1
        val total = Repo.photos.size
        tvAlbumCount.text = if (total == 0) "" else "第 $page 页 · 已加载 $total 张"
        val acc = Api.account
        tvAccount.text = if (acc != null) "${acc.user}@${acc.host}:${acc.port}" else ""
    }

    // ------------------------------------------------------------ 数据加载

    private suspend fun bootstrap() {
        val saved = prefs.currentAccount()
        if (saved == null) {
            showLoading(false)
            openLoginDialog(cancelable = false, initial = null)
            return
        }
        loginAndLoad(saved)
    }

    private fun loginAndLoad(acc: Account) {
        showLoading(true, "正在登录 ${acc.host}:${acc.port} …")
        scope.launch {
            try {
                val logged = withContext(Dispatchers.IO) { Api.login(acc) }
                prefs.saveAccount(logged)
                prefs.currentKey = logged.key
                hideLoading()
                openAlbum(prefs.albumId, prefs.albumName)
            } catch (e: Exception) {
                hideLoading()
                openLoginDialog(cancelable = false, initial = acc, error = e.message)
            }
        }
    }

    private fun openAlbum(albumId: Int, albumName: String) {
        prefs.albumId = albumId
        prefs.albumName = albumName
        Repo.reset(albumId, albumName)
        pageStart = 0
        updateHeader()
        showLoading(true, "正在加载照片…")

        scope.launch {
            try {
                Repo.ensureLoaded(pageSize - 1)
                hideLoading()
                render()
                if (Repo.photos.isEmpty()) {
                    tvHint.text = "这个相册里暂时没有照片    MENU 换个相册"
                } else {
                    applyStyle()
                    preloadAround()
                }
            } catch (e: FnAuthException) {
                onAuthLost(e)
            } catch (e: Exception) {
                hideLoading()
                tvHint.text = "加载失败：${e.message}    MENU 菜单"
            }
        }
    }

    /** 预取下一页，翻页时更顺滑 */
    private fun preloadAround() {
        val need = pageStart + pageSize * 2
        if (Repo.photos.size > need || !Repo.hasMore) return
        scope.launch {
            try {
                Repo.ensureLoaded(need)
                updateHeader()
            } catch (e: Exception) {
                // 预取失败不影响当前浏览
            }
        }
    }

    private fun onAuthLost(e: Exception) {
        val acc = prefs.currentAccount()
        if (acc == null) {
            hideLoading()
            openLoginDialog(cancelable = false, initial = null, error = e.message)
            return
        }
        showLoading(true, "登录已过期，正在重新登录…")
        scope.launch {
            try {
                val logged = withContext(Dispatchers.IO) { Api.login(acc) }
                prefs.saveAccount(logged)
                prefs.currentKey = logged.key
                hideLoading()
                openAlbum(prefs.albumId, prefs.albumName)
            } catch (t: Exception) {
                hideLoading()
                openLoginDialog(cancelable = false, initial = acc, error = t.message)
            }
        }
    }

    private fun reload() {
        openAlbum(prefs.albumId, prefs.albumName)
    }

    // ------------------------------------------------------------ 遥控器

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (uiLocked) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveBy(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveBy(1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { moveBy(-pageSize); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveBy(pageSize); return true }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> { openViewer(); return true }

            // 菜单键；M 键等价，方便用键盘测试
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_M -> { showMenu(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveBy(delta: Int) {
        if (Repo.photos.isEmpty()) return
        val target = (Repo.index + delta).coerceIn(0, Repo.photos.size - 1)
        if (target == Repo.index) return
        Repo.index = target

        val newStart = pageStartFor(target)
        if (newStart != pageStart) {
            pageStart = newStart
            renderIfReady()
            if (Repo.photos.size <= pageStart + pageSize) {
                showLoading(true, "正在加载更多…")
                scope.launch {
                    try {
                        Repo.ensureLoaded(pageStart + pageSize - 1)
                    } catch (e: FnAuthException) {
                        onAuthLost(e)
                    } catch (e: Exception) {
                        // 忽略，保留已有数据
                    }
                    hideLoading()
                    render()
                    preloadAround()
                }
            } else {
                preloadAround()
            }
        } else {
            updateSelection()
            updateHeader()
        }
    }

    private fun renderIfReady() {
        if (grid.cellWidth() > 0) render() else grid.post { render() }
    }

    private fun openViewer() {
        if (Repo.photos.isEmpty()) return
        startActivity(Intent(this, ViewerActivity::class.java))
    }

    // -------------------------------------------------------------- 菜单

    private fun showMenu() {
        if (uiLocked) return
        uiLocked = true
        val current = prefs.currentAccount()
        val dialog = TvListDialog(this)
            .title("菜单")
            .subtitle("${prefs.albumName}    ${current?.displayName ?: "未登录"}")
            .item("切换相册", prefs.albumName) { showAlbumPicker() }
            .item("六宫格（3×2）", checked = prefs.gridStyle == 6) { switchStyle(6) }
            .item("九宫格（3×3）", checked = prefs.gridStyle == 9) { switchStyle(9) }
            .item("十二宫格（4×3）", checked = prefs.gridStyle == 12) { switchStyle(12) }
            .item("切换账号", current?.user ?: "") { showAccountPicker() }
            .item("新增账号") { openLoginDialog(cancelable = true, initial = null) }
            .item("刷新当前相册") { reload() }
        dialog.onDismiss { uiLocked = false }
        dialog.show()
    }

    private fun switchStyle(style: Int) {
        prefs.gridStyle = style
        applyStyle()
        pageStart = pageStartFor(Repo.index)
        renderIfReady()
    }

    private fun showAlbumPicker() {
        uiLocked = true
        showLoading(true, "正在获取相册列表…")
        scope.launch {
            try {
                val albums = withContext(Dispatchers.IO) { Api.getAlbums() }
                hideLoading()
                val dialog = TvListDialog(this@MainActivity)
                    .title("选择相册")
                    .subtitle("共 ${albums.size} 个相册")
                    .item("全部照片", checked = prefs.albumId == 0) {
                        openAlbum(0, "全部照片")
                    }
                albums.forEach { a ->
                    dialog.item(
                        "${a.albumName}（${a.total}）",
                        checked = prefs.albumId == a.albumId
                    ) { openAlbum(a.albumId, a.albumName) }
                }
                dialog.onDismiss { uiLocked = false }
                dialog.show()
            } catch (e: FnAuthException) {
                uiLocked = false
                onAuthLost(e)
            } catch (e: Exception) {
                hideLoading()
                uiLocked = false
                tvHint.text = "获取相册失败：${e.message}"
            }
        }
    }

    private fun showAccountPicker() {
        uiLocked = true
        val list = prefs.accounts()
        val dialog = TvListDialog(this)
            .title("切换账号")
            .subtitle("共 ${list.size} 个账号")
        list.forEach { acc ->
            dialog.item(
                acc.displayName,
                checked = acc.key == prefs.currentKey
            ) {
                prefs.currentKey = acc.key
                loginAndLoad(acc)
            }
        }
        dialog.item("＋ 新增账号") { openLoginDialog(cancelable = true, initial = null) }
        dialog.item("↻ 重新登录当前账号") { prefs.currentAccount()?.let { loginAndLoad(it) } }
        if (list.size > 1) {
            val nonCurrent = list.filter { it.key != prefs.currentKey }
            nonCurrent.forEach { acc ->
                dialog.item("删除 ${acc.user}@${acc.host}") {
                    prefs.deleteAccount(acc.key)
                }
            }
        }
        dialog.onDismiss { uiLocked = false }
        dialog.show()
    }

    private fun openLoginDialog(cancelable: Boolean, initial: Account?, error: String? = null) {
        uiLocked = true
        val dialog = LoginDialog(
            context = this,
            cancelable = cancelable,
            initial = initial,
            initialError = error
        ) { account ->
            uiLocked = false
            prefs.saveAccount(account)
            prefs.currentKey = account.key
            openAlbum(prefs.albumId, prefs.albumName)
        }
        dialog.show()
        if (cancelable) {
            dialog.onDismissCallback { uiLocked = false }
        }
    }

    // ------------------------------------------------------------ 加载提示

    private fun showLoading(show: Boolean, text: String = "正在加载…") {
        loadingBox.visibility = if (show) View.VISIBLE else View.GONE
        tvLoading.text = text
    }

    private fun hideLoading() {
        loadingBox.visibility = View.GONE
    }
}
