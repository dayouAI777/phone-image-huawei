#!/usr/bin/env python3
"""
QwenPaint 模型下载工具（只依赖标准库）

列出仓库里的模型文件:
    python scripts/fetch-model.py --list second-state/SD-Turbo-GGUF

下载:
    python scripts/fetch-model.py --repo second-state/SD-Turbo-GGUF \
        --file sd-turbo-Q4_K.gguf --out models

国内建议加 --mirror（hf-mirror.com）
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

HF = "https://huggingface.co"
MIRROR = "https://hf-mirror.com"
MODEL_EXTS = (".gguf", ".safetensors", ".ckpt", ".pt", ".pth")


def base_url(mirror: bool) -> str:
    return MIRROR if mirror else HF


def list_files(repo: str, mirror: bool) -> int:
    url = f"{base_url(mirror)}/api/models/{repo}"
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            data = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"[fail] {e.code} {e.reason} -> {url}")
        return 1
    except Exception as e:
        print(f"[fail] {e}")
        return 1

    files = [
        s.get("rfilename", "")
        for s in data.get("siblings", [])
        if s.get("rfilename", "").lower().endswith(MODEL_EXTS)
    ]
    if not files:
        print(f"[warn] no model files in {repo}")
        return 0
    print(f"{repo} ({len(files)} files):")
    for f in files:
        print(f"  {f}")
    return 0


def download(repo: str, filename: str, out_dir: str, mirror: bool) -> int:
    os.makedirs(out_dir, exist_ok=True)
    target = os.path.join(out_dir, os.path.basename(filename))
    url = f"{base_url(mirror)}/{repo}/resolve/main/{filename}"

    resume_from = os.path.getsize(target) if os.path.exists(target) else 0
    req = urllib.request.Request(url)
    if resume_from:
        req.add_header("Range", f"bytes={resume_from}-")
        print(f"[info] resuming at {resume_from / 1e6:.1f} MB")

    try:
        resp = urllib.request.urlopen(req, timeout=60)
    except urllib.error.HTTPError as e:
        if e.code == 416:
            print("[ok] already complete")
            return 0
        print(f"[fail] {e.code} {e.reason} -> {url}")
        return 1
    except Exception as e:
        print(f"[fail] {e}")
        return 1

    total = resume_from
    clen = resp.headers.get("Content-Length")
    if clen and clen.isdigit():
        total = resume_from + int(clen)
    elif resp.headers.get("Content-Range"):
        total = int(resp.headers["Content-Range"].split("/")[-1])

    mode = "ab" if resume_from else "wb"
    written = resume_from
    chunk = 1 << 20
    try:
        with resp, open(target, mode) as f:
            while True:
                buf = resp.read(chunk)
                if not buf:
                    break
                f.write(buf)
                written += len(buf)
                if total:
                    pct = written / total * 100
                    sys.stdout.write(f"\r  {written / 1e6:8.1f} / {total / 1e6:.1f} MB  {pct:5.1f}%")
                else:
                    sys.stdout.write(f"\r  {written / 1e6:8.1f} MB")
                sys.stdout.flush()
    except KeyboardInterrupt:
        print("\n[abort] partial file kept, rerun to resume")
        return 130
    except Exception as e:
        print(f"\n[fail] {e}")
        return 1

    print()
    print(f"[ok] {target}  ({os.path.getsize(target) / 1e6:.1f} MB)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Download GGUF/safetensors models for QwenPaint")
    ap.add_argument("--list", metavar="REPO", help="list model files in a repo")
    ap.add_argument("--repo", help="repo id, e.g. second-state/SD-Turbo-GGUF")
    ap.add_argument("--file", help="file name inside the repo")
    ap.add_argument("--out", default="models", help="output directory (default: models)")
    ap.add_argument("--mirror", action="store_true", help="use hf-mirror.com (faster in CN)")
    args = ap.parse_args()

    if args.list:
        return list_files(args.list, args.mirror)
    if args.repo and args.file:
        return download(args.repo, args.file, args.out, args.mirror)
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
