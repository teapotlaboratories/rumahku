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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.teapotlab.rumahku.ar.ArRenderer
import com.teapotlab.rumahku.ar.CoverageBuffer
import com.teapotlab.rumahku.ar.DisplayRotationHelper
import com.teapotlab.rumahku.capture.CaptureProgress
import com.teapotlab.rumahku.capture.CaptureSession

/**
 * The live scanning screen.
 *
 * Owns the ARCore [Session] and ties its lifecycle to the Activity. On top of
 * the camera preview it now drives a [CaptureSession]: tap "Start capture" and,
 * as you move, keyframes (image + pose + intrinsics) are saved to disk while a
 * live counter shows progress. Tap "Stop" to finalize transforms.json.
 */
class CaptureActivity : ComponentActivity() {

    private var session: Session? = null
    private var installRequested = false

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var displayRotationHelper: DisplayRotationHelper
    private lateinit var captureSession: CaptureSession

    // Shared world-space coverage dots: written during capture, drawn each frame.
    private val coverageBuffer = CoverageBuffer()

    // Compose observes these; the GL thread updates them (via runOnUiThread).
    private var status by mutableStateOf(CaptureStatus())
    private var progress by mutableStateOf(CaptureProgress())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayRotationHelper = DisplayRotationHelper(this)
        captureSession = CaptureSession(this, coverageBuffer) { p ->
            runOnUiThread { progress = p }
        }

        glSurfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(
                ArRenderer(
                    sessionProvider = { session },
                    displayRotationHelper = displayRotationHelper,
                    coverageBuffer = coverageBuffer,
                    onFrame = ::onFrame,
                )
            )
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
        }

        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(factory = { glSurfaceView }, modifier = Modifier.fillMaxSize())
                StatusOverlay(status)
                CaptureControls(
                    progress = progress,
                    onToggle = {
                        if (captureSession.isCapturing()) captureSession.stop()
                        else captureSession.start()
                    },
                )
            }
        }
    }

    /** GL-thread per-frame callback: update status + feed the capture session. */
    private fun onFrame(frame: Frame) {
        val camera = frame.camera
        val newStatus = CaptureStatus(camera.trackingState, camera.trackingFailureReason)
        runOnUiThread { if (newStatus != status) status = newStatus }
        captureSession.onFrame(frame)
    }

    override fun onResume() {
        super.onResume()

        if (!hasCameraPermission()) {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> { /* ready */ }
                }
                session = Session(this).also { s ->
                    selectHighestResCameraConfig(s)
                    configureSession(s)
                }
            } catch (e: UnavailableException) {
                Log.e(TAG, "ARCore session unavailable", e)
                Toast.makeText(this, "ARCore unavailable: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }

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
        // Flush any in-progress capture so nothing is lost when leaving.
        if (captureSession.isCapturing()) captureSession.stop()
        if (session != null) {
            displayRotationHelper.onPause()
            glSurfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    /**
     * Picks the camera config with the largest CPU image, so the frames we save
     * via acquireCameraImage() are as high-resolution as the device offers —
     * better input for the Gaussian splat.
     */
    private fun selectHighestResCameraConfig(session: Session) {
        try {
            val filter = CameraConfigFilter(session)
            val best = session.getSupportedCameraConfigs(filter)
                .maxByOrNull { it.imageSize.width * it.imageSize.height }
            if (best != null) {
                session.cameraConfig = best
                Log.i(TAG, "camera config: CPU image ${best.imageSize.width}x${best.imageSize.height}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not select camera config; using default", e)
        }
    }

    private fun configureSession(session: Session) {
        val config = Config(session).apply {
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
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

/** Immutable snapshot of what to show in the status overlay. */
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

@androidx.compose.runtime.Composable
private fun CaptureControls(progress: CaptureProgress, onToggle: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "%d keyframes · %.1f m".format(progress.keyframes, progress.distanceMeters),
                color = Color.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(Color(0xAA000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Button(
                onClick = onToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (progress.capturing) Color(0xFFD32F2F) else Color(0xFF00695C),
                    contentColor = Color.White,
                ),
            ) {
                Text(if (progress.capturing) "Stop" else "Start capture")
            }
        }
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
