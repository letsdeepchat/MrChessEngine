pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MrChessEngine"
include(":chesscore")
// The Android app needs the Android SDK. Set -PcoreOnly=true to build/test only the pure-Kotlin core.
if (providers.gradleProperty("coreOnly").orNull != "true") include(":app")
