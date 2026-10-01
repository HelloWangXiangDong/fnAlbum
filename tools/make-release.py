"""打包并发布一个 GitHub Release（附带 APK）。

用法：
    # 先确保 APK 已构建并放到 dist/ 下
    GH_TOKEN=<你的 PAT> python tools/make-release.py 1.2

    # 指定其它版本号 / APK 路径
    GH_TOKEN=... python tools/make-release.py 1.3 --apk dist/FnAlbum-TV-v1.3.apk

脚本会：
    1. 打一个 v<版本> 的本地 tag（若已存在则复用）
    2. 在 GitHub 上创建同名 Release
    3. 把 APK 作为 release asset 上传

注意：PAT 只需要 repo 权限，不要把它写进文件或提交到仓库。
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"
REPO = "HelloWangXiangDong/fnAlbum"


def call(method, url, payload=None, raw=None, ctype="application/json"):
    data = raw if raw is not None else (json.dumps(payload).encode() if payload is not None else None)
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "token " + os.environ["GH_TOKEN"])
    req.add_header("Accept", "application/vnd.github+json")
    if data:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            body = r.read().decode()
            return r.status, (json.loads(body) if body.strip().startswith(("{", "[")) else body)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("version", help="版本号，例如 1.2（会自动加 v 前缀）")
    ap.add_argument("--apk", help="APK 路径，默认 dist/FnAlbum-TV-v<版本>.apk")
    ap.add_argument("--notes", help="Release 说明文件（markdown），默认用一句占位说明")
    ap.add_argument("--repo", default=REPO, help=f"仓库，默认 {REPO}")
    args = ap.parse_args()

    if not os.environ.get("GH_TOKEN"):
        sys.exit("缺少环境变量 GH_TOKEN（GitHub Personal Access Token，需 repo 权限）")

    tag = args.version if args.version.startswith("v") else "v" + args.version
    apk = args.apk or os.path.join("dist", f"FnAlbum-TV-{tag}.apk")
    if not os.path.isfile(apk):
        sys.exit(f"找不到 APK：{apk}")

    body = open(args.notes, encoding="utf-8").read() if args.notes else f"{tag} 发布。"

    # 已存在同名 release 时先删掉，保证脚本可重复执行
    st, rel = call("GET", f"{API}/repos/{args.repo}/releases/tags/{tag}")
    if st == 200:
        print(f"删除已存在的 release {tag} (id={rel['id']})")
        call("DELETE", f"{API}/repos/{args.repo}/releases/{rel['id']}")

    st, rel = call("POST", f"{API}/repos/{args.repo}/releases", {
        "tag_name": tag,
        "name": tag,
        "body": body,
        "draft": False,
        "prerelease": False,
    })
    if st not in (200, 201):
        sys.exit(f"创建 release 失败：{st} {rel}")
    print(f"Release 已创建：{rel['html_url']}")

    with open(apk, "rb") as f:
        blob = f.read()
    name = os.path.basename(apk)
    print(f"上传 {name}（{len(blob) / 1024 / 1024:.2f} MB）…")
    url = rel["upload_url"].split("{")[0] + f"?name={name}"
    st, asset = call("POST", url, raw=blob, ctype="application/vnd.android.package-archive")
    if st in (200, 201):
        print(f"下载地址：{asset['browser_download_url']}")
    else:
        sys.exit(f"上传失败：{st} {asset}")

    print("\n别忘了推送 tag：git push origin " + tag)


if __name__ == "__main__":
    main()
