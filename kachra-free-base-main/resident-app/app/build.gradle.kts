import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// The Google Maps API key lives in local.properties (gitignored), never in
// source or manifest directly - see docs/PROTOCOL.md for how to get one.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        load(FileInputStream(file))
    }
}
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY") ?: ""

android {
    namespace = "com.example.kachrafreeresident"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kachrafree.resident"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        manifestPlaceholders["mapsApiKey"] = mapsApiKey
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling (for @Preview in Android Studio)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Maps (house location picker + truck status map). Location search does
  // NOT use the Places SDK client library on purpose - see PlacesApi.kt.
  implementation(libs.play.services.maps)
  implementation(libs.maps.compose)
}
