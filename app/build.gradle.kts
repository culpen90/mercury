import com.android.compose.screenshot.tasks.PreviewScreenshotValidationTask

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.screenshot)
}

android {
    namespace = "com.unsupportedpastels.hermesandroid"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.unsupportedpastels.hermesandroid"
        minSdk = 29
        targetSdk = 36
        // versionCode is CI-driven so every Play upload is unique (Play rejects
        // duplicates). Release CI passes VERSION_CODE=${{ github.run_number }};
        // local builds fall back to 1.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue(
            "string",
            "rambler_beta_endpoint",
            providers.gradleProperty("mercury.ramblerBetaEndpoint")
                .orElse(providers.environmentVariable("MERCURY_RAMBLER_BETA_ENDPOINT"))
                .orElse("")
                .get(),
        )
    }

    // Env-driven upload signing: the release keystore is only materialized in CI
    // from GitHub Secrets (decoded to a temp path). No keystore path -> the block
    // stays empty and the release build is produced unsigned, so contributors
    // without the key aren't broken and no signing material lives in the repo.
    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("UPLOAD_KEYSTORE_PATH")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Opt-in device verification alongside a differently signed release.
            if (providers.gradleProperty("mercury.sideBySideDebug").orNull == "true") {
                applicationIdSuffix = ".debug"
                resValue("string", "app_name", "Mercury (Debug)")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only attach the release signing config when CI supplied the upload
            // keystore; otherwise leave the build unsigned (never the debug key).
            if (System.getenv("UPLOAD_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      resValues = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric downloads SDKs independently of Gradle repositories.
            // Its default repo1 endpoint can return HTTP 403 on hosted CI runners.
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }

    lint {
        // Current AndroidX requires API 37/AGP 9.3.1; Gradle 9.7 currently breaks AGP's lint hint path.
        // API 36 remains the latest stable runtime target while API 37 is compile-only.
        // Keep all code/resource/security analysis and suppress only these two version-policy hints.
        disable += "AndroidGradlePluginVersion"
        disable += "OldTargetApi"
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    sourceSets {
        // Adapter corpora are owned by the core tests; the app adapters
        // read the same fixtures.
        getByName("test").resources.srcDir("../shared/mercury-core/src/test/resources")
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<PreviewScreenshotValidationTask>().configureEach {
    // Tolerate Mac/Linux shadow color rounding at a single pixel, while rejecting
    // meaningful visual changes. The pinned alpha16 plugin exposes this task input.
    testEngineInput.threshold.set(0.0000005f)
}

dependencies {
  // Reusable Android contract and policy core
  implementation(project(":shared:mercury-core"))

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.play.services.auth)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.datastore.preferences)
  // No Fragment code in Mercury, but the dependency pins the transitive
  // Fragment version: lint's InvalidFragmentVersionForActivityResult fails
  // the build against the older one activity-compose would otherwise pull.
  implementation(libs.androidx.fragment.ktx)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.websockets)
  implementation(libs.play.services.code.scanner)
  implementation(libs.tink.android)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3.adaptive)
  implementation(libs.androidx.compose.material3.adaptive.navigation3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  screenshotTestImplementation(composeBom)
  screenshotTestImplementation(libs.screenshot.validation.api)
  screenshotTestImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: JUnit, coroutines, Robolectric, and Compose behavior
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.turbine)
  testImplementation(libs.ktor.client.mock)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.compose.ui.test.junit4)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  // Existing native-view tests need Espresso on the compile classpath too;
  // Compose supplies this version only as a transitive runtime dependency.
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Media playback (managed video artifacts)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
}
