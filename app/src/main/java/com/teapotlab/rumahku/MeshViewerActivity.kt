package com.teapotlab.rumahku

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import com.teapotlab.rumahku.ar.gl.DepthMeshRenderer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline viewer for a scan's saved TSDF mesh ([DatasetWriter.MESH_NAME]) — the
 * live 3D map captured during scanning, reviewable without a cloud build. Drag one
 * finger to orbit, pinch to zoom. Reuses [DepthMeshRenderer]'s solid (camera-
 * coloured) mode; here it renders over a neutral background instead of the camera.
 */
class MeshViewerActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private var verts: FloatArray = FloatArray(0)

    // Orbit camera state, recomputed into a view-proj each frame.
    private var yaw = 0.6f
    private var pitch = 0.4f
    private var distance = 3f
    private val center = FloatArray(3)
    private var radius = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verts = intent.getStringExtra(EXTRA_MESH_PATH)?.let { loadMesh(File(it)) } ?: FloatArray(0)
        if (verts.size < VERT_FLOATS * 3) {
            Toast.makeText(this, "No 3D map saved for this scan", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        computeBounds()
        distance = radius * 2.5f
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(Viewer())
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
    }

    override fun onResume() { super.onResume(); if (::glView.isInitialized) glView.onResume() }
    override fun onPause() { super.onPause(); if (::glView.isInitialized) glView.onPause() }

    // --- touch: one finger orbits, two fingers pinch-zoom ---
    private var lastX = 0f
    private var lastY = 0f
    private var lastPinch = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_POINTER_DOWN -> lastPinch = pinch(e)
            MotionEvent.ACTION_POINTER_UP -> { lastX = e.getX(0); lastY = e.getY(0); lastPinch = 0f }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val d = pinch(e)
                    if (lastPinch > 0f && d > 0f) {
                        distance = (distance * lastPinch / d).coerceIn(radius * 0.25f, radius * 12f)
                    }
                    lastPinch = d
                } else {
                    yaw += (e.x - lastX) * 0.01f
                    pitch = (pitch + (e.y - lastY) * 0.01f).coerceIn(-1.5f, 1.5f)
                    lastX = e.x; lastY = e.y
                }
            }
        }
        return true
    }

    private fun pinch(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun computeBounds() {
        var minx = Float.MAX_VALUE; var miny = Float.MAX_VALUE; var minz = Float.MAX_VALUE
        var maxx = -Float.MAX_VALUE; var maxy = -Float.MAX_VALUE; var maxz = -Float.MAX_VALUE
        var i = 0
        while (i + 2 < verts.size) {
            val x = verts[i]; val y = verts[i + 1]; val z = verts[i + 2]
            if (x < minx) minx = x; if (x > maxx) maxx = x
            if (y < miny) miny = y; if (y > maxy) maxy = y
            if (z < minz) minz = z; if (z > maxz) maxz = z
            i += VERT_FLOATS
        }
        center[0] = (minx + maxx) * 0.5f; center[1] = (miny + maxy) * 0.5f; center[2] = (minz + maxz) * 0.5f
        val dx = maxx - minx; val dy = maxy - miny; val dz = maxz - minz
        radius = maxOf(0.1f, 0.5f * sqrt(dx * dx + dy * dy + dz * dz))
    }

    private fun loadMesh(f: File): FloatArray = try {
        if (!f.exists()) FloatArray(0) else {
            val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val n = bb.int
            if (n <= 0 || n > bb.remaining() / 4) FloatArray(0)
            else FloatArray(n).also { bb.asFloatBuffer().get(it) }
        }
    } catch (e: Exception) { FloatArray(0) }

    private inner class Viewer : GLSurfaceView.Renderer {
        private val mesh = DepthMeshRenderer(wireframe = false)
        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val vp = FloatArray(16)
        private var aspect = 1f

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.10f, 0.11f, 0.13f, 1f)
            mesh.createOnGlThread()
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            aspect = if (h > 0) w.toFloat() / h else 1f
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val ex = center[0] + distance * cos(pitch) * sin(yaw)
            val ey = center[1] + distance * sin(pitch)
            val ez = center[2] + distance * cos(pitch) * cos(yaw)
            Matrix.setLookAtM(view, 0, ex, ey, ez, center[0], center[1], center[2], 0f, 1f, 0f)
            Matrix.perspectiveM(proj, 0, 50f, aspect, 0.05f, radius * 30f + 5f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            mesh.draw(vp, verts)
        }
    }

    companion object {
        const val EXTRA_MESH_PATH = "mesh_path"
        private const val VERT_FLOATS = 8
    }
}
