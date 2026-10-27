#!/usr/bin/env python3
"""从 TMDB 网站页面抓演示片库数据 (不需要 API key), 生成 app/src/main/assets/demo-library.json."""
import html, json, re, subprocess, sys, time

UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 Chrome/130 Safari/537.36"

MOVIES = [693134, 438631, 157336, 27205, 155, 872585, 603, 335984, 129, 496243, 13, 680]
SHOWS = [1396, 1399, 209867, 94997, 100088]


def get(url):
    # urllib 会被 Cloudflare 拦 (403), curl 不会
    for _ in range(3):
        r = subprocess.run(["curl", "-s", "-L", "-m", "30", "-H", f"User-Agent: {UA}", "-H", "Accept-Language: zh-CN,zh;q=0.9", url],
                           capture_output=True)
        if r.returncode == 0 and r.stdout:
            return r.stdout.decode("utf-8", "replace")
        time.sleep(2)
    return ""


def path_of(url):
    m = re.search(r"/t/p/[^/]+(/[A-Za-z0-9]+\.(?:jpg|png|svg))", url or "")
    return m.group(1) if m else None


def text(s):
    return html.unescape(re.sub(r"<[^>]+>", "", s or "")).strip()


def meta(page, prop):
    return [html.unescape(m) for m in re.findall(r'<meta property="og:%s" content="([^"]*)"' % prop, page)]


def images(kind, id, which):
    # 图库里每张图都有 href=".../t/p/original/xxx" 的原图链接; 背景图优先无文字的
    langs = ["zh", "en"] if which == "logos" else ["xx", "en"]
    paths = []
    for lang in langs:
        page = get(f"https://www.themoviedb.org/{kind}/{id}/images/{which}?image_language={lang}")
        paths = re.findall(r'href="https://image\.tmdb\.org/t/p/original(/[A-Za-z0-9]+\.(?:png|svg|jpg))"', page)
        if paths:
            break
    # 页面上第一张一般是投票最高的
    return paths[0] if paths else None


def cast_of(page):
    out = []
    section = re.search(r'id="cast_scroller".*?</ol>', page, re.S)
    if not section:
        return out
    for li in re.findall(r'<li class="card[^"]*">(.*?)</li>', section.group(0), re.S):
        name = re.search(r'<p><a href="/person/[^"]*">([^<]*)</a>', li)
        if not name:
            continue
        char = re.search(r'<p class="character">([^<]*)', li)
        img = re.search(r'src="[^"]*(/t/p/[^"]+)"', li)
        out.append({"name": text(name.group(1)), "character": text(char.group(1)) if char else None,
                    "profile": path_of(img.group(1)) if img else None})
    return [{k: v for k, v in c.items() if v} for c in out[:15]]


def common(kind, id):
    page = get(f"https://www.themoviedb.org/{kind}/{id}?language=zh-CN")
    imgs = meta(page, "image")
    year = re.search(r'class="tag release_date">\((\d{4})\)', page)
    pct = re.search(r'data-percent="([\d.]+)"', page)
    genres = re.search(r'<span class="genres">(.*?)</span>', page, re.S)
    runtime = re.search(r'<span class="runtime">\s*(?:(\d+)h)?\s*(?:(\d+)m)?', page)
    cert = re.search(r'<span class="certification">\s*([^<\s]+)', page)
    original = re.search(r'<p class="wrap"><strong><bdi>原(?:始)?(?:标题|名称)</bdi></strong>\s*([^<]+)', page)
    item = {
        "id": f"{'movie' if kind == 'movie' else 'tv'}:{id}",
        "type": kind,
        "tmdbId": id,
        "title": meta(page, "title")[0] if meta(page, "title") else str(id),
        "originalTitle": text(original.group(1)) if original else None,
        "year": int(year.group(1)) if year else None,
        "overview": meta(page, "description")[0] if meta(page, "description") else None,
        "genres": [text(g) for g in re.findall(r'<a [^>]*>([^<]+)</a>', genres.group(1))] if genres else [],
        "rating": round(float(pct.group(1)) / 10, 1) if pct else None,
        "certification": cert.group(1) if cert else None,
        "poster": path_of(imgs[0]) if imgs else None,
        "backdrop": images(kind, id, "backdrops") or (path_of(imgs[1]) if len(imgs) > 1 else None),
        "logo": images(kind, id, "logos"),
        "cast": cast_of(page),
        "match": {"source": "demo", "confidence": 1.0},
    }
    if runtime and (runtime.group(1) or runtime.group(2)):
        item["runtime"] = int(runtime.group(1) or 0) * 60 + int(runtime.group(2) or 0)
    if item["originalTitle"] == item["title"]:
        item["originalTitle"] = None
    return item, page


def episodes(id, season):
    page = get(f"https://www.themoviedb.org/tv/{id}/season/{season}?language=zh-CN")
    eps = []
    for b in re.split(r'class="episode closed"', page)[1:]:
        num = re.search(r'<span class="episode_number">(\d+)</span>', b)
        if not num:
            continue
        title = re.search(r'<h3><a class="no_click open"[^>]*>([^<]+)</a></h3>', b)
        still = re.search(r'src="[^"]*(/t/p/[^"]+)"', b)
        ov = re.search(r'<div class="overview">\s*<p>(.*?)</p>', b, re.S)
        rt = re.search(r'<span class="runtime">(\d+)m', b)
        eps.append({k: v for k, v in {
            "season": season, "episode": int(num.group(1)),
            "title": text(title.group(1)) if title else None,
            "still": path_of(still.group(1)) if still else None,
            "overview": text(ov.group(1)) if ov else None,
            "runtime": int(rt.group(1)) if rt else None,
        }.items() if v is not None})
    return eps[:10]


items = []
now = int(time.time() * 1000)
for i, mid in enumerate(MOVIES):
    item, _ = common("movie", mid)
    item["files"] = [{"fid": f"demo-{mid}", "name": f"{item['title']}.2160p.HDR.mkv", "path": f"/电影/{item['title']}", "size": 0}]
    item["addedAt"] = now - i * 86400_000 * 3
    items.append({k: v for k, v in item.items() if v not in (None, [], "")})
    print(item["title"], item.get("logo"), item.get("backdrop"), file=sys.stderr)
for i, sid in enumerate(SHOWS):
    item, _ = common("tv", sid)
    eps = episodes(sid, 1)
    for e in eps:
        e["files"] = [{"fid": f"demo-{sid}-{e['episode']}", "name": f"S01E{e['episode']:02d}.mkv", "path": "/剧集", "size": 0}]
    item["seasons"] = [{"number": 1, "name": "第 1 季", "episodes": eps}]
    item["addedAt"] = now - i * 86400_000 * 5 - 3600_000
    items.append({k: v for k, v in item.items() if v not in (None, [], "")})
    print(item["title"], len(eps), "eps", item.get("logo"), file=sys.stderr)

lib = {"schema": 1, "generatedAt": now, "items": items}
out = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/demo-library.json"
with open(out, "w") as f:
    json.dump(lib, f, ensure_ascii=False, indent=1)
print("wrote", out, len(items), file=sys.stderr)
