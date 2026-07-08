package com.teapotlab.rumahku.capture

import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import com.teapotlab.rumahku.ar.CoverageBuffer
import java.nio.ByteOrder

/**
 * Turns an ARCore Depth-API frame into dense world-space coverage points.
 *
 * For a strided grid of depth pixels we unproject to camera space using the
 * camera intrinsics (scaled to the depth image resolution), then to world via
 * the camera pose, and drop each into [CoverageBuffer] (voxel-deduped). The
 * result is a rough "processed surface" over what you've scanned — gaps stay
 * empty. See docs/CAPTURE_COVERAGE.md.
 */
object DepthCoverage {

    private const val MIN_M = 0.3f
    private const val MAX_M = 5.0f

    /** Accumulate one depth frame into [coverage]. No-op if depth isn't ready. */
    fun accumulate(frame: Frame, coverage: CoverageBuffer, stride: Int) {
        val depth = try {
            frame.acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return
        } ?: return

        try {
            val w = depth.width
            val h = depth.height
            val plane = depth.planes[0]
            val buf = plane.buffer.order(ByteOrder.nativeOrder())
            val rowStride = plane.rowStride            // bytes per row (pixelStride = 2)

            val intr = frame.camera.imageIntrinsics
            val fl = intr.focalLength                  // [fx, fy] in image px
            val pp = intr.principalPoint               // [cx, cy]
            val dims = intr.imageDimensions            // [w, h] of the CPU image
            if (dims[0] == 0 || dims[1] == 0) return
            val sx = w.toFloat() / dims[0]
            val sy = h.toFloat() / dims[1]
            val fx = fl[0] * sx; val fy = fl[1] * sy
            val cx = pp[0] * sx; val cy = pp[1] * sy

            val m = FloatArray(16)
            frame.camera.pose.toMatrix(m, 0)           // camera→world, column-major

            var v = 0
            while (v < h) {
                val rowBase = v * rowStride
                var u = 0
                while (u < w) {
                    val mm = buf.getShort(rowBase + u * 2).toInt() and 0xFFFF
                    if (mm != 0) {
                        val z = mm / 1000f
                        if (z in MIN_M..MAX_M) {
                            val x = (u - cx) * z / fx
                            val y = -(v - cy) * z / fy
                            val cz = -z
                            val wx = m[0] * x + m[4] * y + m[8] * cz + m[12]
                            val wy = m[1] * x + m[5] * y + m[9] * cz + m[13]
                            val wz = m[2] * x + m[6] * y + m[10] * cz + m[14]
                            coverage.add(wx, wy, wz)
                        }
                    }
                    u += stride
                }
                v += stride
            }
        } finally {
            depth.close()
        }
    }
}
