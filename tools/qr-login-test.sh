#!/usr/bin/env bash
# 扫码登录回归测试
#
# 模拟"手机扫码填写"的完整链路：
#   1. 清空登录态启动 App -> 首次弹窗应出现二维码
#   2. 从日志里取到二维码里的 URL（端口 + token）
#   3. adb forward 把端口映射到本机，用 curl 冒充手机
#   4. 拉填写页 / 错误 token / 畸形请求 / 正常提交
#   5. 校验 TV 端自动登录成功，且弹窗关闭后端口立即释放
#
# 用法：FN_HOST=... FN_USER=... FN_PASS=... bash tools/qr-login-test.sh
#      SKIP_INSTALL=1 FN_HOST=... FN_USER=... FN_PASS=... bash tools/qr-login-test.sh
#
# 注意：这里没有内置任何默认账号，必须自己通过环境变量传入。

set -u

ADB="${ADB:-C:/Android/Sdk/platform-tools/adb.exe}"
ROOT_POSIX="$(cd "$(dirname "$0")/.." && pwd)"
# adb.exe 是原生程序，必须喂 Windows 风格路径
ROOT_WIN="$(cygpath -w "$ROOT_POSIX" 2>/dev/null || echo "$ROOT_POSIX")"
SHOTS="$ROOT_POSIX/_shots"
PAGEFILE="$ROOT_POSIX/_qrpage.html"
LOCAL_PORT=18765

FN_HOST="${FN_HOST:-}"
FN_PORT="${FN_PORT:-5666}"
FN_USER="${FN_USER:-}"
FN_PASS="${FN_PASS:-}"
SKIP_INSTALL="${SKIP_INSTALL:-0}"

if [ -z "$FN_HOST" ] || [ -z "$FN_USER" ] || [ -z "$FN_PASS" ]; then
  echo "缺少参数：请用 FN_HOST / FN_USER / FN_PASS 环境变量提供测试账号" >&2
  exit 2
fi

PASS_N=0; FAIL_N=0
ok()   { echo "  [PASS] $1"; PASS_N=$((PASS_N+1)); }
bad()  { echo "  [FAIL] $1"; FAIL_N=$((FAIL_N+1)); }
check(){ if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1 期望「$3」实际「$2」"; fi; }

mkdir -p "$SHOTS"

# grep -c 在无匹配时既输出 0 又返回非 0，这里统一成干净的数字
count_fatal() {
  local n
  n="$("$ADB" logcat -d -s AndroidRuntime:E 2>/dev/null | grep -c FATAL)"
  echo "${n:-0}" | head -1 | tr -dc '0-9' | sed 's/^$/0/'
}

if [ "$SKIP_INSTALL" != "1" ]; then
  echo "== 0. 安装 APK =="
  "$ADB" install -r "$ROOT_WIN/app/build/outputs/apk/debug/app-debug.apk" 2>&1 | tail -1
fi

echo "== 1. 清空登录态并启动 =="
"$ADB" shell pm clear com.fnalbum.tv >/dev/null 2>&1
"$ADB" logcat -c 2>/dev/null
"$ADB" shell am start -n com.fnalbum.tv/.MainActivity >/dev/null 2>&1
sleep 6

URL="$("$ADB" logcat -d -s FnAlbum:* 2>/dev/null | grep -o 'url=http://[^ ]*' | tail -1 | sed 's/^url=//')"
if [ -z "$URL" ]; then
  bad "未在日志里找到二维码地址（服务没起来）"
  "$ADB" logcat -d -s FnAlbum:* 2>/dev/null | tail -5
  exit 1
fi
ok "二维码地址 $URL"

TOKEN="${URL##*/}"
DEVPORT="$(echo "$URL" | sed -E 's#.*:([0-9]+)/.*#\1#')"
echo "     token=$TOKEN  devicePort=$DEVPORT"

"$ADB" exec-out screencap -p > "$SHOTS/80_qr_login.png"
echo "     截图 -> _shots/80_qr_login.png"

echo "== 2. adb forward 模拟手机访问 =="
"$ADB" forward "tcp:$LOCAL_PORT" "tcp:$DEVPORT" >/dev/null 2>&1
BASE="http://127.0.0.1:$LOCAL_PORT"

echo "== 3. 手机端页面 =="
code="$(curl -s -m 8 -o "$PAGEFILE" -w '%{http_code}' "$BASE/$TOKEN")"
check "填写页 HTTP 状态" "$code" "200"
for kw in "服务器地址" "端口" "账号" "密码" "发送到电视"; do
  if grep -q "$kw" "$PAGEFILE" 2>/dev/null; then ok "页面含「$kw」"; else bad "页面缺「$kw」"; fi
done

echo "== 4. 路由防护 =="
code="$(curl -s -m 6 -o /dev/null -w '%{http_code}' "$BASE/wrongtoken")"
check "错误 token 应 404" "$code" "404"
code="$(curl -s -m 6 -o /dev/null -w '%{http_code}' "$BASE/")"
check "根路径应 302" "$code" "302"

echo "== 5. 畸形请求应被拒 =="
resp="$(curl -s -m 8 -X POST "$BASE/$TOKEN/submit" -H 'Content-Type: application/json' -d '{"host":"","user":"","password":""}')"
case "$resp" in *'"ok":false'*) ok "空字段被拒 -> $resp";; *) bad "空字段未被拒 -> $resp";; esac

echo "== 6. 正常提交（模拟手机点发送） =="
"$ADB" logcat -c 2>/dev/null
resp="$(curl -s -m 10 -X POST "$BASE/$TOKEN/submit" -H 'Content-Type: application/json' \
  -d "{\"host\":\"$FN_HOST\",\"port\":\"$FN_PORT\",\"user\":\"$FN_USER\",\"password\":\"$FN_PASS\"}")"
case "$resp" in *'"ok":true'*) ok "提交被接受 -> $resp";; *) bad "提交失败 -> $resp";; esac

echo "   等待 TV 完成登录 …"
sleep 15
"$ADB" exec-out screencap -p > "$SHOTS/81_qr_after.png"

if "$ADB" logcat -d -s FnAlbum:* 2>/dev/null | grep -q "login ok token="; then
  ok "TV 端自动登录成功"
else
  bad "TV 端未完成登录"
  "$ADB" logcat -d -s FnAlbum:* 2>/dev/null | tail -8
fi

if "$ADB" logcat -d -s FnAlbum:* 2>/dev/null | grep -q "qr: 服务已停止"; then
  ok "登录成功后服务自动关闭"
else
  bad "服务未自动关闭"
fi

echo "== 7. 弹窗关闭后端口必须释放 =="
sleep 2
code="$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE/$TOKEN" 2>/dev/null)"
rc=$?
if [ "$rc" != "0" ]; then ok "端口已不可访问（curl 退出码 $rc）"
elif [ "$code" = "000" ]; then ok "端口已不可访问（无响应）"
else bad "端口仍在响应 ($code)"; fi

check "崩溃次数" "$(count_fatal)" "0"

"$ADB" forward --remove "tcp:$LOCAL_PORT" >/dev/null 2>&1
rm -f "$PAGEFILE"

echo
echo "======================================"
echo "  通过 $PASS_N 项，失败 $FAIL_N 项"
echo "======================================"
[ "$FAIL_N" = "0" ]
