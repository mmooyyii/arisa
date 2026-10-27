---
name: arisa-scrape
description: 刮削夸克网盘里的影视库 (Arisa 项目). 识别还没识别的电影/剧集对应 TMDB 哪部作品, 写进映射表, 生成安卓 TV 用的片库索引, 并按需把目录改成规范名字. 用户说「刮削」「刮一下新片」「整理片库」「识别一下 xxx」「片库改名」「Arisa 片库」时使用.
---

# Arisa 刮削

判断「这是哪部片」由你来做; 扫描网盘、查 TMDB、生成索引、改名这些确定性的操作由命令行工具 `arisa-scraper` 完成.
你的结论写进映射表 (fid → TMDB 作品), 映射按 fid 记录, 目录改名后依然有效.

## 规矩

- 识别相关的规矩见 `arisa-scraper guide` (不下载视频、TMDB ID 只能来自工具输出、拿不准不 decide).
- **改名必须先给用户看计划, 用户确认后才能 `rename --apply`**.
- 映射可以随时改 (`decide` 覆盖、`undecide` 删除), 不是破坏性操作, 可以直接做.

## 流程

### 1. 检查登录
```sh
arisa-scraper whoami
```
网页端失效时让用户重新登录 (见文末), 不要继续.

### 2. 先自动处理确定的
```sh
arisa-scraper auto
```
会自动接受: 视频旁已有 NFO 的, 以及 TMDB 上只有一个候选且片名年份完全对上的.

### 3. 拿到待识别列表
```sh
arisa-scraper pending --candidates
```
每一项是规则猜出来的一部作品, 字段含义见 `arisa-scraper guide`.
列表为空就直接跳到第 5 步.

### 4. 逐部判断并 decide

先看识别规则 (和 mac mini 上服务里的 AI 用的是同一份):
```sh
arisa-scraper guide
```
按规则对每一项判断、用 TMDB 确认、`decide`. 拿不准的不 decide, 最后列出来问用户.

一批做完后可以再跑一次 `pending` 确认剩下的都是你打算问用户的.

### 5. 生成索引
```sh
arisa-scraper build
```
按映射表生成 TV 端读取的 `/.arisa/library-*.json.gz` 和 NFO, 内容没变不会上传.
输出会告诉你还有多少视频待识别、有多少目录建议改名.

### 6. 改名 (需要用户确认)
```sh
arisa-scraper rename            # 生成计划, 不改网盘
```
把计划 (原名 → 新名, 以及跳过的和原因) 整理给用户看, **得到确认后**:
```sh
arisa-scraper rename --apply
arisa-scraper build             # 路径变了, 重新生成索引
```
用户只想改其中一部分时, 编辑 `~/.arisa/rename-plan.txt` 删掉不改的行再 apply.

### 7. 汇报
- 这次识别了哪些 (片名 + 年份), 哪些是看缩略图认出来的
- 拿不准、需要用户确认的 (附上路径和你的猜测)
- 改名情况, 以及索引是否已更新

## 增量

已经映射过的剧集目录里新加集数不需要再识别. mac mini 上跑着 `arisa-scraper serve` 时, 新作品会自动识别,
拿不准的在它的管理网页 (http://macmini.local:7893) 上确认, 一般不需要再跑这个 skill.

## 登录失效

`whoami` 显示网页端失效, 或命令报「夸克登录失效」时: 让用户在浏览器打开 pan.quark.cn 扫码登录,
从开发者工具复制任一 `drive-pc.quark.cn` 请求的 Cookie, 运行 `arisa-scraper login` 粘贴进去.
