package com.teapotlab.rumahku.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import com.google.ar.core.Session
import com.google.ar.core.SharedCamera
import com.google.ar.core.exceptions.CameraNotAvailableException

/**
 * Drives the Camera2 device for an ARCore **SharedCamera** session so we can
 * apply photometric controls ARCore's own [com.google.ar.core.Config] can't:
 *
 *  - **Fast shutter** — cap exposure time to cut motion blur, the dominant cause
 *    of soft keyframes while the phone sweeps a room.
 *  - **AE + AWB lock** — freeze exposure and white balance after an initial
 *    meter, so a wall doesn't change brightness/colour between views. Gaussian
 *    splatting/SfM assume one consistent appearance per surface; auto-exposure
 *    drift actively fights that.
 *
 * Two exposure strategies (see [MODE]):
 *  - **ADAPTIVE** (default) — auto-exposure stays on, but the shutter is capped
 *    fast via the AE target-FPS range. AE then compensates with ISO, not longer
 *    exposure, so it never goes dark or blurry and it keeps adjusting as you move
 *    between bright and dim areas.
 *  - **LOCK** — meter with auto-exposure for [METER_MS], then freeze a fast
 *    shutter (ISO raised to hold brightness) plus AE/AWB lock. Maximum
 *    consistency, but can lock to a poor value if lighting later changes.
 *
 * Sequence (Camera2 callbacks land on a background thread):
 *   [start] → openCamera → onOpened → createCaptureSession(ARCore surfaces)
 *   → onConfigured → setRepeatingRequest → onActive → session.resume().
 * The GL renderer keeps calling session.update() as before.
 */
enum class ExposureMode { ADAPTIVE, LOCK }

