import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Signing secrets stay outside the repository. Without this file, release builds are unsigned.
val signingPropertiesFile = providers.environmentVariable("ZMBY_SIGNING_PROPERTIES").orNull?.let(::File)
val releaseSigning = signingPropertiesFile?.let { path ->
    require(path.isFile) { "ZMBY_SIGNING_PROPERTIES must name an existing private properties file" }
    Properties().apply { path.inputStream().use(::load) }
}

android {
    namespace = "app.zmby"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.zmby"
        // Zidoo Z9X 8K runs Android 11 (API 30).
        minSdk = 28
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = signingPropertiesFile!!.absoluteFile.parentFile.resolve(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 (architecture: 线程与性能约定); the baseline profile ships in src/main/baseline-prof.txt.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Sideloaded onto the Zidoo box (Android 11); targetSdk 30 is deliberate, not a Play upload.
        disable += "ExpiredTargetSdkVersion"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Room DAO and migration tests run on Robolectric; they read the exported schemas as assets.
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // Robolectric reads the app's merged assets: the debug build carries the schema JSON for SchemaTest.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

room {
    // Exported schemas are checked in: migrations are tested against them (local-data spec).
    schemaDirectory("$projectDir/schemas")
}

composeCompiler {
    // Emby models and server references are immutable data: let Compose skip unchanged rows.
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose-stability.conf"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    // Han → pinyin (all readings); upstream source declares GPL-2.0-or-later (see THIRD_PARTY_NOTICES.md).
    implementation(libs.pinyin4j)
    // QR code for the phone-side server import page; Apache 2.0.
    implementation(libs.zxing.core)
    // Debug-build frame timing (JankStats) and installing the baseline profile on sideloaded builds.
    implementation(libs.metrics.performance)
    implementation(libs.profileinstaller)
    // The local store (local-data spec): metadata, lists, favourites, watchlist, data sources.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
}
