import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  // alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

// ---------------------------------------------------------------------------
// Signing keys
// ---------------------------------------------------------------------------
// An unsigned APK cannot be installed on any Android device, and since Android 11
// an APK that targets API 30+ and carries only a JAR (v1) signature cannot be
// installed either. Both failures show up as the same device error,
// "The package appears to be invalid", so this module never produces an unsigned
// release build:
//
//   1. the upload key is used when it is configured (AI Studio provides
//      KEYSTORE_PATH / STORE_PASSWORD / KEY_PASSWORD, CI can provide them as
//      secrets, and a local my-upload-key.jks is picked up automatically), and
//   2. otherwise the public CI test key in .github/ci/open-fire-test.p12 is used.
//
// The CI workflow deliberately disables signing again (see
// .github/workflows/android.yml) so that it can publish a pristine
// "*-unsigned.apk" for distributor signing next to the installable
// "*release.apk". See docs/INSTALLING.md for the whole story.
//
// Do not remove these signingConfigs: they are what makes installable and
// updatable builds possible in AI Studio.
fun env(vararg names: String): String? =
  names.firstNotNullOfOrNull { name -> System.getenv(name)?.takeIf { it.isNotBlank() } }

val configuredKeystorePath = env("KEYSTORE_PATH", "UPLOAD_KEYSTORE_PATH")
val uploadStorePassword = env("STORE_PASSWORD", "KEYSTORE_PASSWORD")
val uploadKeyPassword = env("KEY_PASSWORD", "KEYSTORE_KEY_PASSWORD") ?: uploadStorePassword
val uploadKeyAlias = env("KEY_ALIAS", "KEYSTORE_ALIAS") ?: "upload"

val uploadKeystore: File? =
  configuredKeystorePath?.let { file(it) }
    ?: file("${rootDir}/my-upload-key.jks").takeIf { it.isFile }

// An explicitly configured keystore must work; silently signing with the test key
// instead would ship a build that cannot update the app it is meant to update.
if (configuredKeystorePath != null) {
  check(uploadKeystore!!.isFile) {
    "KEYSTORE_PATH points at ${uploadKeystore.path}, which does not exist."
  }
  check(uploadStorePassword != null) {
    "KEYSTORE_PATH is set but STORE_PASSWORD is missing. Set the keystore password, " +
      "or unset KEYSTORE_PATH to build with the public CI test key."
  }
}

val uploadKeyConfigured =
  uploadKeystore?.isFile == true && uploadStorePassword != null && uploadKeyPassword != null

val ciTestKeystore = file("${rootDir}/.github/ci/open-fire-test.p12")
val ciTestKeystorePassword = "openfireci"
val ciTestKeyAlias = "open-fire-ci"

if (!uploadKeyConfigured) {
  logger.warn(
    "WARNING: no upload key configured (KEYSTORE_PATH / STORE_PASSWORD / KEY_PASSWORD). " +
      "Release builds will be signed with the public CI test key from " +
      ".github/ci/open-fire-test.p12. That APK installs on any device, but it cannot be " +
      "published and it cannot update an app that was signed with a different key."
  )
  check(ciTestKeystore.isFile) {
    "Missing CI test signing key: ${ciTestKeystore.path}. It is checked in; restore it with " +
      "'git checkout -- .github/ci/open-fire-test.p12' or configure an upload key."
  }
}

// The local debug keystore is optional: when it is absent the build falls back to
// Android Gradle Plugin's own debug signing config, which creates
// ~/.android/debug.keystore on demand. Referencing a keystore file that does not
// exist makes `assembleDebug` fail on a fresh clone.
val repoDebugKeystore = file("${rootDir}/debug.keystore")

android {
  namespace = "com.blindtechnexus.openfire"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.blindtechnexus.openfire"
    minSdk = 24
    targetSdk = 36
    versionCode = 8
    versionName = "2.4"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      if (uploadKeyConfigured) {
        // storeType is intentionally left unset: AGP infers JKS/PKCS12 from the
        // file, and "my-upload-key.jks" is a PKCS12 file in most modern setups.
        storeFile = uploadKeystore
        storePassword = uploadStorePassword
        keyAlias = uploadKeyAlias
        keyPassword = uploadKeyPassword
      } else {
        storeFile = ciTestKeystore
        storeType = "PKCS12"
        storePassword = ciTestKeystorePassword
        keyAlias = ciTestKeyAlias
        keyPassword = ciTestKeystorePassword
      }
      // Always emit every signature scheme the platform can use:
      //   v1 (JAR)  is still what Android < 7.0 and some update paths check,
      //   v2        is mandatory for targetSdk >= 30 apps on Android 11+,
      //   v3        is what Android 9+ uses for key rotation.
      // A v1-only signature is silently unusable on Android 11+.
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
    }
    create("debugConfig") {
      storeFile = repoDebugKeystore
      storePassword = env("DEBUG_STORE_PASSWORD") ?: "android"
      keyAlias = env("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
      keyPassword = env("DEBUG_KEY_PASSWORD") ?: "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      // "debugConfig" points at the optional checked-out debug.keystore; without it, AGP's own
      // "debug" config is used and creates ~/.android/debug.keystore on demand.
      signingConfig = signingConfigs.getByName(if (repoDebugKeystore.isFile) "debugConfig" else "debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  androidResources {
    // AudioManager opens the sound assets through AssetManager.openFd(), which only
    // works for uncompressed entries. aapt2 already leaves these extensions
    // uncompressed by default; declaring them keeps that guarantee explicit and
    // keeps the entries 4-byte aligned so the APK stays installable and playable.
    noCompress.addAll(listOf("mp3", "ogg", "wav", "m4a"))
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  packaging {
    jniLibs {
      useLegacyPackaging = false
    }
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
