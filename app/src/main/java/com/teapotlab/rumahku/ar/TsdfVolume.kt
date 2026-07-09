package com.teapotlab.rumahku.ar

import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Real-time volumetric fusion — a TSDF (truncated signed-distance field) with
 * **incremental marching cubes**, the KinectFusion / Mobile3DRecon pipeline that
 * powers Polycam-style live meshing.
 *
 * Every keyframe's depth is *fused* into a shared, voxel-hashed distance field
 * (running weighted average), so overlapping observations reinforce **one**
 * coherent surface instead of piling up layers. Marching cubes then extracts the
 * zero-crossing surface — but only for **dirty cubes** (voxels touched this
 * keyframe), so already-captured area stays fixed ("meshed once, marked
 * captured") and only new/refined surface updates. A cube is meshed only when
 * all 8 of its corners have been observed, so the mesh conforms to the real 3D
 * contour and stops cleanly at the scanned frontier.
 *
 * Output ([snapshot]) is a triangle soup — 8 floats/vertex: x,y,z, r,g,b,
 * bary.x,bary.y — ready for [com.teapotlab.rumahku.ar.gl.DepthMeshRenderer].
 *
 * Fused on the GL thread at keyframes; read on the GL thread; cleared on the UI
 * thread. All shared state is guarded by [lock].
 */
class TsdfVolume {

    private val lock = Any()

    // Voxel field: key → [tsdf, weight, r, g, b]. tsdf in [-1,1], colour 0..1.
    private val voxels = HashMap<Long, FloatArray>()
    // Extracted triangles per cube (key = min-corner voxel): the mesh is the
    // concatenation of these; re-meshing a cube overwrites its entry.
    private val cubeTris = HashMap<Long, FloatArray>()
    private val dirty = HashSet<Long>()
    // Voxels touched in the CURRENT keyframe — so weight counts distinct views
    // (keyframes), not repeat pixel hits within one frame. Cleared per integrate.
    private val frameTouched = HashSet<Long>()
    private var cachedVertCount = 0
    // Snapshot cache: rebuilt only when the mesh changed (not every render frame).
    private var snapshotStale = true
    private var cachedSnapshot = FloatArray(0)

    /** Fuse one keyframe (its depth + [nv21] camera image) into the field. */
    fun integrate(frame: Frame, nv21: ByteArray, camW: Int, camH: Int, stride: Int) {
        // Raw (measured, un-extrapolated) ARCore depth + per-pixel confidence:
        // sparse but metrically accurate — only confident pixels are fused.
        val depth = try {
            frame.acquireRawDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return
        } ?: return
        val conf = try {
            frame.acquireRawDepthConfidenceImage()
        } catch (e: Exception) {
            null
        }

        try {
            val dw = depth.width
            val dh = depth.height
            val plane = depth.planes[0]
            val buf = plane.buffer.order(ByteOrder.nativeOrder())
            val rowStride = plane.rowStride
            val cBuf = conf?.planes?.get(0)?.buffer
            val cRowStride = conf?.planes?.get(0)?.rowStride ?: 0

            val intr = frame.camera.imageIntrinsics
            val fl = intr.focalLength
            val pp = intr.principalPoint
            val dims = intr.imageDimensions
            if (dims[0] == 0 || dims[1] == 0) return
            val fx = fl[0]; val fy = fl[1]; val px = pp[0]; val py = pp[1] // camera intrinsics

            val m = FloatArray(16)
            frame.camera.pose.toMatrix(m, 0)  // camera→world, column-major
            val ySize = camW * camH

            synchronized(lock) {
                frameTouched.clear()
                var v = 0
                while (v < dh) {
                    val rowBase = v * rowStride
                    var u = 0
                    while (u < dw) {
                        val mm = buf.getShort(rowBase + u * 2).toInt() and 0xFFFF
                        val cok = cBuf == null ||
                            (cBuf.get(v * cRowStride + u).toInt() and 0xFF) >= CONF_MIN
                        if (mm != 0 && cok) {
                            val z = mm / 1000f
                            if (z in MIN_M..MAX_M) {
                                fuseSample(u.toFloat() * camW / dw, v.toFloat() * camH / dh, z,
                                    m, fx, fy, px, py, nv21, camW, camH, ySize)
                            }
                        }
                        u += stride
                    }
                    v += stride
                }
                remeshDirty()
            }
        } finally {
            depth.close()
            conf?.close()
        }
    }

    /**
     * Unproject a camera pixel (cpx,cpy) at metric depth [d] to world space,
     * colour it from [nv21], and run a ray-band TSDF update around the surface.
     * Camera basis is ARCore's (+Y up, −Z forward).
     */
    private fun fuseSample(
        cpx: Float, cpy: Float, d: Float, m: FloatArray,
        fx: Float, fy: Float, px: Float, py: Float,
        nv21: ByteArray, camW: Int, camH: Int, ySize: Int,
    ) {
        val lx = (cpx - px) * d / fx
        val ly = -(cpy - py) * d / fy
        val lz = -d
        val sxw = m[0] * lx + m[4] * ly + m[8] * lz + m[12]
        val syw = m[1] * lx + m[5] * ly + m[9] * lz + m[13]
        val szw = m[2] * lx + m[6] * ly + m[10] * lz + m[14]
        val ox = m[12]; val oy = m[13]; val oz = m[14]
        var rx = sxw - ox; var ry = syw - oy; var rz = szw - oz
        val dist = sqrt(rx * rx + ry * ry + rz * rz)
        if (dist < 1e-3f) return
        rx /= dist; ry /= dist; rz /= dist
        val uc = cpx.toInt().coerceIn(0, camW - 1)
        val vc = cpy.toInt().coerceIn(0, camH - 1)
        val yv = nv21[vc * camW + uc].toInt() and 0xFF
        val uvB = ySize + (vc / 2) * camW + (uc and 1.inv())
        val vv = (nv21[uvB].toInt() and 0xFF) - 128
        val uu = (nv21[uvB + 1].toInt() and 0xFF) - 128
        val cr = (yv + 1.402f * vv).coerceIn(0f, 255f) / 255f
        val cg = (yv - 0.344f * uu - 0.714f * vv).coerceIn(0f, 255f) / 255f
        val cb = (yv + 1.772f * uu).coerceIn(0f, 255f) / 255f
        var t = dist - TRUNC_M
        val tEnd = dist + TRUNC_M
        while (t <= tEnd) {
            updateVoxel(ox + rx * t, oy + ry * t, oz + rz * t,
                ((dist - t) / TRUNC_M).coerceIn(-1f, 1f), cr, cg, cb)
            t += VOXEL_M
        }
    }

    /** Weighted-average update of one voxel + mark its 8 incident cubes dirty. */
    private fun updateVoxel(wx: Float, wy: Float, wz: Float, sdf: Float, r: Float, g: Float, b: Float) {
        val ix = floor(wx / VOXEL_M).toInt()
        val iy = floor(wy / VOXEL_M).toInt()
        val iz = floor(wz / VOXEL_M).toInt()
        val key = voxelKey(ix, iy, iz)
        // Count this voxel at most once per keyframe → weight = number of views
        // that saw it (Polycam-style viewpoint diversity), not repeat pixel hits.
        if (!frameTouched.add(key)) return
        val vox = voxels.getOrPut(key) { FloatArray(5) }
        val w = vox[1]
        val nw = (w + 1f).coerceAtMost(WMAX)
        vox[0] = (vox[0] * w + sdf) / (w + 1f)
        vox[1] = nw
        // Blend colour only near the surface (where |sdf| is small).
        if (sdf > -0.6f && sdf < 0.6f) {
            vox[2] = (vox[2] * w + r) / (w + 1f)
            vox[3] = (vox[3] * w + g) / (w + 1f)
            vox[4] = (vox[4] * w + b) / (w + 1f)
        }
        // The 8 cubes whose corners include this voxel.
        var dx = -1
        while (dx <= 0) {
            var dy = -1
            while (dy <= 0) {
                var dz = -1
                while (dz <= 0) {
                    dirty.add(voxelKey(ix + dx, iy + dy, iz + dz))
                    dz++
                }
                dy++
            }
            dx++
        }
    }

    /** Re-extract triangles for every dirty cube (incremental marching cubes). */
    private fun remeshDirty() {
        for (cube in dirty) {
            val ix = unpackX(cube); val iy = unpackY(cube); val iz = unpackZ(cube)
            val tris = polygoniseCube(ix, iy, iz)
            val prev = if (tris != null) cubeTris.put(cube, tris) else cubeTris.remove(cube)
            cachedVertCount += (tris?.size ?: 0) / VERT_FLOATS - (prev?.size ?: 0) / VERT_FLOATS
        }
        dirty.clear()
        snapshotStale = true
    }

    /**
     * Marching cubes on the cube whose min corner is voxel (ix,iy,iz). Returns a
     * triangle soup [x,y,z,r,g,b,bx,by,…] or null if the cube isn't fully
     * observed / has no surface crossing.
     */
    private fun polygoniseCube(ix: Int, iy: Int, iz: Int): FloatArray? {
        // Gather the 8 corners; bail unless each has been seen from enough views
        // (MIN_CAPTURE_WEIGHT) — so a single grazing glimpse doesn't count as
        // "captured". The least-seen corner sets the cube's confidence.
        var cubeIndex = 0
        var minW = Float.MAX_VALUE
        for (i in 0 until 8) {
            val cx = ix + CORNER[i * 3]
            val cy = iy + CORNER[i * 3 + 1]
            val cz = iz + CORNER[i * 3 + 2]
            val vox = voxels[voxelKey(cx, cy, cz)] ?: return null
            val w = vox[1]
            if (w < MIN_CAPTURE_WEIGHT) return null
            if (w < minW) minW = w
            cval[i] = vox[0]
            ccr[i] = vox[2]; ccg[i] = vox[3]; ccb[i] = vox[4]
        }
        // Confidence 0→1 as the cube goes from just-captured to well-observed.
        curConf = ((minW - MIN_CAPTURE_WEIGHT) / FADE_RANGE).coerceIn(0f, 1f)
        for (i in 0 until 8) if (cval[i] < ISO) cubeIndex = cubeIndex or (1 shl i)
        val edges = EDGE_TABLE[cubeIndex]
        if (edges == 0) return null

        // Interpolate the surface vertex (position + colour) on each active edge.
        for (e in 0 until 12) {
            if (edges and (1 shl e) != 0) {
                val a = EDGE_ENDS[e * 2]; val b = EDGE_ENDS[e * 2 + 1]
                val va = cval[a]; val vb = cval[b]
                var mu = if (kotlin.math.abs(vb - va) > 1e-6f) (ISO - va) / (vb - va) else 0.5f
                if (mu < 0f) mu = 0f; if (mu > 1f) mu = 1f
                val ax = (ix + CORNER[a * 3]).toFloat() * VOXEL_M
                val ay = (iy + CORNER[a * 3 + 1]).toFloat() * VOXEL_M
                val az = (iz + CORNER[a * 3 + 2]).toFloat() * VOXEL_M
                val bx = (ix + CORNER[b * 3]).toFloat() * VOXEL_M
                val by = (iy + CORNER[b * 3 + 1]).toFloat() * VOXEL_M
                val bz = (iz + CORNER[b * 3 + 2]).toFloat() * VOXEL_M
                ex[e] = ax + mu * (bx - ax)
                ey[e] = ay + mu * (by - ay)
                ez[e] = az + mu * (bz - az)
                er[e] = ccr[a] + mu * (ccr[b] - ccr[a])
                eg[e] = ccg[a] + mu * (ccg[b] - ccg[a])
                eb[e] = ccb[a] + mu * (ccb[b] - ccb[a])
            }
        }

        // Emit triangles from the table.
        val base = cubeIndex * 16
        var i = 0
        var count = 0
        while (TRI_TABLE[base + i].toInt() != -1) { count++; i += 1 }
        val nTri = count / 3
        if (nTri == 0) return null
        val out = FloatArray(nTri * 3 * VERT_FLOATS)
        var o = 0
        i = 0
        while (i < count) {
            appendEdgeVert(out, o, TRI_TABLE[base + i].toInt(), 1f, 0f); o += VERT_FLOATS
            appendEdgeVert(out, o, TRI_TABLE[base + i + 1].toInt(), 0f, 1f); o += VERT_FLOATS
            appendEdgeVert(out, o, TRI_TABLE[base + i + 2].toInt(), 0f, 0f); o += VERT_FLOATS
            i += 3
        }
        return out
    }

    private fun appendEdgeVert(out: FloatArray, o: Int, e: Int, bx: Float, by: Float) {
        out[o] = ex[e]; out[o + 1] = ey[e]; out[o + 2] = ez[e]
        // Fade from the low-confidence tint to the true colour as confidence rises,
        // so thinly-scanned surface reads "keep scanning here".
        val t = curConf
        out[o + 3] = LOWCONF_R + (er[e] - LOWCONF_R) * t
        out[o + 4] = LOWCONF_G + (eg[e] - LOWCONF_G) * t
        out[o + 5] = LOWCONF_B + (eb[e] - LOWCONF_B) * t
        out[o + 6] = bx; out[o + 7] = by
    }

    /** Concatenate all cube triangles into one flat buffer for the renderer.
     *  Cached — only rebuilt after the mesh changes, not every render frame. */
    fun snapshot(): FloatArray = synchronized(lock) {
        if (!snapshotStale) return cachedSnapshot
        val out = FloatArray(cachedVertCount * VERT_FLOATS)
        var o = 0
        for (arr in cubeTris.values) {
            System.arraycopy(arr, 0, out, o, arr.size)
            o += arr.size
        }
        cachedSnapshot = out
        snapshotStale = false
        out
    }

    fun triangleCount(): Int = synchronized(lock) { cachedVertCount / 3 }

    fun clear() = synchronized(lock) {
        voxels.clear(); cubeTris.clear(); dirty.clear(); frameTouched.clear()
        cachedVertCount = 0; cachedSnapshot = FloatArray(0); snapshotStale = false
    }

    // Scratch (single-threaded under lock) for one cube's marching-cubes pass.
    private var curConf = 1f          // confidence of the cube being polygonised
    private val cval = FloatArray(8)
    private val ccr = FloatArray(8); private val ccg = FloatArray(8); private val ccb = FloatArray(8)
    private val ex = FloatArray(12); private val ey = FloatArray(12); private val ez = FloatArray(12)
    private val er = FloatArray(12); private val eg = FloatArray(12); private val eb = FloatArray(12)

    private fun voxelKey(ix: Int, iy: Int, iz: Int): Long {
        val x = (ix + BIAS).toLong() and COORD_MASK
        val y = (iy + BIAS).toLong() and COORD_MASK
        val z = (iz + BIAS).toLong() and COORD_MASK
        return x or (y shl 21) or (z shl 42)
    }
    private fun unpackX(k: Long) = ((k and COORD_MASK).toInt()) - BIAS
    private fun unpackY(k: Long) = (((k shr 21) and COORD_MASK).toInt()) - BIAS
    private fun unpackZ(k: Long) = (((k shr 42) and COORD_MASK).toInt()) - BIAS

    companion object {
        const val VERT_FLOATS = 8          // x,y,z,r,g,b,bx,by
        private const val VOXEL_M = 0.04f  // 4 cm voxels (coarser → more continuous)
        private const val TRUNC_M = 0.09f  // truncation band (± around surface)
        private const val MIN_M = 0.3f
        private const val MAX_M = 4.0f
        private const val WMAX = 40f       // cap running weight
        private const val CONF_MIN = 100   // raw-depth confidence gate (0..255)
        // "Captured" = a cube's corners each seen from at least this many
        // keyframes; confidence then ramps to full colour over FADE_RANGE more.
        private const val MIN_CAPTURE_WEIGHT = 3f
        private const val FADE_RANGE = 5f
        // Low-confidence tint (neutral cool-grey "clay") for just-captured surface
        // — fades to the true camera colour as the area is observed from more views.
        private const val LOWCONF_R = 0.58f
        private const val LOWCONF_G = 0.64f
        private const val LOWCONF_B = 0.72f
        private const val ISO = 0f         // surface = TSDF zero-crossing
        private const val BIAS = 0x100000  // +2^20 so negative coords pack unsigned
        private const val COORD_MASK = 0x1FFFFFL // 21 bits/axis

        // Marching-cubes corner offsets (Paul Bourke convention), 8×(dx,dy,dz).
        private val CORNER = intArrayOf(
            0, 0, 0,  1, 0, 0,  1, 1, 0,  0, 1, 0,
            0, 0, 1,  1, 0, 1,  1, 1, 1,  0, 1, 1,
        )
        // The two corner indices each of the 12 edges connects.
        private val EDGE_ENDS = intArrayOf(
            0, 1,  1, 2,  2, 3,  3, 0,
            4, 5,  5, 6,  6, 7,  7, 4,
            0, 4,  1, 5,  2, 6,  3, 7,
        )

        private val EDGE_TABLE = MarchingCubesTables.EDGE
        private val TRI_TABLE = MarchingCubesTables.TRI
    }
}
