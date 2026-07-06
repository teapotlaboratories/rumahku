package com.teapotlab.rumahku.ar

/**
 * Thread-safe store of world-space points marking where keyframes were captured.
 *
 * Each captured keyframe contributes one point, projected a short distance in
 * front of the camera (see CaptureSession) so the dots land roughly on the
 * surfaces you scanned. The renderer reads a snapshot each frame and draws the
 * dots anchored in world space — that's the live coverage map.
 *
 * Points are added on the GL thread (during capture) and cleared on the UI
 * thread (when a new session starts), so access is synchronized.
 */
class CoverageBuffer {

    private val data = ArrayList<Float>() // flattened x,y,z,x,y,z,…

    @Synchronized
    fun add(x: Float, y: Float, z: Float) {
        data.add(x); data.add(y); data.add(z)
    }

    /** Returns a flat [x,y,z,…] copy safe to read on the GL thread. */
    @Synchronized
    fun snapshot(): FloatArray = data.toFloatArray()

    @Synchronized
    fun count(): Int = data.size / 3

    @Synchronized
    fun clear() = data.clear()
}
