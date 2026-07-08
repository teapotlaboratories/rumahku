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
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.teapotlab.rumahku.ui.theme.RumahkuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

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
        val iters = intent.getIntExtra(EXTRA_ITERS, ReconstructionService.TOTAL_ITERS)
        if (datasetDir == null) { finish(); return }
        setContent { RumahkuTheme { ReconstructionScreen(datasetDir, iters) { finish() } } }
    }

    companion object {
        const val EXTRA_DATASET = "dataset_dir"
        const val EXTRA_ITERS = "iters"
    }
}

@Composable
private fun ReconstructionScreen(datasetDir: String, iters: Int, onClose: () -> Unit) {
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
            ReconstructionService.start(context, datasetDir, iters)
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

    // Live preview: render the current training splats every ~1.5 s (M2 "refine
    // live") so you watch the splat sharpen. Null until the first splats exist.
    val preview = remember(datasetDir) { loadPreviewParams(datasetDir) }
    var previewBmp by remember { mutableStateOf<Bitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(running) {
        while (running && preview != null) {
            delay(1500)
            val outW = 480
            val outH = (480L * preview.h / preview.w).toInt().coerceIn(1, 1200)
            val px = withContext(Dispatchers.Default) {
                BrushTrainer.nativeRenderTrainingPreview(
                    preview.transform, 0f, 0f, 1f, preview.flX, preview.flY,
                    preview.w, preview.h, outW, outH, if (preview.gravityUp) 1 else 0,
                )
            }
            if (px != null) previewBmp = Bitmap.createBitmap(px, outW, outH, Bitmap.Config.ARGB_8888)
        }
    }

    val done = everRunning && !running
    val cancelled = done && result == ReconstructionService.CANCELLED
    val error = done && (result?.startsWith("ERROR") == true)

    // A cancelled build just returns home.
    androidx.compose.runtime.LaunchedEffect(cancelled) { if (cancelled) onClose() }

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
                cancelled -> {} // closing (LaunchedEffect above)
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
                    val pct = (iter.toFloat() / iters).coerceIn(0f, 1f)
                    Text("Building your 3D scan", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    val bmp = previewBmp
                    if (bmp != null) {
                        // Live splat preview — watch it converge.
                        Image(
                            bmp.asImageBitmap(), contentDescription = "live preview",
                            modifier = Modifier.fillMaxWidth(0.92f)
                                .aspectRatio(bmp.width.toFloat() / bmp.height)
                                .clip(RoundedCornerShape(18.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Text("${(pct * 100).toInt()}%  ·  $splats splats  ·  ${fmt(elapsed)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
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
                    }
                    Text("Keep the app open — it runs on the phone's GPU.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = { ReconstructionService.cancel() },
                        modifier = Modifier.padding(top = 6.dp),
                    ) { Text("Cancel") }
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

/** Camera params to render the live-training preview from (a mid capture pose). */
private class PreviewParams(
    val transform: FloatArray, val flX: Float, val flY: Float,
    val w: Int, val h: Int, val gravityUp: Boolean,
)

private fun loadPreviewParams(datasetDir: String): PreviewParams? {
    return try {
        val tj = File(datasetDir, "transforms.json").takeIf { it.exists() } ?: return null
        val json = JSONObject(tj.readText())
        val flX = json.optDouble("fl_x", 0.0).toFloat()
        val flY = json.optDouble("fl_y", flX.toDouble()).toFloat()
        val w = json.optInt("w"); val h = json.optInt("h")
        val frames = json.getJSONArray("frames")
        if (flX <= 0f || w <= 0 || h <= 0 || frames.length() == 0) return null
        val tm = frames.getJSONObject(frames.length() / 2).getJSONArray("transform_matrix")
        val m = FloatArray(16); var k = 0
        for (r in 0 until 4) {
            val row = tm.getJSONArray(r)
            for (c in 0 until 4) m[k++] = row.getDouble(c).toFloat()
        }
        PreviewParams(m, flX, flY, w, h, json.optBoolean("gravity_up", false))
    } catch (e: Exception) {
        null
    }
}
