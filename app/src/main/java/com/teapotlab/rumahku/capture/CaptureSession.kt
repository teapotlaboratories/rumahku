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

/** Immutable snapshot of capture progress, for the UI. */
data class CaptureProgress(
    val capturing: Boolean = false,
    val keyframes: Int = 0,
    val distanceMeters: Float = 0f,
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

    // Tracking-stability gate: ARCore poses can jump while tracking settles
    // (or briefly re-acquires). Capturing during that window produces garbage
    // poses, so we require a run of stable TRACKING frames before capturing and
    // reject implausible single-frame jumps.
    private var stableTrackingFrames = 0
    private var prevFramePose: Pose? = null
    private var prevFrameTimeNanos: Long = 0L
    private var depthFrame = 0

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
        val intr = intrinsics
        val seed = seedPointCloud.snapshot()
        executor?.execute { w?.finish(intr, seed) }
        executor?.shutdown()
        Log.i(TAG, "capture stopped: $keyframeCount keyframes, ${seed.size} seed points")
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
            return
        }

        // Warm-up: require a run of stable frames before capturing, so the first
        // jittery poses after tracking (re)starts never become keyframes.
        stableTrackingFrames++
        if (stableTrackingFrames < TRACKING_WARMUP_FRAMES) return

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

        val last = lastKeyframePose
        if (last != null) {
            val moved = translationBetween(last, pose)
            val turned = rotationRadiansBetween(last, pose)
            if (moved < MIN_TRANSLATION_M && turned < MIN_ROTATION_RAD) return
        }

        // Sharpness gate: only capture when the phone is nearly still. Fast
        // motion = motion blur, which wrecks the reconstruction. Effectively asks
        // the user to pause briefly at each viewpoint.
        if (prev != null && prevTs > 0L && nowNanos > prevTs) {
            val dt = (nowNanos - prevTs) / 1e9f
            val linVel = translationBetween(prev, pose) / dt
            val angVel = rotationRadiansBetween(prev, pose) / dt
            if (linVel > MAX_STILL_LIN_VEL || angVel > MAX_STILL_ANG_VEL) return
        }

        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return // image not ready this frame; try again next time
        }

        try {
            if (intrinsics == null) intrinsics = camera.imageIntrinsics

            val width = image.width
            val height = image.height
            val nv21 = YuvUtil.toNv21(image)

            val matrix = FloatArray(16)
            pose.toMatrix(matrix, 0)

            if (last != null) distanceMeters += translationBetween(last, pose)
            lastKeyframePose = pose
            val index = keyframeCount++

            val w = writer
            executor?.execute { w?.writeKeyframe(index, nv21, width, height, matrix) }

            // Colour the accumulated seed points that are visible in this
            // keyframe (reuses the image we already have — no extra acquire).
            val intr = intrinsics
            if (intr != null) {
                val worldToCam = FloatArray(16)
                pose.inverse().toMatrix(worldToCam, 0)
                seedPointCloud.colorFrom(
                    worldToCam, intr.focalLength[0], intr.focalLength[1],
                    intr.principalPoint[0], intr.principalPoint[1], nv21, width, height,
                )
            }
            // Fuse this keyframe's depth into the TSDF volume + incrementally
            // re-mesh via marching cubes (vertices coloured from nv21, same view).
            // The live "watch it reconstruct" surface; real splat is post-capture.
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

    private fun emit() = onProgress(CaptureProgress(capturing, keyframeCount, distanceMeters))

    /** Euclidean distance between two poses' translations, in meters. */
    private fun translationBetween(a: Pose, b: Pose): Float {
        val dx = a.tx() - b.tx()
        val dy = a.ty() - b.ty()
        val dz = a.tz() - b.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
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
        private const val MIN_TRANSLATION_M = 0.10f          // 10 cm
        private val MIN_ROTATION_RAD = Math.toRadians(12.0).toFloat()
        private const val TRACKING_WARMUP_FRAMES = 30        // ~1 s of stable tracking before capturing
        private const val MAX_FRAME_JUMP_M = 0.30f           // single-frame move above this = tracking glitch
        private const val MIN_POINT_CONFIDENCE = 0.3f        // drop low-confidence ARCore feature points from the seed
        private const val DEPTH_EVERY_N = 2                  // sample the depth map every Nth stable frame
        private const val DEPTH_STRIDE = 2                   // depth subsample fed into TSDF fusion
        // Sharpness gate — capture only when moving slower than this (blur guard).
        private const val MAX_STILL_LIN_VEL = 0.12f          // m/s
        private val MAX_STILL_ANG_VEL = Math.toRadians(18.0).toFloat()  // rad/s
    }
}
