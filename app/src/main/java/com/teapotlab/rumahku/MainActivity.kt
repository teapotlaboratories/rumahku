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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        }
    }
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
