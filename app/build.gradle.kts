import java.util.Properties
import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.secrets)
}

android {
  namespace = "agu.analys"
  compileSdk = 36
  defaultConfig {
    applicationId = "agu.analys"
    minSdk = 24
    targetSdk = 35
    versionCode = providers.gradleProperty("VERSION_CODE").map(String::toInt).getOrElse(35)
    versionName = "2.1.7"

    // Keys TIDAK di-embed ke BuildConfig agar tidak bocor di APK.
    // User isi via Settings → disimpan di SharedPreferences (yang sudah di-exclude backup).
    buildConfigField("String", "GEMINI_API_KEY", "\"\"")
    buildConfigField("String", "GROQ_API_KEY", "\"\"")
  }
  signingConfigs {
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    create("release") {
      val storePassword = System.getenv("RELEASE_STORE_PASSWORD")
      val keyAlias = System.getenv("RELEASE_KEY_ALIAS")
      val keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
      var keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
      val keystoreBase64 = System.getenv("RELEASE_KEYSTORE_BASE64")

      if (!keystoreBase64.isNullOrBlank()) {
        val tempKeystore = file("${rootDir}/release_temp.keystore")
        if (!tempKeystore.exists()) {
          try {
            val bytes = Base64.getDecoder().decode(keystoreBase64.trim())
            tempKeystore.writeBytes(bytes)
          } catch (e: Exception) {
            System.err.println("Gagal mendekode RELEASE_KEYSTORE_BASE64: ${e.message}")
          }
        }
        keystorePath = tempKeystore.absolutePath
      }

      if (!keystorePath.isNullOrBlank() && !storePassword.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
        storeFile = file(keystorePath)
        this.storePassword = storePassword
        this.keyAlias = keyAlias
        this.keyPassword = keyPassword
      }
    }
  }
  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      signingConfig = signingConfigs.getByName("release")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      isMinifyEnabled = false
      val hasReleaseKeys = !System.getenv("RELEASE_KEYSTORE_BASE64").isNullOrBlank() || !System.getenv("RELEASE_KEYSTORE_PATH").isNullOrBlank()
      signingConfig = if (hasReleaseKeys) signingConfigs.getByName("release") else signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  buildFeatures { compose = true; buildConfig = true }
  packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
  ignoreList.add("GROQ_API_KEY")
  ignoreList.add("GEMINI_API_KEY")
  ignoreList.add("DEEPSEEK_API_KEY")
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.coil.compose)
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
