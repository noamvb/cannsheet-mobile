plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

import java.util.Properties
import com.android.build.api.variant.HasUnitTestBuilder

val productionGasUrl = "https://script.google.com/macros/s/AKfycbys-9r8PnkcTwUwbWL4hITr73n3nF240WQ1Vz6PW_V2XBwzusnMU3Br8tLaCgTiFz7hmQ/exec"
val sandboxPropertiesFile = rootProject.file("sandbox.properties")
val sandboxProperties = Properties().apply {
  if (sandboxPropertiesFile.isFile) sandboxPropertiesFile.inputStream().use(::load)
}
val sandboxGasUrl = sandboxProperties.getProperty("CANNSHEET_SANDBOX_GAS_URL")?.trim().orEmpty()
val sandboxGasUrlSentinel = "https://script.google.com/macros/s/REPLACE_ME/exec"
fun buildConfigString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
val releaseStorePassword = System.getenv("STORE_PASSWORD")
val releaseKeyAlias = System.getenv("KEY_ALIAS")
val releaseKeyPassword = System.getenv("KEY_PASSWORD")
val hasReleaseSigning = listOf(
  releaseKeystorePath,
  releaseStorePassword,
  releaseKeyAlias,
  releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.noamv.cannsheet.mobile"
    minSdk = 24
    targetSdk = 36
    versionCode = 6
    versionName = "1.2.3"

    buildConfigField("String", "GAS_URL", buildConfigString(productionGasUrl))
    buildConfigField("String", "APP_ENVIRONMENT", buildConfigString("PRODUCTION"))

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(requireNotNull(releaseKeystorePath))
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    create("sandbox") {
      initWith(getByName("debug"))
      applicationIdSuffix = ".sandbox"
      versionNameSuffix = "-sandbox"
      matchingFallbacks += listOf("debug")
      signingConfig = signingConfigs.getByName("debug")
      buildConfigField("String", "GAS_URL", buildConfigString(sandboxGasUrl.ifBlank { sandboxGasUrlSentinel }))
      buildConfigField("String", "APP_ENVIRONMENT", buildConfigString("SANDBOX"))
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
}

androidComponents {
  beforeVariants(selector().withBuildType("sandbox")) { variantBuilder ->
    (variantBuilder as HasUnitTestBuilder).enableUnitTest = true
  }
}

val validateSandboxConfig by tasks.registering {
  group = "verification"
  description = "Validates the untracked sandbox Apps Script endpoint."
  doLast {
    val pattern = Regex("^https://script\\.google\\.com/macros/s/[^/]+/exec$")
    check(sandboxPropertiesFile.isFile) {
      "Missing sandbox.properties. Copy sandbox.properties.example and set CANNSHEET_SANDBOX_GAS_URL."
    }
    check(pattern.matches(sandboxGasUrl) && sandboxGasUrl != sandboxGasUrlSentinel) {
      "CANNSHEET_SANDBOX_GAS_URL must be an HTTPS script.google.com/macros/s/.../exec URL."
    }
  }
}

tasks.matching { it.name == "preSandboxBuild" }.configureEach {
  dependsOn(validateSandboxConfig)
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test:core-ktx:1.6.1")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
  debugImplementation(libs.androidx.compose.ui.tooling)
  "sandboxImplementation"("androidx.compose.ui:ui-test-manifest")
  "sandboxImplementation"(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
