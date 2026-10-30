package arisa.library

import kotlinx.serialization.Serializable

/**
 * 发布清单 `latest.json`. 两处有:
 * - GitHub Release 的附件, 刮削器自动更新时读 (见 [GITHUB_LATEST]);
 * - 刮削器转存到网盘 `/.arisa/app/latest.json`, 只带 [app], TV 检查更新时读.
 *
 * 旧版刮削器和旧版 App 会一直读这个文件: 只能加带默认值的字段, 不能删字段、改名、改含义.
 */
@Serializable
data class ReleaseManifest(
    /** "1.2.3", 不带 v. */
    val version: String,
    /** 由 [version] 算出来, 和安卓的 versionCode 一样, 比大小用这个. */
    val versionCode: Int,
    /** 毫秒. */
    val publishedAt: Long = 0,
    /** 更新说明, 几行文字. */
    val notes: String? = null,
    val scraper: ReleaseAsset? = null,
    val app: ReleaseAsset? = null,
) {
    companion object {
        const val FILE = "latest.json"
        /** 网盘里放 TV App 的目录, 在 `/.arisa/` 下面. */
        const val APP_DIR = "app"
        const val REPO = "mmooyyii/arisa"
        const val GITHUB_LATEST = "https://github.com/$REPO/releases/latest/download/$FILE"

        fun assetUrl(version: String, name: String) = "https://github.com/$REPO/releases/download/v$version/$name"

        /** "1.2.3" → 1002003, 和 build.gradle.kts 里算法一样. 不是 x.y.z 的 (开发版) 返回 0. */
        fun versionCode(version: String): Int {
            val parts = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: return 0 }
            if (parts.size != 3 || parts.any { it !in 0..999 } || '-' in version) return 0
            return parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
        }
    }
}

/** 一个发布文件. [name] 是 GitHub Release 里 (或网盘目录里) 的文件名. */
@Serializable
data class ReleaseAsset(
    val name: String,
    val size: Long,
    val sha256: String,
)
