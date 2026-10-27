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
    implementation("com.anthropic:anthropic-java:2.34.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("quarkflix.scraper.MainKt")
    applicationName = "quarkflix-scraper"
}

tasks.test { useJUnitPlatform() }
