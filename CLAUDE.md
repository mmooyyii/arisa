# Arisa 开发约定

## 跨版本兼容 (改代码前必读)

刮削器会自动更新到最新版, TV App 要用户手动更新, 多台电视可能装着不同版本.
所以「新刮削器 + 旧 App」「新 App + 旧文件」一直同时存在. 下面这些文件被不同版本的程序读写:

| 文件 | 谁写 | 谁读 |
|---|---|---|
| 网盘 `/.arisa/library-*.json.gz` (片库索引, `Library`) | 刮削器 | 各版本 TV App |
| 网盘 `/.arisa/app/latest.json` (`ReleaseManifest`) | 刮削器 | 各版本 TV App |
| GitHub Release 的 `latest.json` (`ReleaseManifest`) | CI (`tools/release_manifest.py`) | 各版本刮削器、`deploy/install.sh` |
| 网盘上的观看进度 | 各版本 TV App | 各版本 TV App |
| `~/.arisa/` 下的 config.yaml、mapping.json、state.json 等 | 刮削器 | 升级后 (或回退后) 的刮削器 |

规则:

1. **只加字段, 新字段必须有默认值.** 不删字段、不改名、不改含义 (单位、取值范围、枚举值都算含义).
   读的一方都开着 `ignoreUnknownKeys`, 旧版本会忽略新字段.
2. 要做不兼容的改动时, 把 `Library.SCHEMA` 加一. 旧 App 看到更大的 schema 会停用新索引并提示更新 App,
   但这期间旧 App 就看不到新片子了, 所以尽量别这么做; 真要做, 先发一版能读新格式的 App, 隔一段时间再让刮削器写新格式.
3. 枚举 (如 `MediaType`) 不能加新值: 旧 App 解析到不认识的枚举值会整个索引读失败. 需要新类别时加一个新的可选字段.
4. GitHub Release 的文件名 (`latest.json`, `arisa-scraper-<版本>.zip`, `arisa-<版本>.apk`)、下载地址、
   网盘目录 (`/.arisa/`, `/.arisa/app/`) 都不能改, 旧版本按这些地址找更新.
5. `library/src/test/kotlin/arisa/library/CompatTest.kt` 守着这些规则: 旧版本的样本文件 (`src/test/resources/compat/`)
   只加不删, 格式有变化时把新版本写出的样本也加进去; 必填字段名单只能减不能加.

## 发版

```sh
git tag v0.2.0 && git push origin v0.2.0
```

CI (`.github/workflows/ci.yml`) 打包刮削器和 App, 发 GitHub Release. 版本号只从标签来, 代码里不写死;
x.y.z 每段 0~999, 安卓 versionCode = x*1000000 + y*1000 + z, 只能变大.

App 用 `~/.android/arisa-release.jks` 签名 (CI 里是 secret `ARISA_KEYSTORE_BASE64` / `ARISA_KEYSTORE_PASSWORD`),
换签名会导致已装的 App 没法覆盖安装, 不要换.
