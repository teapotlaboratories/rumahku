package com.teapotlab.rumahku

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.teapotlab.rumahku.ui.theme.RumahkuTheme
import kotlinx.coroutines.delay

/**
 * Reconstruction progress screen — the middle of the journey (see docs/UX.md).
 * Starts the [ReconstructionService] for a scan, shows live progress (iteration
 * %, splat count, elapsed, a friendly message), and hands off to the walkthrough
 * when done.
 */
class ReconstructionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val datasetDir = intent.getStringExtra(EXTRA_DATASET)
        if (datasetDir == null) { finish(); return }
        setContent { RumahkuTheme { ReconstructionScreen(datasetDir) { finish() } } }
    }

    companion object {
        const val EXTRA_DATASET = "dataset_dir"
    }
}

@Composable
private fun ReconstructionScreen(datasetDir: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val running by ReconstructionService.running.collectAsState()
    val result by ReconstructionService.result.collectAsState()

    var iter by remember { mutableIntStateOf(0) }
    var splats by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var everRunning by remember { mutableStateOf(false) }

    val requestNotif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    // Start the reconstruction once.
    var started by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!started) {
            started = true
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            ReconstructionService.start(context, datasetDir)
        }
    }

    // Poll native progress while training.
    androidx.compose.runtime.LaunchedEffect(running) {
        if (running) everRunning = true
        val t0 = SystemClock.elapsedRealtime()
        while (running) {
            iter = BrushTrainer.nativeCurrentIter()
            splats = BrushTrainer.nativeCurrentSplats()
            elapsed = (SystemClock.elapsedRealtime() - t0) / 1000
            delay(300)
        }
    }

    val done = everRunning && !running
    val error = done && (result?.startsWith("ERROR") == true)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            when {
                error -> {
                    IconBadge(Icons.Filled.Close,
                        MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
                    Text("Reconstruction failed", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text(result?.removePrefix("ERROR:")?.trim() ?: "Unknown error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onClose) { Text("Back to home") }
                }
                done -> {
                    IconBadge(Icons.Filled.Check,
                        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary)
                    Text("Your 3D scan is ready!", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(context, SplatViewerActivity::class.java)
                                    .putExtra(SplatViewerActivity.EXTRA_DATASET, datasetDir)
                            )
                            onClose()
                        },
                        modifier = Modifier.fillMaxWidth(0.8f),
                    ) { Text("Open walkthrough") }
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth(0.8f)) {
                        Text("Back to home")
                    }
                }
                else -> {
                    val pct = (iter.toFloat() / ReconstructionService.TOTAL_ITERS).coerceIn(0f, 1f)
                    Text("Building your 3D scan", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
                        CircularProgressIndicator(
                            progress = { if (iter > 0) pct else 0f },
                            modifier = Modifier.size(180.dp),
                            strokeWidth = 10.dp,
                        )
                        Text(if (iter > 0) "${(pct * 100).toInt()}%" else "…",
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(friendlyMessage(iter),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text("$splats splats · ${fmt(elapsed)} elapsed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Keep the app open — it runs on the phone's GPU.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A large icon inside a soft coloured circle — the app's status-emphasis mark. */
@Composable
private fun IconBadge(icon: ImageVector, container: androidx.compose.ui.graphics.Color, tint: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier.size(96.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(52.dp))
    }
}

private fun friendlyMessage(iter: Int): String = when {
    iter <= 0 -> "Warming up the GPU…"
    iter < 400 -> "Getting started…"
    iter < 1200 -> "Building the 3D splat…"
    else -> "Almost there…"
}

private fun fmt(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
