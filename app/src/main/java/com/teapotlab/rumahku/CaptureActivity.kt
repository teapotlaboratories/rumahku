package com.teapotlab.rumahku

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.teapotlab.rumahku.ar.ArRenderer
import com.teapotlab.rumahku.ar.DisplayRotationHelper

/**
 * The live scanning screen.
 *
 * Owns the ARCore [Session] and ties its lifecycle to the Activity:
 *   • onResume  → ensure ARCore is installed, create/resume the session
 *   • onPause   → pause the session and the GL surface
 *   • onDestroy → close the session and free the camera
 *
 * The camera feed is drawn by [ArRenderer] onto a [GLSurfaceView]; a small
 * Compose overlay on top shows the current tracking state as live feedback.
 * This slice is preview-only — keyframe capture is the next step.
 */
class CaptureActivity : ComponentActivity() {

    private var session: Session? = null
    private var installRequested = false

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var displayRotationHelper: DisplayRotationHelper

    // Compose observes this; the GL thread updates it once per frame.
    private var status by mutableStateOf(CaptureStatus())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayRotationHelper = DisplayRotationHelper(this)

        glSurfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            // RGBA8 + 16-bit depth, no stencil — enough for camera + future 3D.
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(
                ArRenderer(
                    sessionProvider = { session },
                    displayRotationHelper = displayRotationHelper,
                    onCameraState = ::onCameraState,
                )
            )
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
        }

        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                // The camera preview surface fills the screen.
                AndroidView(factory = { glSurfaceView }, modifier = Modifier.fillMaxSize())
                // Status overlay pinned to the top.
                StatusOverlay(status)
            }
        }
    }

    /** Called on the GL thread every frame — hop to the UI thread to update state. */
    private fun onCameraState(camera: Camera) {
        val newStatus = CaptureStatus(
            tracking = camera.trackingState,
            reason = camera.trackingFailureReason,
        )
        // Only touch Compose state on the main thread.
        runOnUiThread {
            if (newStatus != status) status = newStatus
        }
    }

    override fun onResume() {
        super.onResume()

        // Camera permission is requested on the diagnostics screen, but guard here
        // too — this Activity could be launched directly.
        if (!hasCameraPermission()) {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (session == null) {
            try {
                // Prompt to install/update Google Play Services for AR if needed.
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return  // We'll be resumed again after the install flow.
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> { /* ready */ }
                }
                session = Session(this).also(::configureSession)
            } catch (e: UnavailableException) {
                Log.e(TAG, "ARCore session unavailable", e)
                Toast.makeText(this, "ARCore unavailable: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }

        // Resume the session; the camera may momentarily be held by another app.
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "camera not available on resume", e)
            Toast.makeText(this, "Camera not available", Toast.LENGTH_LONG).show()
            session = null
            finish()
            return
        }

        glSurfaceView.onResume()
        displayRotationHelper.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            // Order matters: stop rendering before pausing the session so the GL
            // thread doesn't touch a paused session.
            displayRotationHelper.onPause()
            glSurfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        // Explicitly release the camera and native resources.
        session?.close()
        session = null
        super.onDestroy()
    }

    /** Configures tracking options for this preview-only slice. */
    private fun configureSession(session: Session) {
        val config = Config(session).apply {
            focusMode = Config.FocusMode.AUTO
            // Don't block the GL thread waiting for a new camera image.
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            // Depth stays OFF for now: we don't consume it yet, and ARCore's ML
            // depth provider floods logcat with errors in low light. We'll turn
            // it back on when we build the live point-cloud preview.
            depthMode = Config.DepthMode.DISABLED
        }
        session.configure(config)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "rumahku-capture"
    }
}

/** Immutable snapshot of what to show in the overlay. */
data class CaptureStatus(
    val tracking: TrackingState? = null,
    val reason: TrackingFailureReason? = null,
)

@androidx.compose.runtime.Composable
private fun StatusOverlay(status: CaptureStatus) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = status.hint(),
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .background(Color(0xAA000000), RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

/** Human-readable guidance derived from ARCore's tracking state. */
private fun CaptureStatus.hint(): String = when (tracking) {
    TrackingState.TRACKING -> "Tracking · move slowly to scan"
    TrackingState.PAUSED -> when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark — add light"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Slow down"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at a textured surface"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
        else -> "Initializing…"
    }
    TrackingState.STOPPED, null -> "Starting camera…"
}
