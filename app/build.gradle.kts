import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val arisaVersion: String by rootProject.extra
val arisaVersionCode: Int by rootProject.extra

/**
 * 正式签名: CI 从环境变量读, 本地从 ~/.android/arisa-release.properties 读.
 * 都没有时 release 包用 debug 签名, 只能自己装着玩, 不能覆盖安装正式版.
 */
val releaseSigning: Properties? = run {
    val env = System.getenv()
    if (env["ARISA_KEYSTORE"] != null) {
        Properties().apply {
            setProperty("storeFile", env.getValue("ARISA_KEYSTORE"))
            setProperty("storePassword", env.getValue("ARISA_KEYSTORE_PASSWORD"))
            setProperty("keyAlias", env["ARISA_KEY_ALIAS"] ?: "arisa")
            setProperty("keyPassword", env["ARISA_KEY_PASSWORD"] ?: env.getValue("ARISA_KEYSTORE_PASSWORD"))
        }
    } else {
        File(System.getProperty("user.home"), ".android/arisa-release.properties").takeIf { it.exists() }
            ?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    }
}

android {
    namespace = "arisa.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "arisa.app"
        minSdk = 23
        targetSdk = 35
        versionCode = arisaVersionCode
        versionName = arisaVersion
    }

    signingConfigs {
        releaseSigning?.let { p ->
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // 电视盒子都是 ARM; x86 的 FFmpeg 库只有模拟器用得上, debug 包里留着
            // -Parisa.allAbis 时全带上, 在模拟器上验证 release 包用
            if (!project.hasProperty("arisa.allAbis")) ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // 运行时用不到的元数据和许可证文本
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/androidx/**", "/META-INF/*.version", "/META-INF/*.kotlin_module",
            "/kotlin/**", "DebugProbesKt.bin",
        )
    }
    // Google Play 用的依赖清单, 自己分发用不上
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":quark"))
    implementation(project(":library"))

    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    // 网页版扫码登录只给链接, 二维码自己画
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    // FFmpeg 音频解码 (TrueHD / DTS / AC3 等), 设备解不了又不能直通时用. 官方没发 Maven, 用 Jellyfin 打包的, 版本跟 media3 对齐
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
}
