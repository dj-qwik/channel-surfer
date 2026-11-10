import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin domain module: scheduling engine, manifest codec, Archive parsing, retry policy.
// No Android dependencies, so every rule here is unit-testable on any JVM.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Tree API only (JsonElement); no serialization compiler plugin required.
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
