# 开发与调试

## 环境准备

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 | 编译目标也是 17 |
| Android SDK | Platform 34 / Build-Tools 34 | `local.properties` 里配 `sdk.dir` |
| Gradle | 8.12 | 命令行构建；Android Studio 用自带 wrapper 也行 |
| adb | 随 SDK | 下文示例按 `C:/Android/Sdk/platform-tools/adb.exe` 走 |

首次构建前创建 `local.properties`：

```properties
sdk.dir=C\:\\Android\\Sdk
```

> 这个文件已在 `.gitignore` 里，不会被提交。

## 构建

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-17.0.19+10"
./gradlew :app:assembleDebug --console=plain     # Windows: gradlew.bat
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 仓库自带 Gradle Wrapper（8.12），`distributionUrl` 默认指向**腾讯云镜像**，
> 国内拉取更快。想换官方源就改 `gradle/wrapper/gradle-wrapper.properties`。
> 若本机已装 Gradle 8.12，也可以直接用 `gradle` 命令，效果一样。

**加速技巧**

- 保留 Gradle 守护进程，增量构建约 5 秒；`--no-daemon` 首次构建约 5 分钟；
- 不要给 Gradle 的输出加 `| tail` 这类管道，某些终端会把输出缓冲住，看起来像「卡死」。
  需要留痕就 `2>&1 | tee build.log` 或直接重定向到文件。

**代理**（依赖下载慢时）

写进 **用户目录** 的 `~/.gradle/gradle.properties`，不要写进仓库里的 `gradle.properties`：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
systemProp.http.nonProxyHosts=localhost|127.0.0.1|192.168.*
```

仓库内的 `gradle.properties` 刻意只保留与机器无关的项（内存、AndroidX、编码），
`org.gradle.java.home`、代理这类机器相关配置请放在上面的用户级文件里。

## 装上模拟器 / 真机跑

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.fnalbum.tv/.MainActivity
adb logcat -s FnAlbum:* AndroidRuntime:E
```

日志 TAG 统一是 `FnAlbum`，崩溃看 `AndroidRuntime:E`。

**雷电模拟器**常用命令：

```bash
# 列出实例（得到 index）
"C:/leidian/LDPlayer14/ldconsole.exe" list2
# 重启实例 0（adb 显示 offline 时最有效）
"C:/leidian/LDPlayer14/ldconsole.exe" reboot --index 0
```

> 模拟器 `offline` 时，先试 `adb kill-server && adb start-server`；
> 还不行就重启实例。**不要**在模拟器里跑 `svc wifi disable` 之类的命令，
> 会让 adb shell 挂起，只能重启实例才能恢复。

## 联调与验证

改完代码后，用 adb 就能把主要交互过一遍。

**复现首次启动的登录弹窗**（清空登录态与已存账号）：

```bash
adb shell pm clear com.fnalbum.tv
adb shell am start -n com.fnalbum.tv/.MainActivity
```

**注入遥控器按键**：

```bash
adb shell input keyevent 22    # 右
adb shell input keyevent 20    # 下
adb shell input keyevent 23    # OK
adb shell input keyevent 4     # 返回
```

菜单类按键的四种键码见下一节。

**扫码登录联调**：手机与电视在同一局域网即可，直接扫码走完整链路。想在电脑上
模拟手机提交，可以先用 `adb forward` 把电视端端口映射到本机（端口从日志里取，
默认取 8765–8768 中第一个可用的）：

```bash
adb forward tcp:8765 tcp:8765
curl http://127.0.0.1:8765/            # 手机填写页
```

> 应用内**不内置任何测试账号**，登录信息一律由使用者在弹窗里填写。

## 菜单键怎么测

应用同时接受四种「菜单类」键码，实测均生效：

| 键码 | 名称 | 说明 |
| --- | --- | --- |
| 82 | `KEYCODE_MENU` | 电视遥控器「菜单」键 |
| 176 | `KEYCODE_SETTINGS` | 部分遥控器的设置键 |
| 165 | `KEYCODE_INFO` | 部分遥控器的信息键 |
| 41 | `KEYCODE_M` | **键盘 M 键**，为没有菜单键的笔记本 / 键盘准备 |

对照组：`KEYCODE_TAB`(75) 无反应，符合预期。

键盘 M 键两条实测结论：

- 首页按 M → 菜单正常弹出；
- **在登录弹窗的输入框里按 M 不会被菜单拦截**，会正常输入字母 `m`
  （Dialog 是独立窗口，按键先被 `EditText` 消费，不会传到 Activity）。

### 方式一：adb 直接发按键（最省事）

```bash
adb shell input keyevent 82      # 菜单
adb shell input keyevent 41      # 键盘 M
adb shell input keyevent 176
adb shell input keyevent 165
```

### 方式二：物理键盘真按

设备上的 `Generic.kl` 里写着 `key 127 MENU`。Linux 内核键码 127 就是标准 PC 键盘的
**`KEY_COMPOSE`（菜单键 / Application 键）**，位置在**右 Windows 键的右边**，
键帽图标通常是「光标 + 菜单」。另有 `key 139 MENU`，对应部分键盘的独立菜单键。

实测可直接向模拟器注入内核事件：

