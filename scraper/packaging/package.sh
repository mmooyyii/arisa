#!/bin/sh
# 打刮削器安装包: scraper/build/arisa-scraper.zip
#   sh scraper/packaging/package.sh [版本号]      不带版本号是 0.0.0-dev
# 包的结构 (自动更新和 install.sh 按这个装, 不能改):
#   arisa-scraper-<版本>/bin/arisa-scraper         按 CPU 选下面两个之一
#   arisa-scraper-<版本>/bin/arisa-scraper-darwin-arm64
#   arisa-scraper-<版本>/bin/arisa-scraper-darwin-amd64
set -eu
cd "$(dirname "$0")/.."
VERSION=${1:-0.0.0-dev}
OUT=build
STAGE=$OUT/arisa-scraper-$VERSION
rm -rf "$STAGE" "$OUT/arisa-scraper.zip"
mkdir -p "$STAGE/bin"
for arch in arm64 amd64; do
    CGO_ENABLED=0 GOOS=darwin GOARCH=$arch go build -trimpath -ldflags "-s -w -X main.version=$VERSION" \
        -o "$STAGE/bin/arisa-scraper-darwin-$arch" .
done
# 本机是 linux 时顺带编一个, 方便在 CI 里试运行
if [ "$(uname -s)" = Linux ]; then
    arch=$(go env GOARCH)
    CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=$VERSION" -o "$STAGE/bin/arisa-scraper-linux-$arch" .
fi
cp packaging/arisa-scraper.sh "$STAGE/bin/arisa-scraper"
chmod +x "$STAGE/bin/"*
(cd "$OUT" && zip -qry arisa-scraper.zip "arisa-scraper-$VERSION")
# 包里的程序要能打印出版本号, 自动更新靠这个试运行
if [ "$(uname -s)" = Darwin ] || [ "$(uname -s)" = Linux ]; then
    got=$("$STAGE/bin/arisa-scraper" version | tail -1)
    [ "$got" = "$VERSION" ] || { echo "试运行失败: $got"; exit 1; }
fi
rm -f "$STAGE/bin/arisa-scraper-linux-"*
(cd "$OUT" && rm -f arisa-scraper.zip && zip -qry arisa-scraper.zip "arisa-scraper-$VERSION")
echo "$OUT/arisa-scraper.zip"
