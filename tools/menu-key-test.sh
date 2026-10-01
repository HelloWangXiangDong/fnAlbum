#!/bin/bash
# 验证各"菜单类"按键是否能唤起菜单
# 判定方式：dump UI 视图树，看菜单弹窗特有的"切换相册"是否出现
# 注意：必须禁用 Git Bash 的路径转换，否则 /sdcard/... 会被改写成 Windows 路径
export MSYS_NO_PATHCONV=1
ADB="${ADB:-C:/Android/Sdk/platform-tools/adb.exe}"
PKG="com.fnalbum.tv"

menu_open() {
  $ADB shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1
  if $ADB shell cat /sdcard/w.xml 2>/dev/null | grep -q '切换相册'; then
    echo open
  else
    echo closed
  fi
}

focus() {
  $ADB shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | sed 's/.*u0 //;s/}.*//'
}

probe() {
  local code="$1" name="$2"
  if [ "$(menu_open)" = "open" ]; then
    $ADB shell input keyevent 4 >/dev/null 2>&1; sleep 1.0
  fi
  $ADB shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 2.0
  $ADB shell input keyevent "$code" >/dev/null 2>&1; sleep 1.8
  if [ "$(menu_open)" = "open" ]; then
    echo "  keyevent $code  $name  ->  OK  菜单打开"
  else
    echo "  keyevent $code  $name  ->  --  未打开（当前：$(focus)）"
  fi
}

$ADB shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 4
if [ "$(menu_open)" = "open" ]; then $ADB shell input keyevent 4 >/dev/null 2>&1; sleep 1; fi
echo "基线状态 = $(menu_open)  当前窗口 = $(focus)"
echo
probe 82   "KEYCODE_MENU"
probe 176  "KEYCODE_SETTINGS"
probe 165  "KEYCODE_INFO"
probe 75   "KEYCODE_TAB   (对照，应无反应)"
