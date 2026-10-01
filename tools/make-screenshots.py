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

from PIL import Image

ROOT = r"C:\tools\fnAlbum"
SRC = os.path.join(ROOT, "_shots")
DST = os.path.join(ROOT, "docs", "screenshots")
SPONSOR = os.path.join(ROOT, "docs", "sponsor")

W = 1280
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
PHOTO_JOBS = [("03-grid.jpg", "03-grid.png"), ("04-grid12.jpg", "04-grid12.png")]


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

    print("B. 实拍图（只压缩）")
    for out, src in PHOTO_JOBS:
        p = os.path.join(DST, src)
        if not os.path.exists(p):
            print(f"   跳过 {out}（没有 {src}）")
            continue
        im = Image.open(p).convert("RGB").resize((W, W * 9 // 16), Image.LANCZOS)
        q, kb = save_under(im, os.path.join(DST, out))
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
