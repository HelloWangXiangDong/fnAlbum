package com.fnalbum.tv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun View.dropDefaultHighlight() {
    isFocusable = true
    isFocusableInTouchMode = true
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        defaultFocusHighlightEnabled = false
    }
    stateListAnimator = null
}

private fun Context.ime(): InputMethodManager =
    getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

/**
 * 遥控器友好的列表选择弹窗。上下键移动焦点，OK 触发。
 */
class TvListDialog(private val context: Context) {

    private val dialog = Dialog(context, R.style.DialogTheme)
    private var built = false
    private val items = ArrayList<Pair<View, () -> Unit>>()
    private var pendingDismissAction: (() -> Unit)? = null

    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var rowBox: LinearLayout

    fun title(text: String): TvListDialog {
        this.pendingTitle = text
        return this
    }

    fun subtitle(text: String): TvListDialog {
        this.pendingSubtitle = text
        return this
    }

    private var pendingTitle: String = ""
    private var pendingSubtitle: String = ""

    fun item(
        label: String,
        value: String = "",
        checked: Boolean = false,
        onClick: () -> Unit
    ): TvListDialog {
        ensureBuilt()
        val row = LayoutInflater.from(context).inflate(R.layout.item_row, rowBox, false)
        row.findViewById<TextView>(R.id.rowLabel).text = label
        row.findViewById<TextView>(R.id.rowMark).text =
            if (checked) "\u2713" + (if (value.isNotEmpty()) "  $value" else "")
            else value
        row.dropDefaultHighlight()
        row.onFocusChangeListener = View.OnFocusChangeListener { _, has ->
            row.isSelected = has
        }
        row.setOnClickListener {
            pendingDismissAction = onClick
            dialog.dismiss()
        }
        rowBox.addView(row)
        items.add(row to onClick)
        return this
    }

    private fun ensureBuilt() {
        if (built) return
        built = true
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_list, null)
        dialog.setContentView(root)
        titleView = root.findViewById(R.id.dlgTitle)
        subView = root.findViewById(R.id.dlgSub)
        rowBox = root.findViewById(R.id.rows)
        titleView.text = pendingTitle
        subView.text = pendingSubtitle
        subView.visibility = if (pendingSubtitle.isEmpty()) View.GONE else View.VISIBLE
    }

    fun show() {
        ensureBuilt()
        dialog.window?.let { w ->
            w.setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.setOnDismissListener {
            dismissCb?.invoke()
            val action = pendingDismissAction
            pendingDismissAction = null
            action?.invoke()
        }
        dialog.show()
        if (items.isEmpty()) dialog.dismiss()
        else items.first().first.post { items.first().first.requestFocus() }
    }

    private var dismissCb: (() -> Unit)? = null

    fun onDismiss(cb: () -> Unit): TvListDialog {
        dismissCb = cb
        return this
    }

    fun dismiss() = dialog.dismiss()
}

/**
 * 登录 / 新增账号弹窗。
 * 首次启动或当前账号无法登录时以不可取消方式弹出。
 */
