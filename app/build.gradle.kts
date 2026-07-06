// ─────────────────────────────────────────────────────────────────────────────
// app/build.gradle.kts — configuration for the actual Android app module.
// This is where compileSdk, the native (C++) build, and dependencies live.
// ─────────────────────────────────────────────────────────────────────────────

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.teapotlab.rumahku"
    compileSdk = 34

    // Pin the exact NDK installed on this machine. Without this, AGP looks for
    // its built-in default NDK version (which isn't installed) and the build
    // fails with "NDK ... did not have a source.properties file".
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.teapotlab.rumahku"
        minSdk = 26          // ARCore needs 24+; 26 also gives us adaptive icons.
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // ── Native (NDK) build ────────────────────────────────────────────────
        // Only build for the 64-bit ARM ABI. The Samsung S25 (Snapdragon 8 Elite)
        // is arm64-v8a; skipping other ABIs keeps builds fast and the APK small.
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // Flags passed to the C++ compiler. C++17 is a safe modern baseline.
                cppFlags += "-std=c++17"
            }
        }
    }

    // Tell Gradle where the CMake project lives. CMake drives the C++ compile
    // and produces libnativecore.so, which we load from Kotlin at runtime.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // Phase 1 is dev-only; leave shrinking off so stack traces stay readable.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true      // Turn on Jetpack Compose (our UI toolkit).
    }

    // Java/Kotlin bytecode target. 17 pairs well with JDK 21 + AGP 8.6.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ── ARCore: motion tracking (camera poses) + Depth API ────────────────────
    implementation("com.google.ar:core:1.44.0")

    // ── AndroidX core + lifecycle ─────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    // ── Jetpack Compose (declarative UI) via the Bill-of-Materials ────────────
    // The BOM pins mutually-compatible versions so we don't hand-manage each one.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
