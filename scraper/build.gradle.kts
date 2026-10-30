plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":quark"))
    implementation(project(":library"))
    implementation("com.charleskorn.kaml:kaml:0.104.0")
    // 扫码登录时在终端里画二维码
    implementation("com.google.zxing:core:3.5.3")
    // 生成索引里搜索用的拼音
    implementation("com.belerweb:pinyin4j:2.5.1")
    testImplementation(kotlin("test"))
}

// 版本号写进资源, 自动更新时和最新版比较
val arisaVersion: String by rootProject.extra
val versionResource = tasks.register("versionResource") {
    val out = layout.buildDirectory.dir("generated/version")
    val v = arisaVersion
    inputs.property("version", v)
    outputs.dir(out)
    doLast { out.get().file("arisa/scraper/version.txt").asFile.apply { parentFile.mkdirs() }.writeText(v) }
}
sourceSets.main { resources.srcDir(versionResource) }

application {
    mainClass.set("arisa.scraper.MainKt")
    applicationName = "arisa-scraper"
}

tasks.test { useJUnitPlatform() }
