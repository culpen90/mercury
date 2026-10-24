plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.unsupportedpastels.mercury.core"
  compileSdk = 37

  defaultConfig {
    minSdk = 29
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    // Match the app's compile-only API 37 and AGP version-policy exceptions.
    disable += "AndroidGradlePluginVersion"
    disable += "OldTargetApi"
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(libs.cryptography.core)
  implementation(libs.cryptography.random)
  implementation(libs.cryptography.provider.jdk)
  implementation(libs.bcprov)
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.kotlin.test.junit)
}
