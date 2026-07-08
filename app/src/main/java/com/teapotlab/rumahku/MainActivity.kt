package com.teapotlab.rumahku

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.teapotlab.rumahku.ar.ArAvailability
import com.teapotlab.rumahku.ar.ArStatus
import com.teapotlab.rumahku.ui.theme.RumahkuTheme

/**
 * MainActivity — the single entry-point screen for Phase 1.
 *
 * What it does today:
 *   • Loads the native library and shows its version (proves the NDK bridge).
 *   • Checks whether ARCore is available on this device.
 *   • Requests the CAMERA permission.
 *
 * This is deliberately a "diagnostics" screen: once it reports everything green
 * on the real S25, we know the whole toolchain is sound and can start building
 * the actual capture pipeline (ARCore session + keyframe capture) on top.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RumahkuTheme {
                DiagnosticsScreen()
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current

    // ── Camera permission state ───────────────────────────────────────────────
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestCamera = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    // ── ARCore availability (checked once when the screen first composes) ─────
    val arStatus: ArStatus = remember { ArAvailability.check(context) }

    // ── Native bridge check — a failure here throws at load time, so reaching
    //    this point already means the .so loaded successfully. ─────────────────
    val nativeVersion = remember {
        runCatching { NativeBridge.nativeVersion() }.getOrElse { "unavailable: ${it.message}" }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("rumahku", style = MaterialTheme.typography.headlineMedium)
            Text("Phase 1 · toolchain diagnostics", style = MaterialTheme.typography.bodyMedium)

            StatusLine("Native (NDK/JNI)", nativeVersion)
            StatusLine("ARCore", arStatus.label())
            StatusLine("Camera permission", if (hasCamera) "granted" else "not granted")

            if (!hasCamera) {
                Button(onClick = { requestCamera.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera permission")
                }
            }

            // Ready to scan only when ARCore is available and we have the camera.
            val canScan = arStatus == ArStatus.Ready && hasCamera
            Button(
                enabled = canScan,
                onClick = {
                    context.startActivity(android.content.Intent(context, CaptureActivity::class.java))
                },
            ) {
                Text("Start scanning")
            }

            ReconstructSection()
        }
    }
}

/**
 * Phase 2 (M1) — on-device reconstruction. Finds the latest captured scan and
 * trains a Gaussian splat from it via [BrushTrainer] (Brush on the phone GPU),
 * showing live progress. Training is GPU-heavy and runs off the main thread.
 */
@Composable
private fun ReconstructSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    // Poll native progress while a training run is in flight.
    LaunchedEffect(running) {
        while (running) {
            status = "training… iter ${BrushTrainer.nativeCurrentIter()}, " +
                "${BrushTrainer.nativeCurrentSplats()} splats"
            delay(400)
        }
    }

    val latest = remember(running) { latestCaptureDir(context) }

    Button(
        enabled = !running && latest != null,
        onClick = {
            val dataset = latest ?: return@Button
            running = true
            result = null
            status = "starting…"
            scope.launch {
                val out = withContext(Dispatchers.Default) {
                    BrushTrainer.nativeInit(context.cacheDir.absolutePath)
                    val outDir = File(dataset, "splat")
                    // Conservative first-pass caps (memory/thermals); tune later.
                    BrushTrainer.nativeTrain(
                        dataset.absolutePath, outDir.absolutePath,
                        /* totalIters = */ 500, /* maxRes = */ 720, /* maxFrames = */ 40,
                    )
                }
                running = false
                result = out
                status = if (out.startsWith("ERROR")) out else "done → $out"
            }
        },
    ) {
        Text(if (latest == null) "Reconstruct (no scan yet)" else "Reconstruct latest scan")
    }

    status?.let { StatusLine("Reconstruction", it) }
    result?.takeIf { !it.startsWith("ERROR") }?.let { StatusLine("Splat", it.substringAfterLast('/')) }
}

/** Most-recent capture dir that has a transforms.json, or null. */
private fun latestCaptureDir(context: android.content.Context): File? {
    val root = context.getExternalFilesDir(null) ?: context.filesDir
    return File(root, "captures").listFiles()
        ?.filter { it.isDirectory && File(it, "transforms.json").exists() }
        ?.maxByOrNull { it.lastModified() }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Text(
        text = "$label:  $value",
        style = MaterialTheme.typography.bodyLarge,
    )
}

/** Human-readable one-liner for each ARCore state. */
private fun ArStatus.label(): String = when (this) {
    ArStatus.Ready -> "ready"
    ArStatus.NeedsInstall -> "needs install/update"
    ArStatus.Checking -> "checking… (reopen app)"
    ArStatus.Unsupported -> "unsupported on this device"
}
