plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
}

/**
 * 版本号: 发版时 CI 从标签 v1.2.3 传进来 (-Parisa.version=1.2.3), 本地编译是 0.0.0-dev.
 * 安卓的 versionCode 由它算出来: 1.2.3 → 1002003, 所以每段不能超过 999.
 */
val arisaVersion = (findProperty("arisa.version") as String?) ?: "0.0.0-dev"
val arisaVersionCode = arisaVersion.substringBefore('-').split('.').map(String::toInt)
    .also { parts -> require(parts.size == 3 && parts.all { it in 0..999 }) { "版本号要写成 x.y.z: $arisaVersion" } }
    .let { (major, minor, patch) -> (major * 1_000_000 + minor * 1_000 + patch).coerceAtLeast(1) }
extra["arisaVersion"] = arisaVersion
extra["arisaVersionCode"] = arisaVersionCode
