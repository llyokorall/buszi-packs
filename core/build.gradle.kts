// Plain Kotlin/JVM code shared by the Android app and the pack builder:
// GTFS reading, the compact timetable format and the list of supported regions.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

tasks.withType<Test>().configureEach {
    // Optional real-feed test: GTFS_ZIP=/path/to/gtfs.zip
    System.getenv("GTFS_ZIP")?.let { environment("GTFS_ZIP", it) }
}

dependencies {
    testImplementation(libs.junit)
}
