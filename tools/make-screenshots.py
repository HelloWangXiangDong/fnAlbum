"""生成 README 用的图片：脱敏 + 压缩到 100 KB 以内。

三类来源：
  A. `_shots/` 里的模拟器原始截图 —— 需要打马赛克（背后有个人照片）、
     擦掉登录框里的占位提示，再压缩。
  B. `03-grid` / `04-grid12` —— 由开发者手机拍的电视实拍照片处理而来，
     不是截图，本脚本只负责把它们压到 100 KB 以内。
  C. `docs/sponsor/` 收款码 —— 只压缩，不改内容（保证仍可扫码）。

打码区域是靠「带坐标标尺的渲染图」人工量出来的（见 docs/DEVELOPMENT.md），
版式改版后需要重新量。原则：界面文字保持清晰，只糊掉照片内容。

用法：
    python tools/make-screenshots.py
"""
import os
import statistics

from PIL import Image, ImageFilter

ROOT = r"C:\tools\fnAlbum"
SRC = os.path.join(ROOT, "_shots")
DST = os.path.join(ROOT, "docs", "screenshots")
SPONSOR = os.path.join(ROOT, "docs", "sponsor")

W = 1280
PHOTO_W = 1024      # 实拍照片用 1024 宽：手机拍屏噪点重，1280 宽只能压到 q≈52，反而更糊
MAX_KB = 100
BLOCK = 20          # 马赛克格子边长

# ---- A. 截图流水线 ----------------------------------------------------------
# 输出名 -> _shots 里的源文件
PICK = {
    "01-login.jpg":       "02_login.png",
    "02-qr-login.jpg":    "80_qr_login.png",
    "05-menu.jpg":        "52_mkey.png",
    "06-viewer.jpg":      "23_viewer.png",
    "07-video.jpg":       "58_video_play.png",
    "08-live.jpg":        "53_live_enter.png",
    "09-add-account.jpg": "30_addacct.png",
}

# 含个人照片、必须打码的区域（1280x720 坐标）
MOSAIC = {
    "52_mkey.png": [(0, 50, 1280, 668)],                  # 菜单背后的照片网格
    # 全屏照片区；左下角另有前景人物的下半身在底部提示条里露出来，单独补一条
    "23_viewer.png": [(0, 103, 1280, 667), (0, 667, 430, 720)],
    # 视频播放时播放器会自动隐藏信息栏，整幅都是画面，只能整张糊
    "58_video_play.png": [(0, 0, 1280, 720)],
    # 实况照片是竖构图，会从信息栏上方和下方露出来，需要额外糊掉这条竖带；
    # 下边只糊到 y=678（底部提示文字的上沿），保证「OK 播放动图」这行仍可读
    "53_live_enter.png": [(0, 103, 1280, 667), (310, 0, 780, 678)],
    "30_addacct.png": [(0, 50, 1280, 668)],               # 弹窗背后的照片网格
}

# 打码后贴回原图的界面区域，必须**完全落在**弹窗 / 面板内部（向内收缩留余量）
KEEP = {
    "52_mkey.png":    (402, 34, 882, 678),         # 菜单面板实际 392,24 - 892,688
    "30_addacct.png": (289, 110, 984, 588),        # 弹窗实际 279,100 - 994,598
}

# 登录框 hint 文案区域 + 取背景色的采样区（hint 里是开发者的内网地址）
ERASE = {
    "02_login.png":    {"rect": (478, 384, 745, 427), "sample": (790, 390, 880, 420)},
    "80_qr_login.png": {"rect": (217, 384, 484, 427), "sample": (520, 390, 610, 420)},
}

# ---- B. 实拍图（非截图）-----------------------------------------------------
# 电视实机照片：手机拍屏后在导出工具里把账号那一行涂掉，另外裁掉顶部露出的墙面
# （1956x1140 里去掉顶部 40px 正好是 16:9），再压到 100 KB 以内。
# 源图放在 _shots/（该目录不进仓库），保证本脚本可以重跑。
PHOTO_JOBS = [
    ("03-grid.jpg", "tv-grid.png", 40),
    ("04-grid12.jpg", "tv-grid.png", 40),
]

# 源图里账号那一段（wxd@…xyz）在导出时被半透明涂黑，实测仍能看出文字轮廓
# （放大后能辨认出域名尾部）。这里按列取标题栏内的背景色把它整段盖掉，
# 右侧端口 :5666 保持可见。坐标是源图像素，改图后需要重新量。
PHOTO_ERASE = (1438, 50, 1826, 128)
ERASE_SAMPLE_Y = (122, 136)      # 取背景色的 y 区间：标题栏内、文字下方、栏底之上


def median_bg(im, box):
    data = im.crop(box).tobytes()
    px = [data[i:i + 3] for i in range(0, len(data), 3)]
    return tuple(int(statistics.median(p[ch] for p in px)) for ch in range(3))


