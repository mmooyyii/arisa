---
name: arisa-scrape
description: 刮削夸克网盘里的影视库 (Arisa 项目). 识别还没识别的电影/剧集对应 TMDB 哪部作品, 写进映射表, 生成安卓 TV 用的片库索引, 并按需把目录改成规范名字. 用户说「刮削」「刮一下新片」「整理片库」「识别一下 xxx」「片库改名」「Arisa 片库」时使用.
---

# Arisa 刮削

判断「这是哪部片」由你来做; 扫描网盘、查 TMDB、生成索引、改名这些确定性的操作由命令行工具 `arisa-scraper` 完成.
你的结论写进映射表 (fid → TMDB 作品), 映射按 fid 记录, 目录改名后依然有效.

## 规矩

- **不要下载视频**. 认不出来时只能看夸克生成的缩略图 (`thumbnail` 命令, 是一张图片).
- **TMDB ID 只能来自 `tmdb search` / `tmdb show` 的结果**, 不要凭记忆写 ID.
- **拿不准就不要 decide**, 最后列出来问用户. 宁可留着待识别, 也不要识别错.
- **改名必须先给用户看计划, 用户确认后才能 `rename --apply`**.
- 映射可以随时改 (`decide` 覆盖、`undecide` 删除), 不是破坏性操作, 可以直接做.
- 命令输出 JSON 的部分在 stdout, 日志在 stderr.

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
每一项是规则猜出来的一部作品:
- `key`: 默认用来 decide 的 fid (剧集是剧集目录, 电影是视频文件)
- `guess`: 规则猜的 movie / tv, **可能是错的**
- `path`, `fileCount`, `samples`: 路径和示例文件名
- `dirs`: 从片库往下每一级目录的 fid 和名字, 想把整个目录映射过去时从这里取 fid
- `rules`: 规则解析出的片名、年份、季集
- `candidates`: 规则在 TMDB 上搜到的候选 (target 形如 `tv:1396`), 只是参考

列表为空就直接跳到第 5 步.

### 4. 逐部判断并 decide

对每一项:
1. 结合目录名、文件名、你自己的知识判断是哪部作品、电影还是剧集.
   资源分享者常把目录名弄乱: 加字母前缀 (`W 無職轉生…`)、加广告、混日文/繁体/英文/拼音、故意打乱字符.
2. 用 TMDB 确认, 换着中文名、原名、英文名搜:
   ```sh
   arisa-scraper tmdb search 无职转生 --tv
   arisa-scraper tmdb search "Mushoku Tensei" --tv --year 2021
   arisa-scraper tmdb show tv 94664      # 看每季集数, 和文件数对一下
   ```
3. 选映射的位置:
   - 剧集: 映射剧集的根目录 (通常就是 `key`). 以后放进这个目录的新集数会自动归进去.
   - 合集目录 (一个目录里装了好几部): **不要映射合集目录本身**, 分别映射每部作品的子目录 (从 `dirs` 里取 fid).
   - 电影: 映射视频文件 (`key`); 一个目录里只放了这一部电影的多个版本时, 可以映射那个目录.
4. 写映射:
   ```sh
   arisa-scraper decide <fid> tv:94664
   arisa-scraper decide <fid> movie:129 --note "宫崎骏合集里的千与千寻"
   arisa-scraper decide <fid> tv:94664 --season 2       # 目录名看不出是第几季时指定
   arisa-scraper decide <fid> skip                      # 不是正片 / 不入库
   ```

常见情况:
- **剧场版**: 是电影, 即使放在剧集目录里, 也要单独映射到 `movie:ID`.
- **OVA / SP / 特别篇**: TMDB 的剧集里有第 0 季 (特别篇) 时, 映射到剧集并 `--season 0`; 没有就 `skip`.
- **OP/ED、PV、菜单、特典、彩蛋、花絮** 规则没过滤掉的: `skip`.
- **同一集多个压制组版本**: 不用处理, 同一集会有多个文件, TV 上可以选.
- **季目录用绝对集数** (第二季目录里是 26~50 集): 不用处理, 生成索引时会按 TMDB 每季集数换算.
- **名字被打乱、完全看不出是什么**: 先看缩略图
  ```sh
  arisa-scraper thumbnail "/没看/某目录/某文件.mkv" /tmp/arisa-thumb.jpg
  ```
  然后用 Read 打开图片判断. 还是没把握就不 decide, 最后问用户.

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

已经映射过的剧集目录里新加集数不需要再识别, mac mini 上的 `arisa-scraper daemon` 会定时 build 自动收进去.
只有出现新作品 (`pending` 不为空) 时才需要跑这个 skill.

## 登录失效

`whoami` 显示网页端失效, 或命令报「夸克登录失效」时: 让用户在浏览器打开 pan.quark.cn 扫码登录,
从开发者工具复制任一 `drive-pc.quark.cn` 请求的 Cookie, 运行 `arisa-scraper login` 粘贴进去.
