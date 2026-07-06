// ─────────────────────────────────────────────────────────────────────────────
// settings.gradle.kts — the FIRST file Gradle reads.
// It declares (a) where to download plugins/libraries from, and (b) which
// sub-projects ("modules") make up this build. We have one module: :app
// ─────────────────────────────────────────────────────────────────────────────

pluginManagement {
    repositories {
        google()          // Android Gradle Plugin, AndroidX, ARCore live here
        mavenCentral()    // Kotlin, most third-party libraries
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Forbid modules from declaring their own repositories — keeps everything
    // resolving from the same, predictable set defined here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rumahku"
include(":app")