```bash
# 先看设备号：adb shell getevent -pl  → 找 "AT Translated Set 2 keyboard" 对应的 /dev/input/eventX
adb shell "sendevent /dev/input/event2 1 127 1; sendevent /dev/input/event2 0 0 0; \
           sendevent /dev/input/event2 1 127 0; sendevent /dev/input/event2 0 0 0"
```

> 笔记本键盘通常**没有**这个键，这时用方式一。

### 方式三：确认自己键盘上哪个键是菜单键

```bash
adb shell getevent -lt
```

逐个按键，输出里出现 `KEY_COMPOSE` 或 `KEY_MENU` 的那个键就是菜单键。

## Git Bash 踩坑

**必须注意路径改写**：Git Bash 会把 `/sdcard/w.xml` 当成路径改写掉，导致
`uiautomator dump` 之类的命令失败。加一行即可：

```bash
export MSYS_NO_PATHCONV=1
```

## 其它排查技巧

- 判断弹窗是否打开：`adb shell uiautomator dump /sdcard/w.xml` 后看视图树里有没有
  菜单项文案；
- **不要用截图文件大小判断界面状态**，不可靠；
- 清理卡住的构建：`gradle --stop`，必要时结束残留的 `java.exe`。

## 生成 README 截图

```bash
python tools/make-screenshots.py
```

脚本会从 `_shots/` 下的原始 1920×1080 截图生成 `docs/screenshots/` 里的 README 用图，做三件事：

1. **打马赛克**：把会露出个人照片的区域糊掉，界面文字（文件名、分辨率、日期、序号、
   菜单项、底部提示条）保持清晰。因为要「先整块糊、再把弹窗贴回去」，
   弹窗/菜单面板的坐标必须量准，且贴回的区域要向内收缩一点、确保落在面板内部。
2. **擦除占位提示**：登录框里的 hint 文案原本是开发者的内网地址，用输入框底色盖掉
   （先定位蓝色边框，再在框内找文本像素，所以换一批截图也能跑）。
3. **压缩**：统一输出 1280×720 的 `.jpg`，自适应降低质量直到 ≤ 100 KB。

另外 `03-grid` / `04-grid12` 不是截图，是手机拍电视的照片（源图 `_shots/tv-grid.png`，
该目录不进仓库）。它走另一条流水线：

1. **盖掉账号**：源图在导出工具里对账号那一行做过半透明涂黑，但**放大后仍能辨认出
   域名轮廓**——半透明涂黑只能压暗、压不掉字形。脚本改为按列取标题栏内的背景色
   （文字下方那条干净的区域）把整段填平，因为标题栏本身有横向渐变，
   逐列取色才不会留下补丁感；右侧端口保持可见。
2. **裁掉墙面**：源图 1956×1140 顶部露出了一点墙面，去掉顶部 40px 正好是 16:9。
3. **降噪**：手机拍屏的传感器噪点最吃码率，轻微降噪能省约 10% 体积。
4. **压到 1024 宽**：这张照片噪点重，1280 宽只能压到 q≈52 才塞进 100 KB，
   画面反而比 1024 宽 / q=70 更糊。

### 这些坐标是怎么量出来的

打码区域不能靠目测——**先把原图画上坐标网格再渲染出来读数**：

```python
from PIL import Image, ImageDraw
im = Image.open("_shots/23_viewer.png").convert("RGB")
d = ImageDraw.Draw(im)
for x in range(0, im.width, 100):          # 每 100px 一条，每 200px 加粗
    d.line([(x, 0), (x, im.height)], fill=(255, 0, 0) if x % 200 == 0 else (255, 140, 0),
           width=3 if x % 200 == 0 else 1)
    d.text((x + 4, 4), str(x), fill=(0, 255, 0))
for y in range(0, im.height, 100):
    d.line([(0, y), (im.width, y)], fill=(255, 0, 0) if y % 200 == 0 else (255, 140, 0),
           width=3 if y % 200 == 0 else 1)
    d.text((4, y + 4), str(y), fill=(0, 255, 255))
im.save("_ruler.png")
```

**注意**：不要靠「渲染出来的图看起来占多少比例」估算坐标，缩放比例未知，误差很大。
必须**读网格线旁边的数字**，把位置换算成相对最近网格线的偏移量。

### 几个容易踩的点

- **半透明信息栏**：全屏查看器的顶部信息栏和底部提示条是贴在照片上的半透明暗条，
  照片本身会从下面透出来。横构图照片还好（多为天空，压暗后是均匀色块），
  **竖构图（实况照片）会从信息栏上方和下方整条露出来**，必须额外补打码区域。
- **视频播放时信息栏会自动隐藏**，整幅都是画面，只能整张打码。
- 别把信息栏整条糊掉——先把提示文字的位置量出来，马赛克只压到文字上沿，
  这样「← → 上一张/下一张 OK 播放动图 BACK 返回」这类操作提示仍然可读。

## 发布新版本

```bash
# 1. 改 app/build.gradle.kts 里的 versionCode / versionName
# 2. 构建并放到 dist/
gradle :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk dist/FnAlbum-TV-v1.3.apk

# 3. 打 tag + 建 Release + 传 APK（一条命令搞定）
GH_TOKEN=<你的 PAT> python tools/make-release.py 1.3

# 4. 推送 tag
git push origin v1.3
```

PAT 只需要 `repo` 权限。**不要把 Token 写进文件或提交到仓库**，通过环境变量传入即可。
