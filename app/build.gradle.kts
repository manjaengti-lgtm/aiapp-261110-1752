plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fakelocation.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fakelocation.app"
        minSdk = 24
        targetSdk = 34
        // Each build gets a higher version so it installs as an update.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // One permanent signing key, so every new APK installs over the old one.
    val ksFile = System.getenv("FAKELOC_KEYSTORE")?.let { file(it) }
    signingConfigs {
        create("release") {
            if (ksFile != null && ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("FAKELOC_KEY_PASS")
                keyAlias = "fakelocation"
                keyPassword = System.getenv("FAKELOC_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile != null && ksFile.exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Map (OpenStreetMap, no API key needed)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Fused location mock (what WhatsApp, Google Maps etc. read)
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
