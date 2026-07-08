package com.teapotlab.rumahku

/**
 * Kotlin ⇄ Rust bridge for on-device Gaussian-splat training (Phase 2 / M1).
 *
 * Backed by libbrush_ffi.so — a prebuilt Rust cdylib (a thin JNI shim over the
 * Brush training core) shipped in src/main/jniLibs/arm64-v8a/. See docs/M1.md.
 *
 * Contract:
 *  - [nativeInit] must be called once (with an app-writable cache dir) before
 *    the first [nativeTrain]; it points Brush's GPU-kernel cache at that dir so
 *    cold starts don't re-autotune every run.
 *  - [nativeTrain] is BLOCKING and GPU-heavy — call it from a background thread.
 *    It returns the exported .ply path, or a string starting with "ERROR:".
 *  - While it runs, poll [nativeCurrentIter] / [nativeCurrentSplats] for UI.
 */
object BrushTrainer {

    init {
        System.loadLibrary("brush_ffi")
    }

    /** Point Brush's kernel cache at an app-writable dir (e.g. context.cacheDir). */
    external fun nativeInit(cacheDir: String)

    /**
     * Train a splat from a nerfstudio dataset dir (containing transforms.json +
     * images/) and export a .ply into [outDir]. Blocking; run off the main
     * thread. Returns the .ply path, or "ERROR: …".
     *
     * @param maxFrames 0 = use all frames.
     */
    external fun nativeTrain(
        datasetDir: String,
        outDir: String,
        totalIters: Int,
        maxRes: Int,
        maxFrames: Int,
    ): String

    external fun nativeCurrentIter(): Int
    external fun nativeCurrentSplats(): Int
    external fun nativeIsRunning(): Int
    external fun nativeCancel()
}
