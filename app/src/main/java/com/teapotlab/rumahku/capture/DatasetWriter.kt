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
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    /**
     * Finalizes the session: writes transforms.json, and — if we gathered enough
     * ARCore feature points — a seed.ply plus a `ply_file_path` reference so the
     * on-device trainer seeds from it instead of random init (M3).
     */
    fun finish(
        intrinsics: CameraIntrinsics?,
        seedPoints: List<FloatArray> = emptyList(),
        altSeedName: String? = null,
        altSeedPoints: List<FloatArray> = emptyList(),
        imageW: Int = 0,
        imageH: Int = 0,
    ) {
        try {
            val hasSeed = seedPoints.size >= MIN_SEED_POINTS
            if (hasSeed) writeSeedPly(SEED_PLY_NAME, seedPoints)
            // Optional companion cloud (e.g. feature-only) — not referenced by
            // transforms.json, just kept alongside for offline A/B comparison.
            if (altSeedName != null && altSeedPoints.size >= MIN_SEED_POINTS) {
                writeSeedPly(altSeedName, altSeedPoints)
            }
            val json = buildTransforms(intrinsics, if (hasSeed) SEED_PLY_NAME else null, imageW, imageH)
            File(sessionDir, "transforms.json").writeText(json.toString(2))
            Log.i(
                TAG,
                "wrote ${frames.size} frames" +
                    (if (hasSeed) ", ${seedPoints.size}-point seed" else " (no seed)") +
                    " to ${sessionDir.absolutePath}",
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to write dataset", e)
        }
    }

    /** Writes world points as an ASCII point-cloud PLY (positions + colour) to
     *  [name]. Non-finite points are dropped here too (defense in depth) — a
     *  single NaN aborts the trainer, and the vertex count must match the lines. */
    private fun writeSeedPly(name: String, points: List<FloatArray>) {
        val finite = points.filter { it[0].isFinite() && it[1].isFinite() && it[2].isFinite() }
        val sb = StringBuilder(64 + finite.size * 32)
        sb.append("ply\n").append("format ascii 1.0\n")
            .append("comment rumahku seed (ARCore features + raw-depth surface)\n")
            .append("element vertex ").append(finite.size).append('\n')
            .append("property float x\nproperty float y\nproperty float z\n")
            .append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
            .append("end_header\n")
        for (p in finite) {
            sb.append(p[0]).append(' ').append(p[1]).append(' ').append(p[2]).append(' ')
                .append(p[3].toInt()).append(' ').append(p[4].toInt()).append(' ').append(p[5].toInt())
                .append('\n')
        }
        File(sessionDir, name).writeText(sb.toString())
    }

    private fun buildTransforms(
        intrinsics: CameraIntrinsics?, plyFileName: String?, imageW: Int, imageH: Int,
    ): JSONObject {
        val root = JSONObject()
        // ARCore's world is gravity-aligned (+Y up), so the viewer can level the
        // walkthrough. (Imported COLMAP datasets omit this → not leveled.)
        root.put("gravity_up", true)
        // Seed the trainer from the ARCore point cloud when we have one; Brush's
        // nerfstudio loader reads this and skips random init.
        if (plyFileName != null) root.put("ply_file_path", plyFileName)

        // Intrinsics come from ARCore's image (CPU) intrinsics, which match the
        // resolution of the frames we saved via acquireCameraImage().
        if (intrinsics != null) {
            val focal = intrinsics.focalLength        // [fx, fy] in pixels
            val principal = intrinsics.principalPoint // [cx, cy] in pixels
            val dims = intrinsics.imageDimensions      // intrinsics' native [w, h]
            // High-res keyframes share the GPU texture's 16:9 FOV (we pass the
            // texture intrinsics for them), so they scale by the size ratio.
            val scale = if (imageW > 0 && dims[0] > 0) imageW.toDouble() / dims[0] else 1.0
            // No camera_model field: ARCore gives an undistorted pinhole camera,
            // and nerfstudio / Brush / instant-ngp all treat an absent model as
            // pinhole. Emitting "PINHOLE" (a COLMAP term) is not recognized by
            // those loaders and breaks the dataset.
            root.put("fl_x", focal[0].toDouble() * scale)
            root.put("fl_y", focal[1].toDouble() * scale)
            root.put("cx", principal[0].toDouble() * scale)
            root.put("cy", principal[1].toDouble() * scale)
            root.put("w", if (imageW > 0) imageW else dims[0])
            root.put("h", if (imageH > 0) imageH else dims[1])
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

    /** Persists the live TSDF mesh (world-space triangle soup, 8 floats/vertex:
     *  x,y,z,r,g,b,bx,by) so a scan's 3D map can be reviewed offline — no cloud
     *  build. Format: little-endian [int32 floatCount][float32 × floatCount].
     *  Skipped for empty meshes (e.g. depth-less locked-exposure captures). */
    fun writeMesh(verts: FloatArray) {
        if (verts.size < VERT_FLOATS * 3) return   // need at least one triangle
        try {
            val bb = ByteBuffer.allocate(4 + verts.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(verts.size)
            bb.asFloatBuffer().put(verts)
            FileOutputStream(File(sessionDir, MESH_NAME)).use { it.write(bb.array()) }
            Log.i(TAG, "wrote mesh: ${verts.size / VERT_FLOATS} verts")
        } catch (e: Exception) {
            Log.e(TAG, "failed to write mesh", e)
        }
    }

    companion object {
        private const val TAG = "rumahku-writer"
        private const val JPEG_QUALITY = 95
        private const val SEED_PLY_NAME = "seed.ply"
        const val MESH_NAME = "mesh.f32"
        private const val VERT_FLOATS = 8        // x,y,z,r,g,b,bx,by (matches TsdfVolume)
        private const val MIN_SEED_POINTS = 50   // below this the seed isn't worth it
    }
}
