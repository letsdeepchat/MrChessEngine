import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Cloud backup is opt-in: drop a Firebase google-services.json into app/ to build it in.
// Without it the APK has no INTERNET permission and no Firebase code at all.
val cloudSyncEnabled = file("google-services.json").exists()
if (cloudSyncEnabled) apply(plugin = "com.google.gms.google-services")

// Web OAuth client (client_type 3) from google-services.json. Credential Manager needs this as the
// server client ID; it is a fallback in case default_web_client_id is not generated.
val webClientId: String = if (!cloudSyncEnabled) "" else runCatching {
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(file("google-services.json")) as Map<String, Any?>
    val clients = json["client"] as List<Map<String, Any?>>
    clients.flatMap { (it["oauth_client"] as? List<Map<String, Any?>>).orEmpty() }
        .firstOrNull { (it["client_type"] as? Number)?.toInt() == 3 }?.get("client_id") as? String
}.getOrNull() ?: ""

android {
    namespace = "com.mrchess.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mrchess.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.5.0"
        buildConfigField("boolean", "CLOUD_SYNC", cloudSyncEnabled.toString())
        buildConfigField("String", "WEB_CLIENT_ID", "\"$webClientId\"")
        // Stockfish is built for arm64 only (see scripts/build_stockfish_android.sh).
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Fixed key so every CI build installs as an update over the previous one.
        // It is committed for easy sideloading; set MRCHESS_KEYSTORE* env vars to use a private key.
        create("sideload") {
            storeFile = file(System.getenv("MRCHESS_KEYSTORE") ?: "mrchess-sideload.keystore")
            storePassword = System.getenv("MRCHESS_KEYSTORE_PASSWORD") ?: "mrchess-sideload"
            keyAlias = System.getenv("MRCHESS_KEY_ALIAS") ?: "mrchess"
            keyPassword = System.getenv("MRCHESS_KEY_PASSWORD") ?: "mrchess-sideload"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("sideload") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    packaging {
        // The engine runs as a separate process, so its "lib" must be extracted to nativeLibraryDir.
        jniLibs { useLegacyPackaging = true }
    }

    androidResources { noCompress += "tflite" }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":chesscore"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tensorflow.lite)
    implementation(libs.opencv)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    if (cloudSyncEnabled) implementation(project(":cloudsync"))
}

// Fail early with a helpful message if the engine binary has not been built yet.
val checkStockfish by tasks.registering {
    val lib = File(projectDir, "src/main/jniLibs/arm64-v8a/libstockfish.so")
    doLast {
        if (!lib.exists()) throw GradleException(
            "Missing ${lib.path}. Run scripts/build_stockfish_android.sh first (needs the Android NDK)."
        )
    }
}
tasks.named("preBuild") { dependsOn(checkStockfish) }
