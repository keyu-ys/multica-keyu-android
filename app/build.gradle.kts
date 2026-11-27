import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, default: String = ""): String =
    (project.findProperty(key) as? String)?.trim()
        ?: localProps.getProperty(key)?.trim()
        ?: default

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.mtk.keyu"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mtk.keyu"
        minSdk = 29
        targetSdk = 36
        versionCode = 2026112702
        versionName = "2026.11.27.2"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        buildConfigField("String", "DEFAULT_SERVER", prop("multica.defaultServer", "").asBuildConfigString())
        buildConfigField("String", "DEFAULT_SLUG", prop("multica.defaultSlug", "").asBuildConfigString())
        buildConfigField("String", "DEFAULT_OTA_ISSUE_ID", prop("multica.otaIssueId", "").asBuildConfigString())
    }

    val fixedStore = rootProject.file(prop("signing.storeFile", "signing/release.keystore"))
    if (fixedStore.exists()) {
        signingConfigs {
            create("fixed") {
                storeFile = fixedStore
                storePassword = prop("signing.storePassword", "android")
                keyAlias = prop("signing.keyAlias", "androiddebugkey")
                keyPassword = prop("signing.keyPassword", "android")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (fixedStore.exists()) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (fixedStore.exists()) {
                signingConfig = signingConfigs.getByName("fixed")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui:1.9.0")
    implementation("androidx.compose.foundation:foundation:1.9.0")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.savedstate:savedstate:1.4.0")
    implementation("androidx.customview:customview-poolingcontainer:1.0.0")
}
