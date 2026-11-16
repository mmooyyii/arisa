#!/bin/sh
# 按 CPU 选同目录下对应的刮削器程序. 安装包里同时带了 arm64 (Apple 芯片) 和 amd64 (Intel) 两个.
# 自动更新、install.sh、launchd 都只认 bin/arisa-scraper 这个名字, 不要改.
self=$0
while [ -L "$self" ]; do
    link=$(readlink "$self")
    case $link in
        /*) self=$link ;;
        *) self=$(dirname "$self")/$link ;;
    esac
done
dir=$(cd "$(dirname "$self")" && pwd -P)
case $(uname -m) in
    arm64 | aarch64) arch=arm64 ;;
    *) arch=amd64 ;;
esac
exec "$dir/arisa-scraper-$(uname -s | tr '[:upper:]' '[:lower:]')-$arch" "$@"
