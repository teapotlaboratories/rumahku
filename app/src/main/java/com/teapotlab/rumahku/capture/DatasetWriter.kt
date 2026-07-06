package com.teapotlab.rumahku.capture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.google.ar.core.CameraIntrinsics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Writes a capture session to disk in the "transforms.json" convention used by
 * instant-ngp / nerfstudio / most Gaussian-splat trainers:
 *
 *   <sessionDir>/
 *     images/000000.jpg, 000001.jpg, …
 *     transforms.json   (intrinsics + per-frame camera-to-world matrices)
 *
 * Because we record the camera pose for every frame (courtesy of ARCore), the
 * downstream splat pipeline can SKIP the slow, fragile COLMAP structure-from-
 * motion step entirely — that's the whole reason we capture poses on-device.
 *
 * All methods here run on a single background thread (owned by CaptureSession),
 * so no locking is needed on [frames].
 */
class DatasetWriter(private val sessionDir: File) {

    private val imagesDir = File(sessionDir, "images")
    private val frames = ArrayList<FrameEntry>()

    init {
        imagesDir.mkdirs()
    }

    private data class FrameEntry(val filePath: String, val colMajorMatrix: FloatArray)

    /** Encodes one keyframe's image to JPEG and records its pose. */
    fun writeKeyframe(index: Int, nv21: ByteArray, width: Int, height: Int, colMajorMatrix: FloatArray) {
        val relativePath = "images/%06d.jpg".format(index)
        val file = File(sessionDir, relativePath)
        try {
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            FileOutputStream(file).use { out ->
                yuv.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
            }
            frames.add(FrameEntry(relativePath, colMajorMatrix))
        } catch (e: Exception) {
            Log.e(TAG, "failed to write keyframe $index", e)
        }
    }

    /** Finalizes the session by writing transforms.json. */
    fun finish(intrinsics: CameraIntrinsics?) {
        try {
            val json = buildTransforms(intrinsics)
            File(sessionDir, "transforms.json").writeText(json.toString(2))
            Log.i(TAG, "wrote ${frames.size} frames to ${sessionDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "failed to write transforms.json", e)
        }
    }

    private fun buildTransforms(intrinsics: CameraIntrinsics?): JSONObject {
        val root = JSONObject()

        // Intrinsics come from ARCore's image (CPU) intrinsics, which match the
        // resolution of the frames we saved via acquireCameraImage().
        if (intrinsics != null) {
            val focal = intrinsics.focalLength        // [fx, fy] in pixels
            val principal = intrinsics.principalPoint // [cx, cy] in pixels
            val dims = intrinsics.imageDimensions      // [w, h]
            root.put("camera_model", "PINHOLE")
            root.put("fl_x", focal[0].toDouble())
            root.put("fl_y", focal[1].toDouble())
            root.put("cx", principal[0].toDouble())
            root.put("cy", principal[1].toDouble())
            root.put("w", dims[0])
            root.put("h", dims[1])
        }

        val framesArray = JSONArray()
        for (entry in frames) {
            val frameObj = JSONObject()
            frameObj.put("file_path", entry.filePath)
            frameObj.put("transform_matrix", toRowMajorJson(entry.colMajorMatrix))
            framesArray.put(frameObj)
        }
        root.put("frames", framesArray)
        return root
    }

    /**
     * ARCore's Pose.toMatrix() gives a column-major 4x4 (OpenGL layout); the
     * transforms.json convention wants a row-major nested array. Element (r,c)
     * of a column-major array lives at index c*4 + r.
     */
    private fun toRowMajorJson(colMajor: FloatArray): JSONArray {
        val rows = JSONArray()
        for (r in 0 until 4) {
            val row = JSONArray()
            for (c in 0 until 4) {
                row.put(colMajor[c * 4 + r].toDouble())
            }
            rows.put(row)
        }
        return rows
    }

    companion object {
        private const val TAG = "rumahku-writer"
        private const val JPEG_QUALITY = 95
    }
}
