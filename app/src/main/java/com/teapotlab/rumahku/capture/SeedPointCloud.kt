package com.teapotlab.rumahku.capture

import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Accumulates ARCore feature points (world space) across frames into a sparse
 * point cloud used to **seed** on-device Gaussian-splat training.
 *
 * Why this matters (Phase 2 / M3): seeding the trainer with a real point cloud
 * instead of random init is decisive on-device — measured at **+10.8 dB PSNR**
 * (PSNR 21.5 seeded vs 10.7 random) at a phone-reasonable 1000-iter budget on
 * the Pixel 6. Random init simply can't converge in phone time. See docs/M3.md.
 *
 * ARCore's point cloud gives points already in the same world frame as the
 * camera poses we write to transforms.json, so the seed is aligned for free (no
 * COLMAP, no cloud). Points carry stable IDs, so we dedupe across frames and
 * keep the latest (most-refined) position for each.
 *
 * Each point also carries an **RGB colour** sampled from the camera image at
 * keyframes ([colorFrom]) — a coloured seed starts training much closer to the
 * final look than a grey one, which matters in a small on-device iteration budget.
 *
 * Points accumulate + are coloured on the GL thread and read on the writer
 * thread at the end of a session, so access is synchronized.
 */
class SeedPointCloud {

    // point id -> [x, y, z, r, g, b]  (rgb in 0..255; grey until coloured)
    private val points = HashMap<Int, FloatArray>()

    /**
     * Merge one ARCore point-cloud frame. [pointBuffer] is packed
     * [x, y, z, confidence] per point; [idBuffer] is one id per point. Points
     * below [minConfidence] are skipped.
     */
    @Synchronized
    fun addFrom(idBuffer: IntBuffer, pointBuffer: FloatBuffer, minConfidence: Float) {
        val n = idBuffer.remaining()
        for (i in 0 until n) {
            val base = i * 4
            // ARCore occasionally returns points with NaN/Inf coords (or NaN
            // confidence). A `NaN < min` test is false, so they'd slip through —
            // and a single NaN aborts the trainer (`assertion failed: !x.is_nan()`).
            // Reject any non-finite value explicitly.
            val conf = pointBuffer.get(base + 3)
            if (!conf.isFinite() || conf < minConfidence) continue
            val x = pointBuffer.get(base)
            val y = pointBuffer.get(base + 1)
            val z = pointBuffer.get(base + 2)
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            val id = idBuffer.get(i)
            val existing = points[id]
            if (existing != null) {
                existing[0] = x; existing[1] = y; existing[2] = z // keep colour
            } else {
                points[id] = floatArrayOf(x, y, z, 160f, 160f, 160f)
            }
        }
    }

    /**
     * Colour visible points by sampling the camera image at their projection.
     * [worldToCam] is the world→camera 4x4 (column-major); [nv21] is the frame's
     * NV21 image (w×h); intrinsics are for that image (ARCore, −Z forward).
     */
    @Synchronized
    fun colorFrom(
        worldToCam: FloatArray, flX: Float, flY: Float, cx: Float, cy: Float,
        nv21: ByteArray, w: Int, h: Int,
    ) {
        val m = worldToCam
        val ySize = w * h
        for (p in points.values) {
            val camX = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12]
            val camY = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13]
            val camZ = m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14]
            val depth = -camZ // ARCore camera looks down −Z
            if (depth <= 0.1f) continue
            val u = (flX * camX / depth + cx).toInt()
            val v = (flY * (-camY) / depth + cy).toInt() // image v is down, camY up
            if (u < 0 || u >= w || v < 0 || v >= h) continue
            // NV21 → RGB (Y plane, then interleaved V,U at half resolution).
            val yv = nv21[v * w + u].toInt() and 0xFF
            val uvBase = ySize + (v / 2) * w + (u and 1.inv())
            val vv = nv21[uvBase].toInt() and 0xFF
            val uu = nv21[uvBase + 1].toInt() and 0xFF
            p[3] = (yv + 1.402f * (vv - 128)).coerceIn(0f, 255f)
            p[4] = (yv - 0.344f * (uu - 128) - 0.714f * (vv - 128)).coerceIn(0f, 255f)
            p[5] = (yv + 1.772f * (uu - 128)).coerceIn(0f, 255f)
        }
    }

    @Synchronized
    fun count(): Int = points.size

    /** Snapshot of accumulated points ([x,y,z,r,g,b]), safe to read off-thread. */
    @Synchronized
    fun snapshot(): List<FloatArray> = points.values.map { it.copyOf() }

    @Synchronized
    fun clear() = points.clear()
}
