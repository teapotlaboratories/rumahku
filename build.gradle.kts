// ─────────────────────────────────────────────────────────────────────────────
// Root build.gradle.kts — declares plugin VERSIONS once for the whole build.
// `apply false` means "make this plugin available to sub-modules, but don't
// apply it here in the root project." Each module opts in individually.
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 moved the Jetpack Compose compiler into this separate plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
