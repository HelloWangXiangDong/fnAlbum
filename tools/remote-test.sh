#!/bin/bash
# 遥控器交互回归：方向键 / OK / 返回 / 菜单
# 用法：bash tools/remote-test.sh
ADB="${ADB:-C:/Android/Sdk/platform-tools/adb.exe}"
S="${SHOTS:-$(cd "$(dirname "$0")/.." && pwd)/_shots}"
mkdir -p "$S"

$ADB shell input keyevent 22; sleep 1.2; $ADB exec-out screencap -p > "$S/21_right.png"
$ADB shell input keyevent 20; sleep 2.5; $ADB exec-out screencap -p > "$S/22_down.png"
$ADB shell input keyevent 23; sleep 3.0; $ADB exec-out screencap -p > "$S/23_viewer.png"
$ADB shell input keyevent 22; sleep 2.0; $ADB exec-out screencap -p > "$S/24_viewer_next.png"
$ADB shell input keyevent 4;  sleep 2.0; $ADB exec-out screencap -p > "$S/25_back.png"
$ADB shell input keyevent 82; sleep 2.0; $ADB exec-out screencap -p > "$S/26_menu.png"

ls -la "$S"/2*.png
echo "=== FATAL count ==="
$ADB logcat -d -s AndroidRuntime:E 2>/dev/null | grep -c "FATAL"
