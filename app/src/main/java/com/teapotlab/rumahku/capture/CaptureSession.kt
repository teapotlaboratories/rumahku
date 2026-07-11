package com.teapotlab.rumahku.capture

import android.content.Context
import android.util.Log
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.PointCloud
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.teapotlab.rumahku.ar.TsdfVolume
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** A short guidance cue shown on the capture screen. */
enum class CaptureHint { NONE, FINDING, MOVE_SLOWER, HOLD_STEADY }

/** Immutable snapshot of capture progress, for the UI. */
data class CaptureProgress(
    val capturing: Boolean = false,
    val keyframes: Int = 0,
    val distanceMeters: Float = 0f,
    val hint: CaptureHint = CaptureHint.NONE,
)

/**
 * Decides which camera frames become keyframes and writes them out.
 *
 * Keyframe policy: while capturing and tracking is healthy, save a frame only
 * once the camera has moved far enough OR rotated enough since the last
 * keyframe. This avoids saving 30 near-identical frames per second while
 * standing still, and gives the splat pipeline good viewpoint coverage.
 *
 * Threading: [onFrame] runs on the GL thread (that's the only place the ARCore
 * Frame is valid). We do the minimum there — acquire the image, copy its bytes
 * and pose, close it — then hand the heavy JPEG-encode + disk-write to a
 * single background thread so the render loop never stalls.
 */
