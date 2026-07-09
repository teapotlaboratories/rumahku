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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
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
import com.teapotlab.rumahku.ar.TsdfVolume
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

    // Shared live surface: depth fused into a TSDF volume + marching cubes.
    private val tsdf = TsdfVolume()

    // Compose observes these; the GL thread updates them (via runOnUiThread).
    private var status by mutableStateOf(CaptureStatus())
    private var progress by mutableStateOf(CaptureProgress())
    private var coveragePoints by mutableStateOf(0)
    private var frameTick = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayRotationHelper = DisplayRotationHelper(this)
        captureSession = CaptureSession(this, tsdf) { p ->
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
                    tsdf = tsdf,
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
                    tracking = status.tracking == TrackingState.TRACKING,
                    coveragePoints = coveragePoints,
                    onToggle = {
                        if (captureSession.isCapturing()) {
                            captureSession.stop()
                            Toast.makeText(this@CaptureActivity, "Scan saved", Toast.LENGTH_SHORT).show()
                            finish() // back to home — the new scan appears there
                        } else {
                            captureSession.start()
                        }
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

        // Live mesh readout (throttled) — grows as depth fuses into the surface.
        if (++frameTick % 6 == 0) {
            val c = tsdf.triangleCount()
            if (c != coveragePoints) runOnUiThread { coveragePoints = c }
        }
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
            // Fixed focus: no autofocus hunting (refocus blur) and stable camera
            // intrinsics across frames — both matter a lot for 3DGS/SfM quality.
            focusMode = Config.FocusMode.FIXED
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            // Depth-from-motion powers the Polycam-style coverage overlay
            // (TsdfVolume). Degrade gracefully where it isn't supported.
            depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                Config.DepthMode.AUTOMATIC
            } else {
                Log.w(TAG, "Depth API unsupported; coverage falls back to keyframe dots")
                Config.DepthMode.DISABLED
            }
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
private fun CaptureControls(
    progress: CaptureProgress,
    tracking: Boolean,
    coveragePoints: Int,
    onToggle: () -> Unit,
) {
    val coral = Color(0xFFFF6B5E)
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (progress.capturing) {
                Text(
                    text = "%d keyframes · %.1f m".format(progress.keyframes, progress.distanceMeters),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Color(0x88000000), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                // Live mesh — grows as the surface reconstructs.
                Text(
                    text = "mesh · %,d faces".format(coveragePoints),
                    color = Color(0xFF34E0C0),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Color(0x88000000), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            // Camera-style record button: coral circle to start, coral square to finish.
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.28f))
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (progress.capturing) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(coral))
                } else {
                    Box(Modifier.size(66.dp).clip(CircleShape).background(coral))
                }
            }
            Text(
                text = when {
                    progress.capturing -> "Tap to finish"
                    tracking -> "Tap to start scanning"
                    else -> "Point at the room to begin"
                },
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
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
