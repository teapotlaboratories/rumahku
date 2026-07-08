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

    /**
     * Render one view of a splat [plyPath] from a camera pose ([transform] = the
     * 16-float row-major nerfstudio `transform_matrix`), returning ARGB pixels
     * (`outW*outH`) or null on failure. Blocking; run off the main thread.
     */
    external fun nativeRenderView(
        plyPath: String,
        transform: FloatArray,
        flX: Float,
        flY: Float,
        imgW: Int,
        imgH: Int,
        outW: Int,
        outH: Int,
    ): IntArray?

    /**
     * Render an orbit view: the camera orbits the reconstruction's centroid,
     * starting from a reference capture pose [refTransform], by [yawDeg] /
     * [pitchDeg] with [distScale] zoom. ARGB pixels or null. Off the main thread.
     */
    external fun nativeRenderOrbit(
        plyPath: String,
        refTransform: FloatArray,
        yawDeg: Float,
        pitchDeg: Float,
        distScale: Float,
        flX: Float,
        flY: Float,
        imgW: Int,
        imgH: Int,
        outW: Int,
        outH: Int,
    ): IntArray?

    /**
     * Walkthrough "look-around" at a capture standpoint [transform]: the camera
     * stays put while [yawDeg]/[pitchDeg] pan the view and [fovScale] zooms
     * (< 1 = zoomed in). ARGB pixels or null. Off the main thread.
     */
    external fun nativeRenderLook(
        plyPath: String,
        transform: FloatArray,
        yawDeg: Float,
        pitchDeg: Float,
        fovScale: Float,
        flX: Float,
        flY: Float,
        imgW: Int,
        imgH: Int,
        outW: Int,
        outH: Int,
    ): IntArray?

    /**
     * World-space forward direction [x,y,z] the camera looks after [yawDeg]/
     * [pitchDeg] at standpoint [transform]. Used to pick the standpoint to move
     * to on a directional double-tap. Cheap (no render).
     */
    external fun nativeLookForward(
        transform: FloatArray,
        yawDeg: Float,
        pitchDeg: Float,
    ): FloatArray?
}
