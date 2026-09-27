plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.kachrafreeresident"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kachrafree.resident"
        // 26 (Android 8.0): notification channels, used for truck alerts,
        // don't exist below this.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core:1.18.0")
    // OpenStreetMap maps: free, no API key, no account.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
