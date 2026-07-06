# ─────────────────────────────────────────────────────────────────────────────
# CMakeLists.txt — builds the app's native (C++) code into a shared library
# (libnativecore.so) that the Kotlin side loads with System.loadLibrary().
#
# Phase 1: this is a small stub that proves the Kotlin ⇄ C++ (JNI) bridge works.
# Phase 2: the Gaussian-splat optimizer (Vulkan compute) will live here.
# ─────────────────────────────────────────────────────────────────────────────

cmake_minimum_required(VERSION 3.22.1)

project(rumahku_native LANGUAGES CXX)

# Build our C++ sources into a shared library named "nativecore".
# The final file on-device is libnativecore.so.
add_library(nativecore SHARED
        native-lib.cpp)

# The Android NDK ships a prebuilt log library; link it so C++ can write to
# logcat via __android_log_print. `log` and `android` are always available.
find_library(log-lib log)

target_link_libraries(nativecore
        ${log-lib})
