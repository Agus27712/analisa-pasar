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
    versionCode = providers.gradleProperty("VERSION_CODE").map(String::toInt).getOrElse(8)
    versionName = "1.2.0"
  }
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
      val storePassword = System.getenv("RELEASE_STORE_PASSWORD")
      val keyAlias = System.getenv("RELEASE_KEY_ALIAS")
      val keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
      if (!keystorePath.isNullOrBlank() && !storePassword.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
        storeFile = file(keystorePath); this.storePassword = storePassword; this.keyAlias = keyAlias; this.keyPassword = keyPassword
      }
    }
  }
  buildTypes {
    release { isMinifyEnabled = false; isShrinkResources = false; signingConfig = signingConfigs.getByName("release"); proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    debug { isMinifyEnabled = false }
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
