package com.teapotlab.rumahku

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.teapotlab.rumahku.ui.theme.RumahkuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * M4 — fullscreen, Matterport/Polycam-style **walkthrough** of an on-device
 * splat. The render fills the screen (system bars hidden); the whole surface is
 * the control: **drag = look around**, **pinch = zoom**, **double-tap = move**
 * to the next capture point. Rendered on the phone GPU via
 * [BrushTrainer.nativeRenderLook]; cached renders (~a few hundred ms) keep it live.
 */
class SplatViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Immersive: draw edge-to-edge and hide the status/nav bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val datasetDir = intent.getStringExtra(EXTRA_DATASET)
        setContent { RumahkuTheme { WalkthroughScreen(datasetDir) } }
    }

    companion object {
        const val EXTRA_DATASET = "dataset_dir"
    }
}

private class Scene(
    val plyPath: String,
    val flX: Float,
    val flY: Float,
    val w: Int,
    val h: Int,
    val frames: List<FloatArray>,
)

private fun loadScene(datasetDir: String): Scene? {
    val dir = File(datasetDir)
    val tj = File(dir, "transforms.json").takeIf { it.exists() } ?: return null
    val ply = File(dir, "splat").listFiles()
        ?.filter { it.extension == "ply" }?.maxByOrNull { it.lastModified() } ?: return null
    val json = JSONObject(tj.readText())
    val flX = json.optDouble("fl_x", 0.0).toFloat()
    val flY = json.optDouble("fl_y", flX.toDouble()).toFloat()
    val w = json.optInt("w"); val h = json.optInt("h")
    if (flX <= 0f || w <= 0 || h <= 0) return null
    val arr = json.getJSONArray("frames")
    val frames = ArrayList<FloatArray>(arr.length())
    for (i in 0 until arr.length()) {
        val tm = arr.getJSONObject(i).getJSONArray("transform_matrix")
        val m = FloatArray(16)
        var k = 0
        for (r in 0 until 4) {
            val row = tm.getJSONArray(r)
            for (c in 0 until 4) m[k++] = row.getDouble(c).toFloat()
        }
        frames.add(m)
    }
    return Scene(ply.absolutePath, flX, flY, w, h, frames.ifEmpty { return null })
}

/** World position of a capture standpoint (translation of its c2w matrix). */
private fun standpointPos(m: FloatArray) = floatArrayOf(m[3], m[7], m[11])

/**
 * Pick the standpoint to move to on a directional double-tap: the nearest one
 * that lies within ~66° of the look direction [fwd]. Falls back to the next
 * standpoint if nothing is ahead (or [fwd] is unavailable).
 */
private fun nextStandpoint(frames: List<FloatArray>, cur: Int, fwd: FloatArray?): Int {
    if (fwd == null || fwd.size < 3) return (cur + 1) % frames.size
    val p = standpointPos(frames[cur])
    var best = -1
    var bestDist = Float.MAX_VALUE
    for (j in frames.indices) {
        if (j == cur) continue
        val pj = standpointPos(frames[j])
        val dx = pj[0] - p[0]; val dy = pj[1] - p[1]; val dz = pj[2] - p[2]
        val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 1e-4f) continue
        val cos = (dx * fwd[0] + dy * fwd[1] + dz * fwd[2]) / dist // alignment with look dir
        if (cos > 0.4f && dist < bestDist) {
            best = j; bestDist = dist
        }
    }
    return if (best >= 0) best else (cur + 1) % frames.size
}

/** Share the exported .ply via the system sheet (content:// URI, FileProvider). */
private fun sharePly(context: Context, plyPath: String) {
    val file = File(plyPath)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share splat (.ply)"))
}

@Composable
private fun WalkthroughScreen(datasetDir: String?) {
    val scene = remember(datasetDir) { datasetDir?.let { runCatching { loadScene(it) }.getOrNull() } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        if (scene == null) {
            Text("No reconstruction to view yet.", color = Color.White,
                modifier = Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }

        var sp by remember { mutableIntStateOf(scene.frames.size / 2) }
        var yaw by remember { mutableFloatStateOf(0f) }
        var pitch by remember { mutableFloatStateOf(0f) }
        var fov by remember { mutableFloatStateOf(1f) }
        var bmp by remember { mutableStateOf<Bitmap?>(null) }
        var rendering by remember { mutableStateOf(false) }
        val ctx = LocalContext.current

        // Render at the screen's aspect (downscaled for speed; upscaled to fill).
        val wPx = constraints.maxWidth.coerceAtLeast(1)
        val hPx = constraints.maxHeight.coerceAtLeast(1)
        val outW = 560
        val outH = (560L * hPx / wPx).toInt().coerceIn(1, 1400)

        LaunchedEffect(sp, yaw, pitch, fov) {
            delay(35)
            rendering = true
            val px = withContext(Dispatchers.Default) {
                BrushTrainer.nativeRenderLook(
                    scene.plyPath, scene.frames[sp], yaw, pitch, fov,
                    scene.flX, scene.flY, scene.w, scene.h, outW, outH,
                )
            }
            bmp = px?.let { Bitmap.createBitmap(it, outW, outH, Bitmap.Config.ARGB_8888) }
            rendering = false
        }

        val surface = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    yaw += pan.x * 0.15f
                    pitch = (pitch - pan.y * 0.15f).coerceIn(-80f, 80f)
                    if (zoom != 1f) fov = (fov / zoom).coerceIn(0.4f, 2.2f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    // Move to the standpoint in the direction we're looking.
                    val fwd = BrushTrainer.nativeLookForward(scene.frames[sp], yaw, pitch)
                    sp = nextStandpoint(scene.frames, sp, fwd)
                    yaw = 0f; pitch = 0f
                })
            }

        // Fullscreen render (the whole surface is the control).
        bmp?.let {
            Image(it.asImageBitmap(), contentDescription = "walkthrough",
                modifier = surface, contentScale = ContentScale.FillBounds)
        } ?: androidx.compose.foundation.layout.Box(surface)

        if (bmp == null) {
            Text("rendering…", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        // ── Minimal overlay ──────────────────────────────────────────────────
        Pill("${sp + 1} / ${scene.frames.size}",
            Modifier.align(Alignment.TopCenter).padding(top = 28.dp))

        IconPill(Icons.Filled.Refresh, "recenter", Modifier
            .align(Alignment.TopEnd)
            .padding(top = 24.dp, end = 16.dp)
            .clickable { yaw = 0f; pitch = 0f; fov = 1f })

        IconPill(Icons.Filled.Share, "share", Modifier
            .align(Alignment.TopStart)
            .padding(top = 24.dp, start = 16.dp)
            .clickable { sharePly(ctx, scene.plyPath) })

        Text("drag: look   ·   pinch: zoom   ·   double-tap: move",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp))
    }
}

@Composable
private fun IconPill(icon: ImageVector, desc: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