class SharedCameraController(
    context: Context,
    private val session: Session,
    private val sharedCamera: SharedCamera,
    private val cameraId: String,
    private val highRes: Boolean = false,
    private val onArCoreResumed: () -> Unit = {},
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val characteristics = cameraManager.getCameraCharacteristics(cameraId)
    // Largest 16:9 YUV that's a streaming/video mode (≤ 4K wide). It runs
    // CONTINUOUSLY in the repeating request, so there's no per-keyframe sensor
    // mode-switch → no preview stall. Same 16:9 FOV as ARCore's GPU texture, so
    // keyframe intrinsics = textureIntrinsics scaled linearly. Pixel 6: 3840x2160.
    val highResSize: Size? = if (!highRes) null else {
        val yuv = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
        yuv?.filter {
            kotlin.math.abs(it.width.toFloat() / it.height - 16f / 9f) < 0.05f && it.width <= 3840
        }?.maxByOrNull { it.width.toLong() * it.height }
    }
    private var highResReader: ImageReader? = null
    private val highResLock = Any()
    private var latestHighRes: android.media.Image? = null
    private val isoRange: Range<Int>? =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    private val exposureRange: Range<Long>? =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    // The supported AE FPS range with the highest floor → the tightest cap on
    // exposure time (max exposure ≈ 1/minFps), used by ADAPTIVE mode to keep the
    // shutter fast while auto-exposure floats ISO.
    private val fastestFps: Range<Int>? =
        characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.maxByOrNull { it.lower }

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var textureId = -1

    @Volatile
    private var opened = false
    @Volatile
    var arCoreResumed = false
        private set

    // Metering → lock state.
    private var meterStartMs = 0L
    private var locked = false
    private var frameCount = 0
    private var lastExposureNs = 0L
    private var lastIso = 0

    /** Idempotent — safe to call from both onResume and the GL "texture ready"
     *  callback; only the first (with a valid texture) actually opens. */
    fun start(glTextureId: Int) {
        if (opened || glTextureId <= 0) return
        opened = true
        textureId = glTextureId
        bgThread = HandlerThread("rumahku-sharedcam").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
        openCamera()
    }

    @SuppressLint("MissingPermission") // CaptureActivity gates on CAMERA permission
    private fun openCamera() {
        try {
            val cb = sharedCamera.createARDeviceStateCallback(deviceCallback, bgHandler!!)
            cameraManager.openCamera(cameraId, cb, bgHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera failed", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "camera permission missing", e)
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            createCaptureSession()
        }
        override fun onDisconnected(device: CameraDevice) {
            device.close(); cameraDevice = null
        }
        override fun onError(device: CameraDevice, error: Int) {
            Log.e(TAG, "camera device error $error"); device.close(); cameraDevice = null
        }
    }

    @Suppress("DEPRECATION") // the surface-list overload is fine + broadly compatible
    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        try {
            // ARCore needs its target texture before its surfaces are valid.
            session.setCameraTextureName(textureId)
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            requestBuilder = builder
            val arSurfaces = sharedCamera.arCoreSurfaces
            for (s in arSurfaces) builder.addTarget(s) // ARCore surfaces stream continuously
            Log.i(TAG, "mode=$MODE fastestFps=$fastestFps isoRange=$isoRange " +
                "exposureRange=${exposureRange?.lower}..${exposureRange?.upper}ns")
            applyBase(builder) // ADAPTIVE: final state; LOCK: metering phase

            // Optional high-res YUV stream, kept in the REPEATING request so it
            // streams continuously — the sensor stays in one mode, so grabbing a
            // keyframe never triggers a mode-switch (no preview stall). We only
            // hold the latest frame; older ones are dropped.
            val sessionSurfaces = ArrayList(arSurfaces)
            val size = highResSize
            if (highRes && size != null) {
                val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
                reader.setOnImageAvailableListener({ r ->
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    synchronized(highResLock) {
                        latestHighRes?.close()
                        latestHighRes = img
                    }
                }, bgHandler)
                highResReader = reader
                sharedCamera.setAppSurfaces(cameraId, listOf(reader.surface))
                sessionSurfaces.add(reader.surface)
                builder.addTarget(reader.surface) // continuous → no per-keyframe stall
                Log.i(TAG, "high-res continuous stream: ${size.width}x${size.height}")
            }

            val cb = sharedCamera.createARSessionStateCallback(sessionCallback, bgHandler!!)
            device.createCaptureSession(sessionSurfaces, cb, bgHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCaptureSession failed", e)
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(cs: CameraCaptureSession) {
            captureSession = cs
            setRepeating()
        }
        override fun onActive(cs: CameraCaptureSession) {
            if (!arCoreResumed) resumeArCore()
        }
        override fun onConfigureFailed(cs: CameraCaptureSession) {
            Log.e(TAG, "capture session configuration failed")
        }
    }

    private fun resumeArCore() {
        try {
            session.resume()
            arCoreResumed = true
            sharedCamera.setCaptureCallback(captureCallback, bgHandler)
            onArCoreResumed()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "session.resume failed", e)
        }
    }

    private fun setRepeating() {
        val cs = captureSession ?: return
        val b = requestBuilder ?: return
        try {
            meterStartMs = SystemClock.elapsedRealtime()
            cs.setRepeatingRequest(b.build(), captureCallback, bgHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "setRepeatingRequest failed", e)
        }
    }

    /** Auto-exposure + auto-WB. In ADAPTIVE mode we also cap the shutter fast via
     *  the AE FPS range (final state); in LOCK mode this is just the metering
     *  phase before [applyLock] takes over. */
    private fun applyBase(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        if (MODE == ExposureMode.ADAPTIVE) {
            // Cap max exposure fast; AE compensates with ISO instead of blur/dark.
            fastestFps?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
        ) {
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            if (frameCount++ % 90 == 0) {
                Log.i(TAG, "live exposure=${exp / 1000}us (1/${if (exp > 0) 1_000_000_000 / exp else 0}s) iso=$iso")
            }
            // Only LOCK mode meters-then-freezes; ADAPTIVE stays fully auto.
            if (MODE != ExposureMode.LOCK || locked) return
            lastExposureNs = exp
            lastIso = iso
            if (SystemClock.elapsedRealtime() - meterStartMs >= METER_MS) applyLock()
        }
    }

    /** Freeze a fast shutter + compensating ISO, and lock AE/AWB. */
    private fun applyLock() {
        val cs = captureSession ?: return
        val b = requestBuilder ?: return
        val meteredExp = lastExposureNs
        val meteredIso = lastIso
        if (meteredExp <= 0L || meteredIso <= 0) return
        // Fast shutter, clamped to the sensor's range.
        var targetExp = minOf(meteredExp, FAST_SHUTTER_NS)
        exposureRange?.let { targetExp = it.clamp(targetExp) }
        // Preserve total light: the exposure we cut is bought back as ISO
        // (clamped — if the sensor can't reach it, the frame is a bit darker).
        var targetIso = (meteredIso.toLong() * meteredExp / targetExp).toInt()
        isoRange?.let { targetIso = it.clamp(targetIso) }
        try {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExp)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, FRAME_DURATION_NS)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true) // freeze the metered white balance
            cs.setRepeatingRequest(b.build(), captureCallback, bgHandler)
            locked = true
            Log.i(TAG, "locked: exposure=${targetExp / 1000}us iso=$targetIso " +
                "(metered ${meteredExp / 1000}us iso=$meteredIso)")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "applyLock failed", e)
        }
    }

    /** Grab the latest frame from the continuous high-res stream as NV21 and hand
     *  it to [onResult] on the background thread. No sensor mode-switch (the stream
     *  is always running), so the preview doesn't stall. No-op until a frame has
     *  arrived / if high-res is off. */
    fun grabHighRes(onResult: (nv21: ByteArray, width: Int, height: Int) -> Unit) {
        val handler = bgHandler ?: return
        handler.post {
            synchronized(highResLock) {
                val img = latestHighRes ?: return@post
                try {
                    onResult(YuvUtil.toNv21(img), img.width, img.height)
                } catch (e: Exception) {
                    Log.w(TAG, "grabHighRes convert failed", e)
                }
            }
        }
    }

    /** Pause ARCore + tear down the camera. The controller can be [start]ed again
     *  (e.g. on the next onResume) — it re-meters from scratch. */
    fun close() {
        try {
            if (arCoreResumed) { session.pause(); arCoreResumed = false }
        } catch (e: Exception) { Log.w(TAG, "session.pause failed", e) }
        try { captureSession?.close() } catch (e: Exception) { /* ignore */ }
        captureSession = null
        try { cameraDevice?.close() } catch (e: Exception) { /* ignore */ }
        cameraDevice = null
        try { highResReader?.close() } catch (e: Exception) { /* ignore */ }
        highResReader = null
        synchronized(highResLock) { latestHighRes?.close(); latestHighRes = null }
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        requestBuilder = null
        opened = false
        locked = false
    }

    companion object {
        private const val TAG = "rumahku-sharedcam"
        // ADAPTIVE = auto-exposure with a fast-shutter cap (never dark, adapts as
        // you move). Everything it sets is in the INITIAL request, so ARCore's
        // depth stream is undisturbed.
        //
        // ⚠️ LOCK re-issues setRepeatingRequest mid-session to freeze AE/AWB, and
        // in shared-camera mode that BREAKS ARCore's depth-from-motion (→ the live
        // mesh + depth seed silently die). Do not enable LOCK without first moving
        // its settings into the initial request. Confirmed on Pixel 6, 2026-07.
        val MODE = ExposureMode.ADAPTIVE
        private const val METER_MS = 1200L               // auto-meter this long before locking (LOCK mode)
        private const val FAST_SHUTTER_NS = 8_000_000L   // 1/125 s target shutter
        private const val FRAME_DURATION_NS = 33_000_000L // ~30 fps frame duration
    }
}
