# Quarkflix

夸克网盘 + AI 刮削 + 安卓 TV 播放器.

```
mac mini (刮削器, 常驻)  ──写──▶  夸克网盘  ◀──读──  安卓 TV (展示 + 播放)
                          /.quarkflix/library-*.json.gz   (汇总索引)
                          视频旁边的 .nfo                  (可选, Kodi/Jellyfin/Infuse 通用)
```

TV 和 mac mini 不直接通信, TV 只需要扫码登录夸克.

## 模块

| 模块 | 说明 |
|---|---|
| `quark` | 夸克客户端. `QuarkTvClient`: TV 端开放接口 (扫码登录、列目录、直链、转码流); `QuarkWebClient`: 网页端 Cookie 接口 (额外支持上传/删除). 参考 OpenList 的 quark_uc_tv / quark_uc 驱动 |
| `library` | 索引数据结构 `Library`, 两端共用 |
| `scraper` | 刮削器命令行, 跑在 mac mini 上 |
| `app` | 安卓 TV App (Compose for TV + Media3) |

## 刮削器

```sh
./gradlew :scraper:installDist
cp config.example.yaml ~/.quarkflix/config.yaml   # 填 tmdb.api_key、ai 配置、片库目录
S=scraper/build/install/quarkflix-scraper/bin/quarkflix-scraper
$S login                 # 粘贴 pan.quark.cn 的 Cookie
$S ls /电影              # 确认能列目录
$S scan --dry-run        # 只识别不写网盘, 看 ~/.quarkflix/report.txt
$S scan                  # 写索引和 NFO
$S daemon                # 常驻定时扫描
```

识别流程: 人工修正 (`overrides`) → 已有 NFO → 上次识别结果 → 规则解析文件名 / AI 解析 → TMDB 搜索 → 规则或 AI 在候选里选.
AI 只做"解析文件名"和"在 TMDB 候选里选一个", 不生成元数据.

输出模式 (`output`, 可按目录覆盖):
- `index`: 只写汇总索引
- `index+nfo`: 加电影 NFO 和 tvshow.nfo, 图片写远程 URL
- `index+full`: 加单集 NFO 和全部图片

## TV App

```sh
./gradlew :app:installDebug
```

首次打开扫码登录夸克; 没有片库时可以先点"演示模式"看界面 (内置数据由 `tools/make_demo.py` 生成).
