package com.fnalbum.tv

import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TV 端「扫码登录」临时服务。
 *
 * 电视遥控器输入 IP / 账号 / 密码极其痛苦，所以在登录弹窗打开期间，
 * 在局域网里起一个极小的 HTTP 服务，把地址编成二维码显示在电视上：
 *
 *   手机扫码 → 打开填写页 → 提交 → 回传到电视 → 电视自动登录
 *
 * 设计取舍：
 * - 不引任何 HTTP 框架（NanoHTTPD 等），手写最小 HTTP/1.1 子集，避免 APK 膨胀；
 * - 路径里带随机 token，只有扫到二维码的设备能提交，且服务只在弹窗期间存活；
 * - 只监听局域网，不申请任何额外权限（INTERNET 已覆盖）。
 */
class QrLoginServer(
    /** 收到手机提交的凭据（在后台线程回调，调用方负责切主线程） */
    private val onCredential: (Account) -> Unit
) {

    companion object {
        private const val TAG = "FnAlbum"

        /** 优先用这几个固定端口，方便 adb forward / 排障；都占用时回落随机端口 */
        private val PREFERRED_PORTS = intArrayOf(8765, 8766, 8767, 8768)

        private const val MAX_BODY = 64 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null
    private val running = AtomicBoolean(false)

    private var token: String = ""
    private var port: Int = 0

    /** 生成好的访问地址，供界面展示 */
    var url: String = ""
        private set

    val isRunning: Boolean get() = running.get()

    // --------------------------------------------------------------- 生命周期

    /** 启动服务并返回手机可访问的 URL；找不到局域网地址或端口全占用时返回 null */
    fun start(): String? {
        val ip = pickLanIp()
        if (ip.isNullOrEmpty()) {
            Log.w(TAG, "qr: 未找到局域网 IPv4 地址，扫码登录不可用")
            return null
        }
        val ss = bindSocket() ?: return null
        serverSocket = ss
        port = ss.localPort
        token = randomToken()
        url = "http://$ip:$port/$token"
        running.set(true)
        pool = Executors.newFixedThreadPool(3)
        acceptThread = Thread({ acceptLoop(ss) }, "qr-login").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "qr: 服务已启动 url=$url")
        return url
    }

    fun stop() {
        if (!running.getAndSet(false) && serverSocket == null) return
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        try {
            pool?.shutdownNow()
        } catch (_: Throwable) {
        }
        serverSocket = null
        pool = null
        acceptThread = null
        Log.i(TAG, "qr: 服务已停止")
    }

    // --------------------------------------------------------------- 网络

    /**
     * 取一个"手机最可能连得上"的局域网 IPv4。
     *
     * 电视盒子/手机常同时挂着多个网卡，选错就白扫，所以按可信度打分：
     * wlan / eth 加分，192.168 段加分，tun/ppp/rmnet（VPN、蜂窝）重罚只在别无选择时才用。
     */
    private fun pickLanIp(): String? {
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = (nif.name ?: "").lowercase()
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || !addr.isSiteLocalAddress) continue
                    val ip = addr.hostAddress ?: continue

                    var score = 0
                    if (name.startsWith("wlan") || name.startsWith("eth")) score += 4
                    else if (name.startsWith("ap") || name.startsWith("swlan")) score += 2
                    when {
                        ip.startsWith("192.168.") -> score += 3
                        ip.startsWith("10.") -> score += 2
                        ip.startsWith("172.") -> score += 1
                    }
                    // 虚拟网卡 / 蜂窝：手机八成连不上，压到最低
                    if (name.startsWith("tun") || name.startsWith("ppp") ||
                        name.startsWith("rmnet") || name.startsWith("dummy")
                    ) {
                        score -= 10
                    }

                    if (score > bestScore) {
                        bestScore = score
                        best = ip
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "qr: 枚举网卡失败 ${t.message}")
        }
        if (best != null) Log.i(TAG, "qr: 选用网卡地址 $best")
        return best
    }

    private fun bindSocket(): ServerSocket? {
        for (p in PREFERRED_PORTS) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(p), 8)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "qr: 端口 $p 不可用（${t.message}）")
            }
        }
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0), 8)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "qr: 无可用端口 ${t.message}")
            null
        }
    }

    private fun randomToken(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val sb = StringBuilder(10)
        val rnd = java.security.SecureRandom()
        repeat(10) { sb.append(chars[rnd.nextInt(chars.length)]) }
        return sb.toString()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "qr: accept 结束 ${t.message}")
                break
            }
            try {
                pool?.execute { handle(socket) }
            } catch (_: Throwable) {
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    // --------------------------------------------------------------- HTTP

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            val out = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLine(input)
            if (requestLine == null) {
                respond(out, 400, "text/plain; charset=utf-8", "bad request")
                return
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(out, 400, "text/plain; charset=utf-8", "bad request")
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            // 读完请求头（必须读完，否则 body 读不到）
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }

            val bodyLen = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (bodyLen in 1..MAX_BODY) readBody(input, bodyLen) else ""

            val segs = path.split('/').filter { it.isNotEmpty() }

            when {
                // 根路径 -> 跳到带 token 的地址
                method == "GET" && segs.isEmpty() -> redirect(out, "/$token")

                // 手机打开填写页
                method == "GET" && segs.size == 1 && segs[0] == token ->
                    respond(out, 200, "text/html; charset=utf-8", htmlPage())

                // 手机提交
                method == "POST" && segs.size == 2 && segs[0] == token && segs[1] == "submit" ->
                    handleSubmit(out, body)

                // 连通性探测（排障用）
                method == "GET" && segs.size == 1 && segs[0] == "ping" ->
                    respond(out, 200, "application/json", """{"ok":true,"app":"FnAlbumTV"}""")

                else -> respond(out, 404, "text/plain; charset=utf-8", "not found")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "qr: 处理请求失败 ${t.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun handleSubmit(out: BufferedOutputStream, body: String) {
        val parsed = try {
            val o = JSONObject(body)
            val host = o.optString("host").trim()
            val port = o.optString("port").trim().toIntOrNull() ?: 5666
            val user = o.optString("user").trim()
            val password = o.optString("password")
            if (host.isEmpty() || user.isEmpty() || password.isEmpty()) null
            else normalise(host, port)?.let { (h, p, ssl) ->
                Account(host = h, port = p, user = user, password = password, ssl = ssl)
            }
        } catch (t: Throwable) {
            null
        }

        if (parsed == null) {
            respond(out, 200, "application/json", """{"ok":false,"msg":"信息不完整或格式不正确，请检查"}""")
            return
        }

        // 先回包再触发登录：手机端能立刻看到"已发送"，不必等登录结果
        respond(out, 200, "application/json", """{"ok":true}""")
        Log.i(TAG, "qr: 收到手机提交 host=${parsed.host}:${parsed.port} user=${parsed.user}")
        try {
            onCredential(parsed)
        } catch (t: Throwable) {
            Log.w(TAG, "qr: 凭据回调异常 ${t.message}")
        }
    }

    /** 归一化用户手输的地址：剥掉 http(s)://，拆掉可能粘进来的端口 */
    private fun normalise(rawHost: String, rawPort: Int): Triple<String, Int, Boolean>? {
        var host = rawHost.trim()
        var ssl = false
        when {
            host.startsWith("https://", true) -> {
                ssl = true; host = host.substring(8)
            }

            host.startsWith("http://", true) -> {
                ssl = false; host = host.substring(7)
            }
        }
        host = host.trimEnd('/').trim()
        var port = rawPort
        if (host.contains(':')) {
            val p = host.split(':')
            if (p.size == 2 && p[1].toIntOrNull()?.let { it in 1..65535 } == true) {
                host = p[0]
                port = p[1].toInt()
            }
        }
        if (host.isEmpty() || port !in 1..65535) return null
        return Triple(host, port, ssl)
    }

    // --------------------------------------------------------------- HTTP 工具

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(96)
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun readBody(input: InputStream, len: Int): String {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n <= 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun respond(out: BufferedOutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun redirect(out: BufferedOutputStream, to: String) {
        val head = "HTTP/1.1 302 Found\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    // --------------------------------------------------------------- 手机页面

    /**
     * 手机端填写页。刻意写成单文件内联样式 + 原生 JS：
     * 不依赖任何外网资源，NAS 局域网里也能秒开。
     */
    private fun htmlPage(): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0e1621">
<title>飞牛相册 TV · 填写登录信息</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#0e1621;color:#e4ecf7;padding:24px 18px 48px;
font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}
.card{max-width:520px;margin:0 auto}
h1{font-size:21px;margin:0 0 8px}
.sub{color:#8fa3bf;font-size:13px;margin:0 0 20px;line-height:1.6}
label{display:block;color:#9fb0c6;font-size:13px;margin:16px 0 6px}
input{width:100%;height:50px;padding:0 14px;font-size:17px;color:#fff;background:#18222f;
border:1px solid #26364a;border-radius:10px;outline:none;-webkit-appearance:none}
input:focus{border-color:#3d7eff;background:#1b2836}
button{width:100%;height:52px;margin-top:26px;border:0;border-radius:10px;background:#3d7eff;
color:#fff;font-size:17px;font-weight:600}
button[disabled]{background:#2a3a50;color:#7d8fa8}
.row{display:flex;gap:12px}
.row .a{flex:3}.row .b{flex:1}
.msg{margin-top:18px;font-size:14px;text-align:center;line-height:1.6;min-height:22px}
.ok{color:#5bd68a}.err{color:#ff7b72}
.foot{margin-top:26px;color:#5c6b80;font-size:12px;text-align:center;line-height:1.7}
</style>
</head>
<body>
<div class="card">
  <h1>飞牛相册 TV</h1>
  <p class="sub">填好登录信息，点下面按钮发送到电视，电视会自动保存并登录，无需再用遥控器输入。</p>
  <form id="f" autocomplete="off" novalidate>
    <div class="row">
      <div class="a">
        <label for="host">服务器地址（IP 或域名）</label>
        <input id="host" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="如 192.168.1.100">
      </div>
      <div class="b">
        <label for="port">端口</label>
        <input id="port" inputmode="numeric" value="5666">
      </div>
    </div>
    <label for="user">账号</label>
    <input id="user" autocapitalize="off" autocorrect="off" spellcheck="false">
    <label for="pass">密码</label>
    <input id="pass" type="password" autocapitalize="off" autocorrect="off" spellcheck="false">
    <button id="btn" type="submit">发送到电视</button>
  </form>
  <div id="msg" class="msg"></div>
  <div class="foot">密码只在本机与电视之间传输，保存在电视本地。</div>
</div>
<script>
(function(){
  var f=document.getElementById('f'),btn=document.getElementById('btn'),msg=document.getElementById('msg');
  function say(t,c){msg.className='msg '+(c||'');msg.textContent=t;}
  function v(id){return document.getElementById(id).value.trim();}
  f.addEventListener('submit',function(e){
    e.preventDefault();
    var host=v('host'),port=v('port'),user=v('user'),pass=document.getElementById('pass').value;
    if(!host){say('请填写服务器地址','err');return;}
    if(!user){say('请填写账号','err');return;}
    if(!pass){say('请填写密码','err');return;}
    btn.disabled=true;say('正在发送…');
    fetch(location.pathname+'/submit',{
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({host:host,port:port,user:user,password:pass})
    }).then(function(r){return r.json();}).then(function(j){
      if(j&&j.ok){say('已发送到电视，请看电视屏幕完成登录','ok');btn.textContent='发送成功';}
      else{say((j&&j.msg)||'发送失败，请重试','err');btn.disabled=false;}
    })['catch'](function(){
      say('连接不上电视，请确认手机和电视在同一个网络','err');
      btn.disabled=false;
    });
  });
})();
</script>
</body>
</html>
""".trimIndent()
}
