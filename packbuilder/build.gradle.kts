// Command-line tool run by GitHub Actions (or locally) to prebuild compact region packs.
// Not part of the Android app.
//   ./gradlew :packbuilder:run --args="--out build/packs"
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.alicehouse.buszi.packbuilder.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.sqlite.jdbc)
}