class LoginDialog(
    private val context: Context,
    private val cancelable: Boolean,
    private val initial: Account? = null,
    private val initialError: String? = null,
    private val onSaved: (Account) -> Unit
) {

    private val dialog = Dialog(context, R.style.DialogTheme)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var edHost: EditText
    private lateinit var edPort: EditText
    private lateinit var edUser: EditText
    private lateinit var edPass: EditText
    private lateinit var tvErr: TextView
    private lateinit var qrBox: View
    private lateinit var qrDivider: View
    private lateinit var tvQrState: TextView
    private var qrServer: QrLoginServer? = null
    private var ssl = false
    private var testing = false

    fun show() {
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_login, null)
        dialog.setContentView(root)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(false)

        edHost = root.findViewById(R.id.edHost)
        edPort = root.findViewById(R.id.edPort)
        edUser = root.findViewById(R.id.edUser)
        edPass = root.findViewById(R.id.edPass)
        tvErr = root.findViewById(R.id.tvErr)
        qrBox = root.findViewById(R.id.qrBox)
        qrDivider = root.findViewById(R.id.qrDivider)
        tvQrState = root.findViewById(R.id.tvQrState)

        val title = root.findViewById<TextView>(R.id.dlgTitle)
        val sub = root.findViewById<TextView>(R.id.dlgSub)

        title.text = if (initial == null) "添加飞牛账号" else "无法登录，请检查账号信息"
        sub.text = if (cancelable) "地址填 IP 或域名，密码会自动记住" else "必须填写正确的服务器与账号后才能继续"

        initial?.let {
            ssl = it.ssl
            edHost.setText(it.host)
            edPort.setText(it.port.toString())
            edUser.setText(it.user)
            edPass.setText(it.password)
        }
        initialError?.let { showError(it, false) }

        listOf<EditText>(edHost, edPort, edUser, edPass).forEach { e ->
            e.dropDefaultHighlight()
            e.setSelectAllOnFocus(true)
            e.setOnEditorActionListener { _, _, _ -> doSave(); true }
            e.setOnFocusChangeListener { _, has ->
                if (has) {
                    val imm = context.ime()
                    imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        edPass.imeOptions = if (cancelable) android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        else android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        if (initial == null) edHost.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

        val btnTest = root.findViewById<TextView>(R.id.btnTest)
        val btnSave = root.findViewById<TextView>(R.id.btnSave)
        btnTest.dropDefaultHighlight()
        btnSave.dropDefaultHighlight()
        btnTest.setOnClickListener { doTest() }
        btnSave.setOnClickListener { doSave() }

        // 不可取消时吞掉返回键
        if (!cancelable) {
            dialog.setOnKeyListener { _, keyCode, event ->
                keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN
            }
        }

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnDismissListener {
            // 弹窗一关就把临时服务收掉，不留长期监听端口
            qrServer?.stop()
            qrServer = null
            scope.cancel()
            dismissCb?.invoke()
        }
        dialog.show()
        startQrLogin(root)
        edHost.post { edHost.requestFocus() }
    }

    // ------------------------------------------------------- 手机扫码登录

    /**
     * 起局域网服务 + 画二维码。
     * 拿不到局域网地址（比如没连 WiFi）时，整块扫码区域直接隐藏，退回纯手动输入。
     */
    private fun startQrLogin(root: View) {
        val imgQr = root.findViewById<ImageView>(R.id.imgQr)
        val tvUrl = root.findViewById<TextView>(R.id.tvQrUrl)
        val qrLoading = root.findViewById<View>(R.id.qrLoading)

        val server = QrLoginServer { acc ->
            // 回调来自网络线程，切回主线程再动界面
            scope.launch {
                if (!dialog.isShowing) return@launch
                onQrCredential(acc)
            }
        }

        val url = server.start()
        if (url == null) {
            qrBox.visibility = View.GONE
            qrDivider.visibility = View.GONE
            return
        }
        qrServer = server

        val bmp = try {
            QrCode.bitmap(url, 480)
        } catch (t: Throwable) {
            android.util.Log.w("FnAlbum", "qr: 生成二维码失败 ${t.message}")
            null
        }
        if (bmp == null) {
            qrBox.visibility = View.GONE
            qrDivider.visibility = View.GONE
            server.stop()
            qrServer = null
            return
        }

        qrLoading.visibility = View.GONE
        imgQr.setImageBitmap(bmp)
        tvUrl.text = url
        setQrState("等待手机扫码…")
    }

    /** 手机那边提交上来的凭据：填进表单再走和手动登录完全一样的流程 */
    private fun onQrCredential(acc: Account) {
        if (testing) return
        ssl = acc.ssl
        edHost.setText(acc.host)
        edPort.setText(acc.port.toString())
        edUser.setText(acc.user)
        edPass.setText(acc.password)
        setQrState("已收到手机填写的信息，正在登录…", "#5BD68A")
        doSave()
    }

    private fun setQrState(text: String, color: String = "#9FB0C6") {
        if (!::tvQrState.isInitialized) return
        tvQrState.text = text
        tvQrState.setTextColor(Color.parseColor(color))
    }

    private var dismissCb: (() -> Unit)? = null

    fun onDismissCallback(cb: () -> Unit) {
        dismissCb = cb
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun readForm(): Account? {
        val raw = edHost.text.toString().trim()
        if (raw.isEmpty()) {
            showError("请填写服务器地址", false); return null
        }
        var hostText = raw
        var useSsl = ssl
        when {
            hostText.startsWith("https://", true) -> { useSsl = true; hostText = hostText.substring(8) }
            hostText.startsWith("http://", true) -> { useSsl = false; hostText = hostText.substring(7) }
        }
        hostText = hostText.trimEnd('/').trim()

        var port = edPort.text.toString().trim().toIntOrNull() ?: 5666
        if (hostText.contains(':')) {
            val parts = hostText.split(':')
            if (parts.size == 2 && parts[1].toIntOrNull()?.let { it in 1..65535 } == true) {
                hostText = parts[0]
                port = parts[1].toInt()
            }
        }
        if (hostText.isEmpty()) {
            showError("服务器地址不合法", false); return null
        }

        val user = edUser.text.toString().trim()
        if (user.isEmpty()) {
            showError("请填写账号", false); return null
        }
        val pass = edPass.text.toString()
        if (pass.isEmpty()) {
            showError("请填写密码", false); return null
        }

        ssl = useSsl
        return Account(host = hostText, port = port, user = user, password = pass, ssl = useSsl)
    }

    private fun showError(msg: String, ok: Boolean) {
        tvErr.visibility = View.VISIBLE
        tvErr.text = msg
        tvErr.setTextColor(if (ok) Color.parseColor("#5BD68A") else Color.parseColor("#FF7B72"))
    }

    /** 切换忙碌状态；无论结束与否都会把 label 显示到提示行 */
    private fun setBusy(busy: Boolean, label: String, ok: Boolean = busy) {
        testing = busy
        edHost.isEnabled = !busy
        edPort.isEnabled = !busy
        edUser.isEnabled = !busy
        edPass.isEnabled = !busy
        showError(label, ok)
    }

    private fun doTest() {
        if (testing) return
        val acc = readForm() ?: return
        setBusy(true, "正在连接 ${acc.host}:${acc.port} …")
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Api.login(acc) }
                setBusy(false, "连接成功，可以保存了", ok = true)
            } catch (e: Exception) {
                setBusy(false, e.message ?: "连接失败", ok = false)
            }
        }
    }

    private fun doSave() {
        if (testing) return
        val acc = readForm() ?: return
        setBusy(true, "正在登录 ${acc.host}:${acc.port} …")
        setQrState("正在登录 ${acc.host}:${acc.port} …")
        scope.launch {
            try {
                val logged = withContext(Dispatchers.IO) { Api.login(acc) }
                dialog.dismiss()
                onSaved(logged)
                scope.cancel()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setBusy(false, e.message ?: "登录失败", ok = false)
                setQrState("登录失败，可重新扫码或手动修改", "#FF7B72")
            }
        }
    }
}
