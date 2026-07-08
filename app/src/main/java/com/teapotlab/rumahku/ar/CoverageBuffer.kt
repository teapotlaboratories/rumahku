package com.teapotlab.rumahku.ar

import kotlin.math.floor

/**
 * Thread-safe store of world-space points marking scanned surfaces — the live
 * coverage map drawn over the camera (see CoverageRenderer).
 *
 * Points come from two sources during capture: a marker per keyframe, and (the
 * dense part) ARCore Depth-API samples unprojected to world space
 * (see DepthCoverage). To keep the buffer bounded and make "already covered"
 * stick as you move, points are **deduped onto a voxel grid** — each ~[VOXEL_M]
 * cell keeps at most one point. A room settles at ~10–40 k points.
 *
 * Each point also carries an RGB colour (0..1). New points start a neutral teal
 * and are recoloured from the camera image at keyframes via [colorFrom] — so the
 * overlay becomes a live **colour** point cloud of the room as you scan (a rough
 * "what it looks like" preview; the real splat comes from the post-capture train).
 *
 * Points are added on the GL thread (during capture) and cleared on the UI
 * thread (new session), so access is synchronized.
 */
class CoverageBuffer {

    private val data = ArrayList<Float>()       // flattened x,y,z,r,g,b, …
    private val voxels = HashSet<Long>()        // occupied voxel keys

    /** Adds a world point (uncoloured, neutral teal) unless its cell is covered. */
    @Synchronized
    fun add(x: Float, y: Float, z: Float) {
        if (!voxels.add(voxelKey(x, y, z))) return
        data.add(x); data.add(y); data.add(z)
        data.add(DEFAULT_R); data.add(DEFAULT_G); data.add(DEFAULT_B)
    }

    /**
     * Recolour every point visible in a keyframe image by projecting it into the
     * frame and sampling NV21→RGB — the same projection as the seed colouring.
     *
     * @param worldToCam column-major 4x4 world→camera in the ARCore camera basis
     *   (+Y up, −Z forward), i.e. `pose.inverse().toMatrix(...)`. Matches
     *   SeedPointCloud.colorFrom exactly (same matrix is passed to both).
     */
    @Synchronized
    fun colorFrom(
        worldToCam: FloatArray, flX: Float, flY: Float, cx: Float, cy: Float,
        nv21: ByteArray, w: Int, h: Int,
    ) {
        val m = worldToCam
        val ySize = w * h
        var i = 0
        while (i < data.size) {
            val x = data[i]; val y = data[i + 1]; val z = data[i + 2]
            val camX = m[0] * x + m[4] * y + m[8] * z + m[12]
            val camY = m[1] * x + m[5] * y + m[9] * z + m[13]
            val camZ = m[2] * x + m[6] * y + m[10] * z + m[14]
            val depth = -camZ // ARCore camera looks down −Z
            if (depth > 0.1f) {
                val u = (flX * camX / depth + cx).toInt()
                val v = (flY * (-camY) / depth + cy).toInt() // image v down, camY up
                if (u in 0 until w && v in 0 until h) {
                    // NV21 → RGB (Y plane, then interleaved V,U at half res).
                    val yv = nv21[v * w + u].toInt() and 0xFF
                    val uvBase = ySize + (v / 2) * w + (u and 1.inv())
                    val vv = (nv21[uvBase].toInt() and 0xFF) - 128
                    val uu = (nv21[uvBase + 1].toInt() and 0xFF) - 128
                    data[i + 3] = (yv + 1.402f * vv).coerceIn(0f, 255f) / 255f
                    data[i + 4] = (yv - 0.344f * uu - 0.714f * vv).coerceIn(0f, 255f) / 255f
                    data[i + 5] = (yv + 1.772f * uu).coerceIn(0f, 255f) / 255f
                }
            }
            i += 6
        }
    }

    /** Returns a flat [x,y,z,r,g,b,…] copy safe to read on the GL thread. */
    @Synchronized
    fun snapshot(): FloatArray = data.toFloatArray()

    @Synchronized
    fun count(): Int = data.size / 6

    @Synchronized
    fun clear() {
        data.clear()
        voxels.clear()
    }

    /** Quantize to a voxel cell; 21 bits/axis → ±(2^20)·VOXEL_M range, ample. */
    private fun voxelKey(x: Float, y: Float, z: Float): Long {
        val ix = floor(x / VOXEL_M).toLong() and MASK
        val iy = floor(y / VOXEL_M).toLong() and MASK
        val iz = floor(z / VOXEL_M).toLong() and MASK
        return ix or (iy shl 21) or (iz shl 42)
    }

    private companion object {
        const val VOXEL_M = 0.08f          // 8 cm coverage resolution
        const val MASK = 0x1FFFFFL         // 21 bits
        // Neutral teal for not-yet-coloured points (matches the old overlay look).
        const val DEFAULT_R = 0.16f
        const val DEFAULT_G = 0.84f
        const val DEFAULT_B = 0.66f
    }
}
