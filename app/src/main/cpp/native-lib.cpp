// ─────────────────────────────────────────────────────────────────────────────
// native-lib.cpp — the C++ side of the app.
//
// For now it exposes ONE function to Kotlin via JNI, just to prove the native
// toolchain is wired end-to-end. As the project grows, the heavy compute (the
// Gaussian-splat optimizer, running on Vulkan) will live in this native layer,
// where we get full control over memory and the GPU.
//
// JNI naming rule: a native method declared in Kotlin as
//     external fun nativeVersion(): String
// inside class  com.teapotlab.rumahku.NativeBridge
// must be implemented by a C function named:
//     Java_com_teapotlab_rumahku_NativeBridge_nativeVersion
// (package dots become underscores). Get this wrong and it fails at runtime.
// ─────────────────────────────────────────────────────────────────────────────

#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "rumahku-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_teapotlab_rumahku_NativeBridge_nativeVersion(JNIEnv *env, jobject /* this */) {
    LOGI("native core initialised");
    // This string surfaces in the Kotlin UI, confirming the JNI bridge is alive.
    std::string version = "nativecore 0.1.0 (C++17, arm64-v8a)";
    return env->NewStringUTF(version.c_str());
}
