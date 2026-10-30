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

在 mac mini 上安装 (需要 JDK 17+), 装好后作为 launchd 常驻服务运行, 之后自动更新:

```sh
curl -fsSL https://raw.githubusercontent.com/mmooyyii/arisa/main/deploy/install.sh | sh
# 填 ~/.arisa/config.yaml 里的 tmdb.api_key 和片库目录, 然后:
arisa-scraper login      # 终端扫码登录夸克
arisa-scraper ls /电影   # 确认能列目录
launchctl kickstart -k gui/$(id -u)/com.arisa.scraper   # 重启服务, 管理网页 http://localhost:7893
```

自动更新: 常驻服务每 6 小时看一次 GitHub 上的最新版本, 有新版就下载到 `~/.arisa/versions/<版本>`,
等手上的任务做完后切换 `~/.arisa/current` 重启; 同时把新版 TV App 转存到网盘 `/.arisa/app/`.
`arisa-scraper update` 马上检查一次; 配置见 `config.example.yaml` 的 `update`.
新版出问题时可以手动切回上一版: `ln -sfn versions/<旧版本> ~/.arisa/current` 后重启服务.

开发时直接用源码跑 (不会自动更新):

```sh
./gradlew :scraper:installDist
scraper/build/install/arisa-scraper/bin/arisa-scraper serve
```

常驻服务 (`serve`) 的流程:

1. 每 90 秒看一次夸克的「最近」(转存、上传), 落在片库里的目录让扫描缓存失效, 增量扫描; 每天凌晨全量扫一次 (移动、删除在「最近」里看不到).
2. 规则能确定的直接记进映射表: 视频旁已有 NFO, 或 TMDB 上只有一个候选且片名年份完全对上.
3. 其余交给 DeepSeek 识别 (规则见 `arisa-scraper guide`), AI 拿不准的放进「待确认」. 花费按 DeepSeek 余额的变化记, 管理网页上显示今天花了多少.
4. 按映射表生成 `/.arisa/library-*.json.gz` 和 NFO, 生成改名计划.
5. 待确认和改名在管理网页上处理, 不发通知.

管理网页 (http://localhost:7893, 只在局域网用, 没有密码):

- 待确认: AI 拿不准的作品, 搜 TMDB 点一下就定
- 片库: 已识别的作品和对应的网盘目录, 认错了可以改成别的或撤销; 未识别的直接手动认; 跳过的可以撤销
- 改名 / 动态: 改名计划和最近的日志
- 设置: 片库目录、TMDB / DeepSeek key、代理、输出模式、自动更新等, 存回 `~/.arisa/config.yaml`
- 版本与更新: 当前版本、GitHub 最新版、网盘里的 TV App 版本, 可以马上检查一次
- 夸克登录失效时页面上直接扫码重新登录

日本动画的片名用 Bangumi (bgm.tv) 的中文名, 按原名和年份自动匹配, 匹配不对时在映射里用 `--bangumi ID` 指定.

识别结论存在映射表 (fid → TMDB 作品), 本地 `~/.arisa/mapping.json`, 网盘 `/.arisa/mapping.json` 备份.
给剧集目录做过映射后, 往里加新集数不用再识别. 不开服务时也可以在 Claude Code 里用 `arisa-scrape` skill 手动刮削.

输出模式 (`output`, 可按目录覆盖):

- `index`: 只写汇总索引
- `index+nfo`: 加电影 NFO 和 tvshow.nfo, 图片写远程 URL
- `index+full`: 加单集 NFO 和全部图片

## TV App

从 [Releases](https://github.com/mmooyyii/arisa/releases) 下载 `arisa-<版本>.apk` 装到电视上 (U 盘或 `adb install`).
之后的版本由刮削器转存到网盘, 在 TV 的「设置 → 检查更新」里下载安装 (第一次要在系统设置里允许 Arisa 安装应用).

开发:

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

## 发版

推一个 `v` 开头的标签, GitHub Actions 打包刮削器和 TV App 发到 Releases, 刮削器会自动更新过去:

```sh
git tag v0.2.0 && git push origin v0.2.0
```

不同版本的刮削器和 App 会同时存在, 改索引格式前先看 `CLAUDE.md` 里的兼容规则.
