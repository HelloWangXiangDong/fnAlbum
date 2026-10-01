#!/bin/bash
# 菜单功能回归：宫格样式切换 / 相册选择 / 新增账号
# 用法：bash tools/remote-test2.sh
ADB="${ADB:-C:/Android/Sdk/platform-tools/adb.exe}"
S="${SHOTS:-$(cd "$(dirname "$0")/.." && pwd)/_shots}"
mkdir -p "$S"

# 当前菜单处于打开状态，焦点在"切换相册"
# 下移到"九宫格" -> OK
$ADB shell input keyevent 20; sleep 0.5
$ADB shell input keyevent 20; sleep 0.5
$ADB shell input keyevent 23; sleep 2.5
$ADB exec-out screencap -p > "$S/27_grid9.png"

# 打开菜单 -> 下移到"十二宫格" -> OK
$ADB shell input keyevent 82; sleep 1.5
$ADB shell input keyevent 20; sleep 0.4
$ADB shell input keyevent 20; sleep 0.4
$ADB shell input keyevent 20; sleep 0.4
$ADB shell input keyevent 23; sleep 2.5
$ADB exec-out screencap -p > "$S/28_grid12.png"

# 菜单 -> 相册选择
$ADB shell input keyevent 82; sleep 1.5
$ADB shell input keyevent 23; sleep 4.0
$ADB exec-out screencap -p > "$S/29_albums.png"

# 返回菜单 -> 新增账号
$ADB shell input keyevent 4; sleep 1.0
$ADB shell input keyevent 82; sleep 1.5
for i in 1 2 3 4 5; do $ADB shell input keyevent 20; sleep 0.3; done
$ADB shell input keyevent 23; sleep 2.5
$ADB exec-out screencap -p > "$S/30_addacct.png"

echo "=== FATAL count ==="
$ADB logcat -d -s AndroidRuntime:E 2>/dev/null | grep -c "FATAL"
ls -la "$S"/2[7-9]*.png "$S"/30*.png
