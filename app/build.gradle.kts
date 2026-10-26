plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.tabletcomics"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.tabletcomics"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "0.6.4-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
    }
    signingConfigs {
        getByName("debug") {
            val stableKey = rootProject.file(".signing/current.keystore")
            if (stableKey.isFile) {
                storeFile = stableKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("externalRelease") {
            val configuredKey = System.getenv("TABLET_COMICS_KEYSTORE")
            if (!configuredKey.isNullOrBlank()) {
                storeFile = file(configuredKey)
                storePassword = System.getenv("TABLET_COMICS_STORE_PASSWORD")
                keyAlias = System.getenv("TABLET_COMICS_KEY_ALIAS")
                keyPassword = System.getenv("TABLET_COMICS_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (!System.getenv("TABLET_COMICS_KEYSTORE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("externalRelease")
            }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
