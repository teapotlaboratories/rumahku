package com.teapotlab.rumahku.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.teapotlab.rumahku.ar.gl.BackgroundRenderer
import com.teapotlab.rumahku.ar.gl.DepthMeshRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The GLSurfaceView renderer that drives ARCore each frame.
 *
 * The GL thread calls onDrawFrame continuously. Each call we:
 *   1. Tell ARCore which texture to draw the camera into.
 *   2. Call session.update() to advance tracking and get the latest [Frame].
 *   3. Draw the camera background.
 *   4. Report the camera's tracking state back to the UI thread.
 *
 * The Session itself is owned by the Activity (it has an Android lifecycle);
 * we access it through [sessionProvider] so the renderer never outlives it.
 */
class ArRenderer(
    private val sessionProvider: () -> Session?,
    private val displayRotationHelper: DisplayRotationHelper,
    private val tsdf: TsdfVolume,
    private val onFrame: (Frame) -> Unit,
    // Fired on the GL thread once the camera background texture exists — the
    // SharedCamera controller needs it before it can open the Camera2 device.
    private val onGlReady: (textureId: Int) -> Unit = {},
    // Live-mesh style: wireframe outline vs. the original solid shaded surface.
    wireframeMesh: Boolean = false,
) : GLSurfaceView.Renderer {

    private val backgroundRenderer = BackgroundRenderer()
    private val depthMeshRenderer = DepthMeshRenderer(wireframeMesh)

    // Scratch matrices reused each frame to avoid per-frame allocation.
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjMatrix = FloatArray(16)
    private var trackLog = 0     // throttles the tracking-state diagnostic log

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        depthMeshRenderer.createOnGlThread()
        onGlReady(backgroundRenderer.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = sessionProvider() ?: return

        // Push any pending display-geometry change before update().
        displayRotationHelper.updateSessionIfNeeded(session)

        try {
            // ARCore must know the target texture *before* update().
            session.setCameraTextureName(backgroundRenderer.textureId)

            val frame = session.update()
            backgroundRenderer.draw(frame)

            // Hand the frame to the listener (status overlay + keyframe capture).
            // Runs on the GL thread — the only place the Frame is valid. Capture
            // may triangulate depth into the mesh here, which we then draw below.
            onFrame(frame)

            // Draw the live depth mesh anchored in world space. Only meaningful
            // while tracking, when the camera matrices are valid.
            val camera = frame.camera
            // Log tracking ~1/sec — cheap diagnostic; distinguishes a tracking
            // loss from a depth-only loss (e.g. under an AE/AWB exposure lock).
            if (trackLog++ % 30 == 0) {
                Log.i(TAG, "trackingState=${camera.trackingState} failure=${camera.trackingFailureReason}")
            }
            if (camera.trackingState == TrackingState.TRACKING) {
                camera.getProjectionMatrix(projMatrix, 0, Z_NEAR, Z_FAR)
                camera.getViewMatrix(viewMatrix, 0)
                Matrix.multiplyMM(viewProjMatrix, 0, projMatrix, 0, viewMatrix, 0)
                // Fresh depth buffer so the mesh self-occludes over the camera bg.
                GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
                depthMeshRenderer.draw(viewProjMatrix, tsdf.snapshot())
            }
        } catch (t: Throwable) {
            // A dropped frame should never crash the render loop.
            Log.e(TAG, "exception on the OpenGL thread", t)
        }
    }

    companion object {
        private const val TAG = "rumahku-render"
        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f
    }
}
