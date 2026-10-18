import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.isFile) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
val serverBaseUrl = providers.gradleProperty("serverBaseUrl").orNull
    ?: localProperties.getProperty("serverBaseUrl")
    ?.trim()
    ?.trimEnd('/')
    ?: throw GradleException(
        "Missing serverBaseUrl in resident-app/local.properties. Set it to the server URL."
    )

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
        buildConfigField("String", "SERVER_BASE_URL", "\"$serverBaseUrl\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
