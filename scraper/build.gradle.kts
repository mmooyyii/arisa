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
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("arisa.scraper.MainKt")
    applicationName = "arisa-scraper"
}

tasks.test { useJUnitPlatform() }
