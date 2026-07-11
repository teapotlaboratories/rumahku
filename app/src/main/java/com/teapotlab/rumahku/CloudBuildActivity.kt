package com.teapotlab.rumahku

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teapotlab.rumahku.ui.theme.RumahkuTheme
import java.io.File

/**
 * Watches a hybrid cloud build. The actual work runs in [CloudBuildService] (and
 * the training runs server-side), so leaving this screen — Back or "Keep
 * building in background" — does NOT stop it; the home screen shows progress and
 * you can start another build meanwhile.
 */
class CloudBuildActivity : ComponentActivity() {
    companion object {
        const val EXTRA_DATASET = "dataset"
        const val EXTRA_ITERS = "iters"
        const val EXTRA_MAXRES = "maxres"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = intent.getStringExtra(EXTRA_DATASET) ?: run { finish(); return }
        val iters = intent.getIntExtra(EXTRA_ITERS, 3000)
        val maxRes = intent.getIntExtra(EXTRA_MAXRES, 1920)
        // Ensure the build is running (idempotent — the service ignores a repeat
        // start for a scan already building), then just observe it.
        CloudBuildService.start(this, dir, iters, maxRes)
        setContent { RumahkuTheme { CloudBuildScreen(File(dir), ::finish) } }
    }
}

@Composable
private fun CloudBuildScreen(scanDir: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val key = scanDir.absolutePath
    val allJobs by CloudBuildService.jobs.collectAsState()
    val state = allJobs[key]

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            val s = state
            when {
                s?.error != null -> {
                    Text("Cloud build failed", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text(s.error, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onClose) { Text("Back to home") }
                }
                s?.done == true -> {
                    Text("Your 3D scan is ready!", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(context, SplatViewerActivity::class.java)
                                    .putExtra(SplatViewerActivity.EXTRA_DATASET, scanDir.absolutePath),
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
                    Text("Building in the cloud", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    // Determinate fill when we have a real fraction (upload % or
                    // training iters); an animated indeterminate ring otherwise, so
                    // phases without a count (queued/refining/downloading) never look
                    // frozen at 0.
                    val frac: Float? = when {
                        s?.phase == "Uploading" && s.pct in 0..100 -> s.pct / 100f
                        s?.phase == "Reconstructing" && s.total > 0 && s.iter > 0 ->
                            (s.iter.toFloat() / s.total).coerceIn(0f, 1f)
                        else -> null
                    }
                    if (frac != null) {
                        CircularProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.size(120.dp),
                            strokeWidth = 8.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(120.dp),
                            strokeWidth = 8.dp,
                        )
                    }
                    Text(phaseLabel(s), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    if (s?.phase == "Reconstructing" && s.total > 0) {
                        val pct = if (s.iter > 0) " · ${(100 * s.iter / s.total)}%" else ""
                        Text("${s.iter} / ${s.total} iters$pct · ${s.elapsed}s on GPU",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = onClose, modifier = Modifier.padding(top = 6.dp)) {
                        Text("Keep building in background")
                    }
                    TextButton(onClick = {
                        CloudBuildService.cancel(context, scanDir.absolutePath)
                        onClose()
                    }) {
                        Text("Cancel build", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun phaseLabel(s: CloudBuildService.JobState?): String = when (s?.phase) {
    null, "Starting" -> "Starting…"
    "Packaging" -> "Packaging the scan…"
    "Uploading" -> if (s.pct in 0..100) "Uploading… ${s.pct}%" else "Uploading…"
    "Queued" -> if (s.iter > 1) "Waiting for GPU · #${s.iter} in queue" else "Waiting for GPU…"
    "Refining" -> "Refining camera poses (COLMAP)…"
    "Reconstructing" -> "Reconstructing on GPU…"
    "Evaluating" -> "Scoring quality on held-out views…"
    "Exporting" -> "Exporting the 3D model…"
    "Downloading" -> "Downloading the 3D model…"
    else -> s.phase
}
