// Kotlin Multiplatform code shared by Android, the pack builder and (later) iOS:
// regions, compact timetable format, GTFS-realtime parser, database layout.
//
// iOS targets are declared now so the code stays iOS-ready. They can only be compiled on a Mac;
// on Windows/Linux Gradle skips them. mingwX64 is a Windows native target used here to prove the
// common code has no JVM-only APIs (if it builds for native Windows, it builds for iOS).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    mingwX64()
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach {
        it.binaries.framework {
            baseName = "BusziCore"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

tasks.withType<Test>().configureEach {
    // Optional real-feed test: GTFS_ZIP=/path/to/gtfs.zip
    System.getenv("GTFS_ZIP")?.let { environment("GTFS_ZIP", it) }
}
