import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Small schedule backend: authoritative UTC time + signed, versioned manifest + schedule windows.
// Uses only the JDK's built-in HTTP server and the shared :core engine, so the server's schedule is
// byte-for-byte the schedule every app computes from the same manifest.
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.channelsurf.server.MainKt")
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
}
