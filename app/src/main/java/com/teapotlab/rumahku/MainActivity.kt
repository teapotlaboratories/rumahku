package com.teapotlab.rumahku

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.teapotlab.rumahku.ar.ArAvailability
import com.teapotlab.rumahku.ar.ArStatus
import com.teapotlab.rumahku.ui.theme.RumahkuTheme
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home — the scan library. Bright, friendly (see docs/UX.md). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RumahkuTheme { HomeScreen() } }
    }
}

private enum class ScanStatus { READY, CAPTURED }
private class Scan(val dir: File, val title: String, val thumb: File?, val status: ScanStatus)

@Composable
private fun HomeScreen() {
    val context = LocalContext.current

    // Reload scans whenever we come back to this screen (after capture / view).
    var reloadKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) reloadKey++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val reconstructing by ReconstructionService.running.collectAsState()
    val scans = remember(reloadKey, reconstructing) { loadScans(context) }

    // Long-press management: an action chooser → rename or delete dialog.
    var manageScan by remember { mutableStateOf<Scan?>(null) }
    var renameTarget by remember { mutableStateOf<Scan?>(null) }
    var deleteTarget by remember { mutableStateOf<Scan?>(null) }
    var buildScan by remember { mutableStateOf<Scan?>(null) }   // quality picker

    // Live build progress for the scan currently reconstructing (shown on its card).
    val currentDir by ReconstructionService.currentDir.collectAsState()
    var buildPct by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reconstructing) {
        while (reconstructing) {
            buildPct = (BrushTrainer.nativeCurrentIter().toFloat() /
                ReconstructionService.TOTAL_ITERS).coerceIn(0f, 1f)
            delay(500)
        }
    }

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCamera = granted
        if (granted) startCapture(context)
    }

    fun newScan() {
        when {
            !hasCamera -> requestCamera.launch(Manifest.permission.CAMERA)
            ArAvailability.check(context) != ArStatus.Ready ->
                Toast.makeText(context, "AR isn't available on this device yet", Toast.LENGTH_SHORT).show()
            else -> startCapture(context)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newScan() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New scan") },
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 20.dp, 16.dp, 96.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("rumahku", style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Scan a room, walk it in 3D",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (scans.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyState() }
            } else {
                items(scans) { scan ->
                    ScanCard(
                        scan,
                        progress = if (reconstructing && scan.dir.absolutePath == currentDir) buildPct else null,
                        onClick = {
                            if (scan.status == ScanStatus.READY) openViewer(context, scan)
                            else buildScan = scan
                        },
                        onLongClick = { manageScan = scan },
                    )
                }
            }
        }
    }

    // ── Long-press: manage → rename / delete ─────────────────────────────────
    manageScan?.let { s ->
        AlertDialog(
            onDismissRequest = { manageScan = null },
            title = { Text(s.title, fontWeight = FontWeight.Bold) },
            text = { Text("Rename or delete this scan.") },
            confirmButton = {
                TextButton(onClick = { renameTarget = s; manageScan = null }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = s; manageScan = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
    renameTarget?.let { s ->
        var text by remember(s) { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename scan") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it },
                    singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = { renameScan(s.dir, text); renameTarget = null; reloadKey++ }) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete scan?") },
            text = { Text("“${s.title}” will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { s.dir.deleteRecursively(); deleteTarget = null; reloadKey++ }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
    buildScan?.let { s ->
        AlertDialog(
            onDismissRequest = { buildScan = null },
            title = { Text("Build quality") },
            text = {
                Column {
                    QualityOption("Quick", "1000 iters · fastest") { launchBuild(context, s, 1000); buildScan = null }
                    QualityOption("Balanced", "2000 iters · recommended") { launchBuild(context, s, 2000); buildScan = null }
                    QualityOption("High", "3000 iters · sharpest") { launchBuild(context, s, 3000); buildScan = null }
                }
            },
            confirmButton = { TextButton(onClick = { buildScan = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QualityOption(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openViewer(context: Context, scan: Scan) {
    context.startActivity(
        Intent(context, SplatViewerActivity::class.java)
            .putExtra(SplatViewerActivity.EXTRA_DATASET, scan.dir.absolutePath)
    )
}

private fun launchBuild(context: Context, scan: Scan, iters: Int) {
    context.startActivity(
        Intent(context, ReconstructionActivity::class.java)
            .putExtra(ReconstructionActivity.EXTRA_DATASET, scan.dir.absolutePath)
            .putExtra(ReconstructionActivity.EXTRA_ITERS, iters)
    )
}

private fun startCapture(context: Context) {
    context.startActivity(Intent(context, CaptureActivity::class.java))
}

/** Immersive scan card: photo fills the card, title over a gradient scrim.
 *  When [progress] is non-null the scan is building — show a live ring. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScanCard(scan: Scan, progress: Float?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val thumb = remember(scan.thumb?.path) { scan.thumb?.let { decodeThumb(it) } }
    Card(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.82f)) {
            Thumb(thumb, Modifier.fillMaxSize())
            // Bottom scrim so white text stays legible over any photo.
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))))
            if (progress != null) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(56.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                }
            } else {
                StatusDotChip(scan.status, Modifier.align(Alignment.TopStart).padding(10.dp))
                // ▶ over photos only — the placeholder already shows a house glyph.
                if (scan.status == ScanStatus.READY && thumb != null) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null,
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.align(Alignment.Center).size(46.dp))
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                Text(scan.title, color = Color.White,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (progress != null) "Building ${(progress * 100).toInt()}%" else secondaryLabel(scan.status),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** Photo thumbnail, or a sleek gradient + line icon placeholder. */
@Composable
private fun Thumb(thumb: Bitmap?, modifier: Modifier) {
    if (thumb != null) {
        Image(thumb.asImageBitmap(), contentDescription = null,
            modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(
            modifier.background(Brush.linearGradient(listOf(
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.primaryContainer))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Home, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
                modifier = Modifier.size(48.dp))
        }
    }
}

/** Frosted chip: a status dot + short label. */
@Composable
private fun StatusDotChip(status: ScanStatus, modifier: Modifier) {
    val (label, dot) = when (status) {
        ScanStatus.READY -> "Ready" to MaterialTheme.colorScheme.primary
        ScanStatus.CAPTURED -> "New" to MaterialTheme.colorScheme.tertiary
    }
    Row(
        modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.88f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(label, color = Color.Black.copy(alpha = 0.78f),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun secondaryLabel(status: ScanStatus): String = when (status) {
    ScanStatus.READY -> "Tap to walk through"
    ScanStatus.CAPTURED -> "Tap to build 3D"
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(84.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Home, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(40.dp))
        }
        Text("No scans yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Tap “New scan” to capture your first room",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── data ──────────────────────────────────────────────────────────────────
private fun loadScans(context: Context): List<Scan> {
    val root = rumahkuRoot(context)
    val dirs = File(root, "captures").listFiles()
        ?.filter { it.isDirectory && File(it, "transforms.json").exists() }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()
    return dirs.map { dir ->
        val ready = File(dir, "splat").listFiles()?.any { it.extension == "ply" } == true
        val thumb = File(dir, "images/000000.jpg").takeIf { it.exists() }
        val custom = File(dir, "name.txt").takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
        Scan(dir, custom ?: friendlyTitle(dir.name), thumb,
            if (ready) ScanStatus.READY else ScanStatus.CAPTURED)
    }
}

/** Persist a user-chosen display name for a scan (blank → revert to the date). */
private fun renameScan(dir: File, name: String) {
    val f = File(dir, "name.txt")
    if (name.isBlank()) f.delete() else f.writeText(name.trim())
}

private fun friendlyTitle(name: String): String {
    val ts = name.toLongOrNull()
    return if (ts != null) {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ts))
    } else {
        name.replaceFirstChar { it.uppercase() }
    }
}

private fun decodeThumb(file: File, maxPx: Int = 500): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (e: Exception) {
    null
}
