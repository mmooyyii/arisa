#!/bin/sh
# 在 mac mini 上安装刮削器 (之后由它自己自动更新).
#   sh install.sh                 装 GitHub 上的最新版
#   sh install.sh 0.2.0           装指定版本
#   sh install.sh xxx.zip         装本地的包 (./gradlew :scraper:distZip 打出来的)
# 需要 JDK 17+. GitHub 直连不稳时先 export https_proxy=http://127.0.0.1:7890
set -eu

REPO=mmooyyii/arisa
ARISA_HOME=${ARISA_HOME:-$HOME/.arisa}
VERSIONS=$ARISA_HOME/versions
BIN_DIR=${BIN_DIR:-$HOME/.local/bin}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)}
[ -n "$JAVA_HOME" ] || { echo "找不到 JDK 17+, 先装一个 (brew install openjdk@21) 或设置 JAVA_HOME"; exit 1; }

if [ $# -gt 0 ] && [ -f "$1" ]; then
    cp "$1" "$TMP/scraper.zip"
else
    if [ $# -gt 0 ]; then url=https://github.com/$REPO/releases/download/v$1/latest.json
    else url=https://github.com/$REPO/releases/latest/download/latest.json; fi
    curl -fsSL "$url" -o "$TMP/latest.json"
    version=$(plutil -extract version raw -o - "$TMP/latest.json")
    name=$(plutil -extract scraper.name raw -o - "$TMP/latest.json")
    sha=$(plutil -extract scraper.sha256 raw -o - "$TMP/latest.json")
    echo "下载 $name"
    curl -fL --progress-bar "https://github.com/$REPO/releases/download/v$version/$name" -o "$TMP/scraper.zip"
    echo "$sha  $TMP/scraper.zip" | shasum -a 256 -c - >/dev/null || { echo "sha256 校验失败"; exit 1; }
fi

unzip -q "$TMP/scraper.zip" -d "$TMP/unpacked"
dir=$(ls -d "$TMP"/unpacked/*/ | head -1)
version=$(JAVA_HOME=$JAVA_HOME "$dir/bin/arisa-scraper" version | tail -1)
mkdir -p "$VERSIONS" "$ARISA_HOME/logs" "$BIN_DIR"
rm -rf "${VERSIONS:?}/$version"
mv "$dir" "$VERSIONS/$version"
ln -sfn "versions/$version" "$ARISA_HOME/current"
ln -sfn "$ARISA_HOME/current/bin/arisa-scraper" "$BIN_DIR/arisa-scraper"
echo "已安装 $version → $VERSIONS/$version"

[ -f "$ARISA_HOME/config.yaml" ] || {
    curl -fsSL "https://raw.githubusercontent.com/$REPO/v$version/config.example.yaml" -o "$ARISA_HOME/config.yaml" 2>/dev/null \
        && echo "已生成配置 $ARISA_HOME/config.yaml, 记得填 tmdb.api_key 和片库目录"
}

# launchd 常驻: 退出后自动拉起, 自动更新时就是靠它用新版重启
[ "${SKIP_LAUNCHD:-}" = 1 ] && exit 0
plist=$HOME/Library/LaunchAgents/com.arisa.scraper.plist
mkdir -p "$(dirname "$plist")"
curl -fsSL "https://raw.githubusercontent.com/$REPO/v$version/deploy/arisa.plist.template" -o "$TMP/plist" 2>/dev/null \
    || cp "$(dirname "$0")/arisa.plist.template" "$TMP/plist"
sed -e "s|__HOME__|$HOME|g" -e "s|__JAVA_HOME__|$JAVA_HOME|g" "$TMP/plist" > "$plist"
launchctl bootout "gui/$(id -u)/com.arisa.scraper" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$plist"
echo "常驻服务已启动, 管理网页 http://localhost:7893, 日志 $ARISA_HOME/logs/serve.log"
command -v arisa-scraper >/dev/null || echo "把 ~/.local/bin 加进 PATH 就能直接用 arisa-scraper 命令"
