import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mrchess.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mrchess.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        // Stockfish is built for arm64 only (see scripts/build_stockfish_android.sh).
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the CI artifact installs directly; replace for Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

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
