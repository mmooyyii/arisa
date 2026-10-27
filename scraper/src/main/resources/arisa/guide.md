# 怎么判断一部作品

## 规矩

- **不要下载视频**. 认不出来时只能看夸克生成的缩略图 (`thumbnail` 命令, 是一张图片).
- **TMDB ID 只能来自 `tmdb search` / `tmdb show` 的结果**, 不要凭记忆写 ID.
- **拿不准就不要 decide**. 宁可留着待识别, 也不要识别错.
- 命令输出 JSON 的部分在 stdout, 日志在 stderr.

## 待识别列表的字段

每一项是规则猜出来的一部作品:
- `key`: 默认用来 decide 的 fid (剧集是剧集目录, 电影是视频文件)
- `guess`: 规则猜的 movie / tv, **可能是错的**
- `path`, `fileCount`, `samples`: 路径和示例文件名
- `dirs`: 从片库往下每一级目录的 fid 和名字, 想把整个目录映射过去时从这里取 fid
- `rules`: 规则解析出的片名、年份、季集
- `candidates`: 规则在 TMDB 上搜到的候选 (target 形如 `tv:1396`), 只是参考
- `sampleFid`: 其中一个视频的 fid

## 逐部判断

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

## 常见情况

- **剧场版**: 是电影, 即使放在剧集目录里, 也要单独映射到 `movie:ID`.
- **OVA / SP / 特别篇**: TMDB 的剧集里有第 0 季 (特别篇) 时, 映射到剧集并 `--season 0`; 没有就 `skip`.
- **OP/ED、PV、菜单、特典、彩蛋、花絮** 规则没过滤掉的: `skip`.
- **同一集多个压制组版本**: 不用处理, 同一集会有多个文件, TV 上可以选.
- **季目录用绝对集数** (第二季目录里是 26~50 集): 不用处理, 生成索引时会按 TMDB 每季集数换算.
- **名字被打乱、完全看不出是什么**: 先看缩略图
  ```sh
  arisa-scraper thumbnail --fid <sampleFid> /tmp/arisa-thumb.jpg
  ```
  然后用 Read 打开图片判断. 还是没把握就不 decide.
