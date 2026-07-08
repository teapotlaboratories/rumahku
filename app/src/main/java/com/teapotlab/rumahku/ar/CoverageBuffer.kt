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
 * Points are added on the GL thread (during capture) and cleared on the UI
 * thread (new session), so access is synchronized.
 */
class CoverageBuffer {

    private val data = ArrayList<Float>()       // flattened x,y,z,x,y,z,…
    private val voxels = HashSet<Long>()        // occupied voxel keys

    /** Adds a world point unless its voxel cell is already covered. */
    @Synchronized
    fun add(x: Float, y: Float, z: Float) {
        if (!voxels.add(voxelKey(x, y, z))) return
        data.add(x); data.add(y); data.add(z)
    }

    /** Returns a flat [x,y,z,…] copy safe to read on the GL thread. */
    @Synchronized
    fun snapshot(): FloatArray = data.toFloatArray()

    @Synchronized
    fun count(): Int = data.size / 3

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
    }
}
