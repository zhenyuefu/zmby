// Development only: stands in for the Zidoo system player on an emulator. Never ship it.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.android.gallery3d"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.android.gallery3d"
        minSdk = 28
        targetSdk = 30
        versionCode = 1
        versionName = "fake"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