def mosaic(im, box, block=BLOCK):
    region = im.crop(box)
    small = region.resize((max(1, region.width // block), max(1, region.height // block)), Image.BOX)
    im.paste(small.resize(region.size, Image.NEAREST), box)


def save_under(im, path, max_kb=MAX_KB):
    """自适应质量压缩，直到体积达标。"""
    for q in (82, 76, 70, 64, 58, 52, 46, 40):
        im.save(path, "JPEG", quality=q, optimize=True, progressive=True)
        kb = os.path.getsize(path) / 1024
        if kb <= max_kb:
            return q, kb
    return 40, os.path.getsize(path) / 1024


def prepare_shot(src):
    im = Image.open(os.path.join(SRC, src)).convert("RGB").resize((W, W * 9 // 16), Image.LANCZOS)

    if src in ERASE:
        spec = ERASE[src]
        x0, y0, x1, y1 = spec["rect"]
        im.paste(Image.new("RGB", (x1 - x0, y1 - y0), median_bg(im, spec["sample"])), (x0, y0))

    orig = im.copy()                      # 打码前的样子，用于之后贴回界面区域

    for box in MOSAIC.get(src, []):
        mosaic(im, box)

    if src in KEEP:                       # 贴回弹窗 / 菜单面板，保证界面清晰
        x0, y0, x1, y1 = KEEP[src]
        im.paste(orig.crop((x0, y0, x1, y1)), (x0, y0))
    return im


def fill_from_below(im, box, sample_y):
    """把矩形区域按列用下方的背景色填平 —— 保留标题栏自身的横向渐变，看不出补丁。"""
    x0, y0, x1, y1 = box
    sy0, sy1 = sample_y
    src = im.crop((x0, sy0, x1, sy1)).load()
    n = sy1 - sy0
    for i in range(x1 - x0):
        color = tuple(sum(src[i, y][c] for y in range(n)) // n for c in range(3))
        im.paste(color, (x0 + i, y0, x0 + i + 1, y1))
    return im


def prepare_photo(src, top_crop=0):
    """实拍照片：盖掉账号、裁成 16:9、降噪，再缩到 1024 宽。"""
    im = Image.open(os.path.join(SRC, src)).convert("RGB")   # convert 顺带丢掉 EXIF / ICC
    im = fill_from_below(im, PHOTO_ERASE, ERASE_SAMPLE_Y)

    if top_crop:
        im = im.crop((0, top_crop, im.width, im.height))

    w, h = im.size
    if h * 16 > w * 9:                        # 太高：上下各裁一点
        d = (h - w * 9 / 16) / 2
        im = im.crop((0, int(d), w, int(h - d)))
    elif h * 16 < w * 9:                      # 太宽：左右各裁一点
        d = (w - h * 16 / 9) / 2
        im = im.crop((int(d), 0, int(w - d), h))

    # 手机拍屏的传感器噪点最吃码率，轻微降噪能省掉约 10% 体积，肉眼几乎看不出
    im = im.filter(ImageFilter.SMOOTH).filter(ImageFilter.GaussianBlur(0.5))
    return im.resize((PHOTO_W, PHOTO_W * 9 // 16), Image.LANCZOS)


def main():
    os.makedirs(DST, exist_ok=True)

    print("A. 截图（打码 + 压缩）")
    for name, src in PICK.items():
        p = os.path.join(SRC, src)
        if not os.path.exists(p):
            print(f"   跳过 {name}（_shots 里没有 {src}）")
            continue
        q, kb = save_under(prepare_shot(src), os.path.join(DST, name))
        print(f"   {name:22s} q={q:2d}  {kb:6.1f} KB")

    print("B. 实拍图（裁剪 + 压缩）")
    for out, src, top in PHOTO_JOBS:
        if not os.path.exists(os.path.join(SRC, src)):
            print(f"   跳过 {out}（_shots 里没有 {src}）")
            continue
        q, kb = save_under(prepare_photo(src, top), os.path.join(DST, out))
        print(f"   {out:22s} q={q:2d}  {kb:6.1f} KB")

    print("C. 收款码（只压缩，保持可扫）")
    for name in ("wechat-pay.jpg", "alipay.jpg"):
        p = os.path.join(SPONSOR, name)
        if not os.path.exists(p):
            continue
        if os.path.getsize(p) / 1024 <= MAX_KB:
            print(f"   {name:22s} 已达标 {os.path.getsize(p) / 1024:.1f} KB")
            continue
        im = Image.open(p).convert("RGB")
        scale = min(1.0, 900 / im.width)
        if scale < 1.0:
            im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
        q, kb = save_under(im, p, max_kb=95)
        print(f"   {name:22s} q={q:2d}  {kb:6.1f} KB  {im.size}")

    # 清掉历史遗留的 .png（内容其实是 JPEG，扩展名是错的）
    for f in sorted(os.listdir(DST)):
        if f.lower().endswith(".png"):
            os.remove(os.path.join(DST, f))
            print(f"   删除遗留 {f}")


if __name__ == "__main__":
    main()
