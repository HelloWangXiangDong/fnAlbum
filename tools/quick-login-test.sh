#!/usr/bin/env bash
# 一键：构建 -> 安装 -> 清空登录状态 -> 自动填表登录 -> 输出日志与截图
#
# 用法：
#   FN_HOST=192.168.1.10 FN_USER=admin FN_PASS=yourpass bash tools/quick-login-test.sh
#   FN_HOST=... FN_USER=... FN_PASS=... SKIP_BUILD=1 bash tools/quick-login-test.sh
#
# 注意：这里没有内置任何默认账号，必须自己通过环境变量传入。

set -u

ADB="${ADB:-C:/Android/Sdk/platform-tools/adb.exe}"
ROOT_POSIX="$(cd "$(dirname "$0")/.." && pwd)"
ROOT_WIN="$(cygpath -w "$ROOT_POSIX" 2>/dev/null || echo "$ROOT_POSIX")"
SHOT="${SHOT:-$ROOT_POSIX/_shots/last.png}"
GRADLE="${GRADLE:-gradle}"

FN_HOST="${FN_HOST:-}"
FN_PORT="${FN_PORT:-5666}"
FN_USER="${FN_USER:-}"
FN_PASS="${FN_PASS:-}"

if [ -z "$FN_HOST" ] || [ -z "$FN_USER" ] || [ -z "$FN_PASS" ]; then
  echo "缺少参数：请用 FN_HOST / FN_USER / FN_PASS 环境变量提供测试账号" >&2
  echo "示例：FN_HOST=192.168.1.10 FN_USER=admin FN_PASS=secret bash tools/quick-login-test.sh" >&2
  exit 2
fi

mkdir -p "$(dirname "$SHOT")"
cd "$ROOT_POSIX"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  "$GRADLE" :app:assembleDebug --console=plain 2>&1 | grep -E "BUILD|e: |error:" || true
fi

"$ADB" install -r "$ROOT_WIN/app/build/outputs/apk/debug/app-debug.apk" 2>&1 | tail -1

# 清掉已存账号，保证走首次登录流程
"$ADB" shell pm clear com.fnalbum.tv >/dev/null 2>&1 || true
"$ADB" logcat -c 2>/dev/null
"$ADB" shell am start -n com.fnalbum.tv/.MainActivity >/dev/null 2>&1
sleep 3

"$ADB" shell input text "$FN_HOST"; sleep 0.4
"$ADB" shell input keyevent 61; "$ADB" shell input keyevent 61; sleep 0.4
"$ADB" shell input text "$FN_USER"; sleep 0.4
"$ADB" shell input keyevent 61; sleep 0.4
"$ADB" shell input text "$FN_PASS"; sleep 0.4
"$ADB" shell input keyevent 61; "$ADB" shell input keyevent 61; sleep 0.4
"$ADB" shell input keyevent 66
sleep "${SLEEP_SECS:-10}"

"$ADB" exec-out screencap -p > "$SHOT" 2>/dev/null || true
echo "=========== LOG ==========="
"$ADB" logcat -d -s FnAlbum:* AndroidRuntime:E 2>/dev/null | tail -"${LOG_LINES:-25}"
