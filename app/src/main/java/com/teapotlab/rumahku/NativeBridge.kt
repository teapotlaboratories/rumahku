package com.teapotlab.rumahku

/**
 * Kotlin ⇄ C++ bridge.
 *
 * The `external` keyword means "the body of this function lives in native code."
 * At class-load time we load libnativecore.so (built by CMake from
 * src/main/cpp/), which supplies the implementation.
 *
 * Keeping every native entry point in this one object makes the JNI surface
 * easy to see at a glance — important because the C++ function names must match
 * the package + class + method exactly (see native-lib.cpp).
 */
object NativeBridge {

    init {
        // Loads libnativecore.so. Throws UnsatisfiedLinkError if the .so is
        // missing or built for the wrong ABI — which is exactly what we want
        // during development: fail loudly, not silently.
        System.loadLibrary("nativecore")
    }

    /** Returns a version string from the native layer — proves JNI works. */
    external fun nativeVersion(): String
}
