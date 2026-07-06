package com.teapotlab.rumahku.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Camera
import com.google.ar.core.Session
import com.teapotlab.rumahku.ar.gl.BackgroundRenderer
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
    private val onCameraState: (Camera) -> Unit,
) : GLSurfaceView.Renderer {

    private val backgroundRenderer = BackgroundRenderer()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
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

            // Surface the camera pose/tracking state to whoever is listening.
            onCameraState(frame.camera)
        } catch (t: Throwable) {
            // A dropped frame should never crash the render loop.
            Log.e(TAG, "exception on the OpenGL thread", t)
        }
    }

    companion object {
        private const val TAG = "rumahku-render"
    }
}
