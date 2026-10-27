# Arisa

夸克网盘 + AI 刮削 + 安卓 TV 播放器.

```
mac mini (刮削器, 常驻)  ──写──▶  夸克网盘  ◀──读──  安卓 TV (展示 + 播放)
                          /.arisa/library-*.json.gz   (汇总索引)
                          视频旁边的 .nfo                  (可选, Kodi/Jellyfin/Infuse 通用)
```

TV 和 mac mini 不直接通信, TV 只需要扫码登录夸克.

## 模块

| 模块        | 说明                                                                                                                                       |
|-----------|------------------------------------------------------------------------------------------------------------------------------------------|
| `quark`   | 夸克客户端. `QuarkTvClient`: TV 端开放接口 (扫码登录、列目录、直链、转码流); `QuarkWebClient`: 网页端 Cookie 接口 (额外支持上传/删除). 参考 OpenList 的 quark_uc_tv / quark_uc 驱动 |
| `library` | 索引数据结构 `Library`, 两端共用                                                                                                                   |
| `scraper` | 刮削器命令行, 跑在 mac mini 上                                                                                                                    |
| `app`     | 安卓 TV App (Compose for TV + Media3)                                                                                                      |

## 刮削器

```sh
./gradlew :scraper:installDist
cp config.example.yaml ~/.arisa/config.yaml   # 填 tmdb.api_key 和片库目录
S=scraper/build/install/arisa-scraper/bin/arisa-scraper
$S login                 # 粘贴 pan.quark.cn 的 Cookie
$S ls /电影              # 确认能列目录
$S serve                 # 常驻服务, 管理网页 http://localhost:7893
```

常驻服务 (`serve`) 的流程:

1. 每 90 秒看一次夸克的「最近」(转存、上传), 落在片库里的目录让扫描缓存失效, 增量扫描; 每天凌晨全量扫一次 (移动、删除在「最近」里看不到).
2. 规则能确定的直接记进映射表: 视频旁已有 NFO, 或 TMDB 上只有一个候选且片名年份完全对上.
3. 其余交给 DeepSeek 识别 (规则见 `arisa-scraper guide`), AI 拿不准的放进「待确认」. 花费按 DeepSeek 余额的变化记, 管理网页上显示今天花了多少.
4. 按映射表生成 `/.arisa/library-*.json.gz` 和 NFO, 生成改名计划.
5. 待确认和改名在管理网页上处理, 不发通知.

日本动画的片名用 Bangumi (bgm.tv) 的中文名, 按原名和年份自动匹配, 匹配不对时在映射里用 `--bangumi ID` 指定.

识别结论存在映射表 (fid → TMDB 作品), 本地 `~/.arisa/mapping.json`, 网盘 `/.arisa/mapping.json` 备份.
给剧集目录做过映射后, 往里加新集数不用再识别. 不开服务时也可以在 Claude Code 里用 `arisa-scrape` skill 手动刮削.

输出模式 (`output`, 可按目录覆盖):

- `index`: 只写汇总索引
- `index+nfo`: 加电影 NFO 和 tvshow.nfo, 图片写远程 URL
- `index+full`: 加单集 NFO 和全部图片

## TV App

```sh
./gradlew :app:installDebug
```

首次打开扫码登录夸克; 没有片库时可以先点"演示模式"看界面 (内置数据由 `tools/make_demo.py` 生成).

播放器 (遥控器):

- 确定: 暂停 / 继续; 左右: 快退快进, 按住越跳越快; 下: 字幕、音轨、画面、剧集、信息面板; 返回: 先关面板再退出
- 音轨标出语言、编码 (TrueHD / DD+ Atmos / DTS-HD …) 和声道; 杜比音频优先直通给电视/功放, 设备解不了又不能直通时用 FFmpeg 软解
- 字幕: 内封字幕 + 视频旁边的外挂字幕 (srt/ass/ssa/vtt, GBK/Big5 自动转码), 默认中文; 每部作品记住上次选的音轨和字幕
- 剧集最后 30 秒提示下一集, 播完自动接着播; 直链过期或断网时换新链接从原位置接着播
- 设置里可以开「自动切换刷新率」(24p 片源切 24Hz)
