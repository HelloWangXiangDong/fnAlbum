"""
把登录截图里显示的旧内网 IP 提示文案擦掉，只保留干净的空输入框，
然后把 _shots/ 下的原始截图压成 README 用的图，输出到 docs/screenshots/。

之所以要擦：源码里的占位提示已经改成通用示例地址（如 192.168.1.100），
而这两张历史截图里还留着开发者自己局域网的真实地址，不适合随仓库公开。
"""
import os
import statistics
from PIL import Image

ROOT = r"C:\tools\fnAlbum"
SRC = os.path.join(ROOT, "_shots")
DST = os.path.join(ROOT, "docs", "screenshots")

# 需要擦除 hint 文本的区域（原图 1920x1080 坐标），以及用于取背景色的采样区。
# 坐标是先定位输入框的蓝色边框、再在框内找文本像素自动探测出来的。
ERASE = {
    "02_login.png":    {"rect": (478, 384, 745, 427), "sample": (790, 390, 880, 420)},
    "80_qr_login.png": {"rect": (217, 384, 484, 427), "sample": (520, 390, 610, 420)},
}

# (源文件, 目标文件名)
PICK = [
    ("02_login.png",      "01-login.png"),
    ("80_qr_login.png",   "02-qr-login.png"),
    ("31_final.png",      "03-grid.png"),
    ("28_grid12.png",     "04-grid12.png"),
    ("52_mkey.png",       "05-menu.png"),
    ("23_viewer.png",     "06-viewer.png"),
    ("58_video_play.png", "07-video.png"),
    ("53_live_enter.png", "08-live.png"),
    ("30_addacct.png",    "09-add-account.png"),
]


def median_bg(im, box):
    """取采样区的中位色作为填充背景，比单点取样更稳。"""
    r, g, b = [], [], []
    for px in im.crop(box).getdata():
        r.append(px[0]); g.append(px[1]); b.append(px[2])
    return (int(statistics.median(r)), int(statistics.median(g)), int(statistics.median(b)))


os.makedirs(DST, exist_ok=True)
patched = {}

for name, spec in ERASE.items():
    path = os.path.join(SRC, name)
    im = Image.open(path).convert("RGB")
    bg = median_bg(im, spec["sample"])
    x0, y0, x1, y1 = spec["rect"]
    im.paste(Image.new("RGB", (x1 - x0, y1 - y0), bg), (x0, y0))
    patched[name] = im
    print(f"{name:18s} 擦除 {spec['rect']}  背景色 {bg}")

for src, dst in PICK:
    im = patched.get(src) or Image.open(os.path.join(SRC, src)).convert("RGB")
    w = 1280
    h = round(im.height * w / im.width)
    im = im.resize((w, h), Image.LANCZOS)
    out = os.path.join(DST, dst)
    im.save(out, "JPEG", quality=82, optimize=True)
    print(f"  -> {dst:20s} {im.size}  {os.path.getsize(out)/1024:6.1f} KB")