class CaptureSession(
    private val context: Context,
    private val tsdf: TsdfVolume,
    private val onProgress: (CaptureProgress) -> Unit,
) {

    @Volatile
    private var capturing = false

    private var writer: DatasetWriter? = null
    private var executor: ExecutorService? = null

    // Accumulated ARCore feature points → seed.ply for on-device training (M3).
    private val seedPointCloud = SeedPointCloud()

    private var lastKeyframePose: Pose? = null
    private var keyframeCount = 0
    private var distanceMeters = 0f
    private var intrinsics: CameraIntrinsics? = null
    private var hint = CaptureHint.FINDING

    // High-res capture (opt-in, wired by CaptureActivity). When set, each keyframe
    // is saved from the continuous high-res stream (NV21) instead of ARCore's VGA
    // CPU image; the mesh + seed still use the CPU/depth streams. highResW/H are
    // the high-res size; textureIntrinsics (16:9, same FOV) scales to it.
    var highResGrabber: ((onResult: (ByteArray, Int, Int) -> Unit) -> Unit)? = null
    var highResW = 0
    var highResH = 0
    private var textureIntrinsics: CameraIntrinsics? = null

    // Tracking-stability gate: ARCore poses can jump while tracking settles
    // (or briefly re-acquires). Capturing during that window produces garbage
    // poses, so we require a run of stable TRACKING frames before capturing and
    // reject implausible single-frame jumps.
    private var stableTrackingFrames = 0
    private var prevFramePose: Pose? = null
    private var prevFrameTimeNanos: Long = 0L

    fun isCapturing(): Boolean = capturing

    /** Begins a new capture session in its own timestamped directory. */
    fun start() {
        if (capturing) return
        val root = com.teapotlab.rumahku.rumahkuRoot(context)
        val dir = File(root, "captures/${System.currentTimeMillis()}")
        writer = DatasetWriter(dir)
        executor = Executors.newSingleThreadExecutor()
        lastKeyframePose = null
        keyframeCount = 0
        distanceMeters = 0f
        intrinsics = null
        stableTrackingFrames = 0
        prevFramePose = null
        tsdf.clear()
        seedPointCloud.clear()
        capturing = true
        Log.i(TAG, "capture started → ${dir.absolutePath}")
        emit()
    }

    /** Ends the session, flushing transforms.json on the background thread. */
    fun stop() {
        if (!capturing) return
        capturing = false
        val w = writer
        // High-res keyframes are 16:9 (from the texture-FOV stream), so write the
        // texture intrinsics scaled to their size; normal keyframes use the CPU
        // intrinsics as-is.
        val intr = if (highResW > 0) textureIntrinsics else intrinsics
        // Feature-point seed (sparse, on texture) + dense raw-depth surface points
        // (cover blank walls). The combined cloud is seed.ply; the feature-only set
        // is kept as seed_features.ply so we can A/B the depth seed on one capture.
        val featureSeed = seedPointCloud.snapshot()
        val depthSeed = tsdf.surfaceSeed(MAX_DEPTH_SEED_POINTS)
        val combined = if (depthSeed.isEmpty()) featureSeed else featureSeed + depthSeed
        // Snapshot the live mesh now (capturing=false, so it's stable) and persist
        // it so the scan's 3D map is reviewable offline (MeshViewerActivity).
        val meshVerts = tsdf.snapshot()
        executor?.execute {
            w?.finish(intr, combined, altSeedName = "seed_features.ply", altSeedPoints = featureSeed,
                imageW = highResW, imageH = highResH)
            w?.writeMesh(meshVerts)
        }
        executor?.shutdown()
        Log.i(TAG, "capture stopped: $keyframeCount keyframes, " +
            "${featureSeed.size} feature + ${depthSeed.size} depth seed points")
        emit()
    }

    /** Called every frame on the GL thread. Captures a keyframe when due. */
    fun onFrame(frame: Frame) {
        if (!capturing) return

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            // Lost tracking: reset the warm-up so we wait for it to re-settle.
            stableTrackingFrames = 0
            prevFramePose = null
            setHint(CaptureHint.FINDING)
            return
        }

        val pose = camera.pose
        val nowNanos = frame.timestamp

        // Reject implausible single-frame jumps — a real hand can't move this
        // fast; it means tracking glitched. Treat as instability.
        val prev = prevFramePose
        val prevTs = prevFrameTimeNanos
        prevFramePose = pose
        prevFrameTimeNanos = nowNanos
        if (prev != null && translationBetween(prev, pose) > MAX_FRAME_JUMP_M) {
            stableTrackingFrames = 0
            setHint(CaptureHint.FINDING)
            return
        }

        // Warm-up: require a run of stable frames before capturing, so the first
        // jittery poses after tracking (re)starts never become keyframes.
        stableTrackingFrames++
        if (stableTrackingFrames < TRACKING_WARMUP_FRAMES) { setHint(CaptureHint.FINDING); return }

        // M3 seed: accumulate ARCore feature points (world space) every stable
        // frame — these become seed.ply so the on-device trainer starts from a
        // real point cloud instead of random init (+10.8 dB in testing).
        try {
            frame.acquirePointCloud().use { pc: PointCloud ->
                seedPointCloud.addFrom(pc.ids, pc.points, MIN_POINT_CONFIDENCE)
            }
        } catch (e: NotYetAvailableException) {
            // no point cloud this frame — fine
        } catch (e: Exception) {
            Log.w(TAG, "seed point-cloud accumulate failed", e)
        }

        // Keyframe gate: only proceed once we've moved/rotated enough since the
        // last keyframe. Everything below runs at keyframe rate — including the
        // live mesh, so it reflects exactly the frames we capture (nothing fused
        // from skipped or blurry frames).
        val last = lastKeyframePose
        if (last != null) {
            val moved = translationBetween(last, pose)
            val turned = rotationRadiansBetween(last, pose)
            if (moved < MIN_TRANSLATION_M && turned < MIN_ROTATION_RAD) { setHint(CaptureHint.NONE); return }
        }

        // Only capture when the phone is nearly still — fast motion = motion blur.
        if (prev != null && prevTs > 0L && nowNanos > prevTs) {
            val dt = (nowNanos - prevTs) / 1e9f
            val linVel = translationBetween(prev, pose) / dt
            val angVel = rotationRadiansBetween(prev, pose) / dt
            if (linVel > MAX_STILL_LIN_VEL || angVel > MAX_STILL_ANG_VEL) {
                setHint(CaptureHint.MOVE_SLOWER); return
            }
        }

        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return // image not ready this frame; try again next time
        }

        try {
            if (intrinsics == null) intrinsics = camera.imageIntrinsics
            // Texture intrinsics (GPU stream, 16:9) match the high-res stream's FOV.
            if (textureIntrinsics == null) textureIntrinsics = camera.textureIntrinsics

            val width = image.width
            val height = image.height
            val nv21 = YuvUtil.toNv21(image)

            // Reject blurry keyframes (Laplacian-variance sharpness) — blurry
            // input = blurry splat, so we drop them and guide the user.
            val sharp = sharpness(nv21, width, height)
            if (sharp < MIN_SHARPNESS) {
                Log.d(TAG, "reject blurry keyframe sharpness=%.0f".format(sharp))
                setHint(CaptureHint.HOLD_STEADY)
                return
            }
            hint = CaptureHint.NONE   // a keyframe is being captured — all good

            val matrix = FloatArray(16)
            pose.toMatrix(matrix, 0)

            if (last != null) distanceMeters += translationBetween(last, pose)
            lastKeyframePose = pose
            val index = keyframeCount++

            val w = writer
            val grabber = highResGrabber
            if (grabber != null) {
                // High-res: save the latest frame from the continuous stream (NV21,
                // arrives async on the camera thread). No mode-switch → no stall.
                grabber { nv21hr, jw, jh ->
                    try { executor?.execute { w?.writeKeyframe(index, nv21hr, jw, jh, matrix) } }
                    catch (e: Exception) { Log.w(TAG, "hi-res keyframe dropped", e) }
                }
            } else {
                executor?.execute { w?.writeKeyframe(index, nv21, width, height, matrix) }
            }

            // Colour the accumulated seed points visible in this keyframe (reuses
            // the image we already have — no extra acquire).
            val intr = intrinsics
            if (intr != null) {
                val worldToCam = FloatArray(16)
                pose.inverse().toMatrix(worldToCam, 0)
                seedPointCloud.colorFrom(
                    worldToCam, intr.focalLength[0], intr.focalLength[1],
                    intr.principalPoint[0], intr.principalPoint[1], nv21, width, height,
                )
            }
            // Fuse this keyframe's depth into the TSDF + incrementally re-mesh.
            // Keyframes only → the live mesh mirrors exactly what's captured.
            try {
                tsdf.integrate(frame, nv21, width, height, DEPTH_STRIDE)
            } catch (e: Exception) {
                Log.w(TAG, "tsdf integrate failed", e)
            }
            emit()
        } catch (e: Exception) {
            Log.e(TAG, "keyframe capture failed", e)
        } finally {
            image.close()
        }
    }

    private fun emit() = onProgress(CaptureProgress(capturing, keyframeCount, distanceMeters, hint))

    /** Update the guidance cue, pushing to the UI only when it actually changes. */
    private fun setHint(h: CaptureHint) {
        if (h != hint) { hint = h; emit() }
    }

    /** Euclidean distance between two poses' translations, in meters. */
    private fun translationBetween(a: Pose, b: Pose): Float {
        val dx = a.tx() - b.tx()
        val dy = a.ty() - b.ty()
        val dz = a.tz() - b.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Variance of the Laplacian over the luma (NV21 Y plane) — a standard image
     * sharpness / blur metric. Higher = sharper. Computed over a subsampled
     * central region for speed (edges are noisy / often out of focus).
     */
    private fun sharpness(nv21: ByteArray, w: Int, h: Int): Double {
        val x0 = w / 5; val x1 = w * 4 / 5
        val y0 = h / 5; val y1 = h * 4 / 5
        var sum = 0.0; var sumSq = 0.0; var n = 0
        var y = y0
        while (y < y1) {
            val row = y * w
            var x = x0
            while (x < x1) {
                val c = nv21[row + x].toInt() and 0xFF
                val up = nv21[row - w + x].toInt() and 0xFF
                val dn = nv21[row + w + x].toInt() and 0xFF
                val lf = nv21[row + x - 1].toInt() and 0xFF
                val rt = nv21[row + x + 1].toInt() and 0xFF
                val lap = (4 * c - up - dn - lf - rt).toDouble()
                sum += lap; sumSq += lap * lap; n++
                x += SHARP_STEP
            }
            y += SHARP_STEP
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** Angle between two poses' orientations, in radians. */
    private fun rotationRadiansBetween(a: Pose, b: Pose): Float {
        val qa = FloatArray(4).also { a.getRotationQuaternion(it, 0) }
        val qb = FloatArray(4).also { b.getRotationQuaternion(it, 0) }
        var dot = qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3]
        dot = abs(dot).coerceAtMost(1f)
        return 2f * acos(dot)
    }

    companion object {
        private const val TAG = "rumahku-capture-session"
        // Keyframe spacing — kept dense for high frame-to-frame overlap (~70-80%),
        // which SfM/3DGS quality depends on. Denser = more views per surface.
        private const val MIN_TRANSLATION_M = 0.07f          // 7 cm
        private val MIN_ROTATION_RAD = Math.toRadians(8.0).toFloat()
        private const val TRACKING_WARMUP_FRAMES = 30        // ~1 s of stable tracking before capturing
        private const val MAX_FRAME_JUMP_M = 0.30f           // single-frame move above this = tracking glitch
        private const val MIN_POINT_CONFIDENCE = 0.3f        // drop low-confidence ARCore feature points from the seed
        private const val DEPTH_STRIDE = 2                   // depth subsample fed into TSDF fusion
        private const val MAX_DEPTH_SEED_POINTS = 50_000     // cap dense depth seed (bounds seed.ply + on-device init)
        private const val SHARP_STEP = 2                     // subsample stride for the sharpness metric
        private const val MIN_SHARPNESS = 55.0               // reject keyframes blurrier than this
                                                             // (calibrated: blurry ≤25, sharp 41+, gap at 25–41)
        // Sharpness gate — capture only when moving slower than this (blur guard).
        private const val MAX_STILL_LIN_VEL = 0.12f          // m/s
        private val MAX_STILL_ANG_VEL = Math.toRadians(18.0).toFloat()  // rad/s
    }
}
