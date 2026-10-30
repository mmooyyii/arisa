#!/usr/bin/env python3
"""生成发布清单 latest.json (格式见 library/.../Release.kt 的 ReleaseManifest).

用法: release_manifest.py <版本> <刮削器 zip> <apk> <更新说明文件> > latest.json
"""
import hashlib
import json
import os
import sys
import time


def asset(path):
    data = open(path, "rb").read()
    return {"name": os.path.basename(path), "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def version_code(v):
    major, minor, patch = (int(x) for x in v.split("."))
    assert all(0 <= x <= 999 for x in (major, minor, patch)), v
    return major * 1_000_000 + minor * 1_000 + patch


version, scraper, apk, notes = sys.argv[1:5]
manifest = {
    "version": version,
    "versionCode": version_code(version),
    "publishedAt": int(time.time() * 1000),
    "notes": open(notes, encoding="utf-8").read().strip() or None,
    "scraper": asset(scraper),
    "app": asset(apk),
}
json.dump({k: v for k, v in manifest.items() if v is not None}, sys.stdout, ensure_ascii=False, indent=1)
print()
