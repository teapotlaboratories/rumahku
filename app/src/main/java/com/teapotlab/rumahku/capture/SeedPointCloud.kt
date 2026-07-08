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
 * Points accumulate on the GL thread and are read on the writer thread at the
 * end of a session, so access is synchronized.
 */
class SeedPointCloud {

    // point id -> latest world-space [x, y, z]
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
            points[idBuffer.get(i)] = floatArrayOf(x, y, z)
        }
    }

    @Synchronized
    fun count(): Int = points.size

    /** Snapshot of accumulated world points, safe to read off-thread. */
    @Synchronized
    fun snapshot(): List<FloatArray> = points.values.map { it.copyOf() }

    @Synchronized
    fun clear() = points.clear()
}
