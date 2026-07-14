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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
    val gravityUp: Boolean,
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
    // ARCore captures are gravity-aligned (+Y up) so the walkthrough can be
    // leveled; imported datasets (COLMAP) have an arbitrary up, so don't.
    val gravityUp = json.optBoolean("gravity_up", false)
    return Scene(ply.absolutePath, flX, flY, w, h, frames.ifEmpty { return null }, gravityUp)
}

/** World position of a capture standpoint (translation of its c2w matrix). */
private fun standpointPos(m: FloatArray) = floatArrayOf(m[3], m[7], m[11])

/** Euclidean distance from camera [pose]'s position to the nearest capture standpoint. */
private fun nearestStandpointDist(frames: List<FloatArray>, pose: FloatArray): Float {
    val p = standpointPos(pose)
    var best = Float.MAX_VALUE
    for (f in frames) {
        val q = standpointPos(f)
        val dx = q[0] - p[0]; val dy = q[1] - p[1]; val dz = q[2] - p[2]
        val d = dx * dx + dy * dy + dz * dz
        if (d < best) best = d
    }
    return kotlin.math.sqrt(best)
}

/** Index of the capture standpoint nearest camera [pose] (keeps the position counter honest). */
private fun nearestStandpointIdx(frames: List<FloatArray>, pose: FloatArray): Int {
    val p = standpointPos(pose)
    var best = Float.MAX_VALUE; var bi = 0
    for (i in frames.indices) {
        val q = standpointPos(frames[i])
        val dx = q[0] - p[0]; val dy = q[1] - p[1]; val dz = q[2] - p[2]
        val d = dx * dx + dy * dy + dz * dz
        if (d < best) { best = d; bi = i }
    }
    return bi
}

/**
 * Best capture standpoint ahead of camera [pose] along look direction [fwd]: within a
 * ~75° forward cone, ranked by alignment-over-distance (nearer + better aligned wins).
 * Returns -1 when nothing is ahead — the caller stays put rather than blindly cycling
 * to the next recorded frame (the old behaviour that felt like "it just advances").
 */
private fun forwardStandpoint(frames: List<FloatArray>, pose: FloatArray, fwd: FloatArray?): Int {
    if (fwd == null || fwd.size < 3) return -1
    val p = standpointPos(pose)
    var best = -1; var bestScore = 0f
    for (j in frames.indices) {
        val q = standpointPos(frames[j])
        val dx = q[0] - p[0]; val dy = q[1] - p[1]; val dz = q[2] - p[2]
        val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 1e-3f) continue
        val cos = (dx * fwd[0] + dy * fwd[1] + dz * fwd[2]) / dist   // alignment with look dir
        if (cos <= 0.25f) continue                                  // ~75° cone ahead
        val score = cos / dist                                      // aligned + near wins
        if (score > bestScore) { bestScore = score; best = j }
    }
    return best
}

/** Median nearest-neighbour spacing of the standpoints — sets the walk step + drift cap
 *  in scene units so movement feels the same regardless of the reconstruction's scale. */
private fun standpointSpacing(frames: List<FloatArray>): Float {
    if (frames.size < 2) return 1f
    val nn = FloatArray(frames.size)
    for (i in frames.indices) {
        val a = standpointPos(frames[i]); var best = Float.MAX_VALUE
        for (j in frames.indices) if (j != i) {
            val b = standpointPos(frames[j])
            val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
            val d = dx * dx + dy * dy + dz * dz
            if (d < best) best = d
        }
        nn[i] = kotlin.math.sqrt(best)
    }
    nn.sort()
    return nn[nn.size / 2].coerceAtLeast(1e-3f)
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

        val start = scene.frames.size / 2
        var sp by remember { mutableIntStateOf(start) }
        // Live camera pose: a c2w matrix whose translation we move for free-fly walking.
        // Teleports copy a standpoint's pose; walking edits only the position, so you
        // keep looking where you're going.
        var pose by remember { mutableStateOf(scene.frames[start].copyOf()) }
        var yaw by remember { mutableFloatStateOf(0f) }
        var pitch by remember { mutableFloatStateOf(0f) }
        var fov by remember { mutableFloatStateOf(1f) }
        var version by remember { mutableIntStateOf(0) }   // bumped on every gesture change
        var bmp by remember { mutableStateOf<Bitmap?>(null) }
        val ctx = LocalContext.current

        // Walk step + drift cap in scene units (median standpoint spacing).
        val spacing = remember(scene) { standpointSpacing(scene.frames) }
        val step = spacing * 0.45f       // forward distance per double-tap
        val maxDrift = spacing * 0.9f    // how far you may free-fly from any standpoint
        // Teleport the camera onto capture standpoint [i], facing its captured view.
        fun snapTo(i: Int) {
            sp = i.coerceIn(0, scene.frames.size - 1)
            pose = scene.frames[sp].copyOf(); yaw = 0f; pitch = 0f; version++
        }

        // Render at the screen's aspect (downscaled for speed; upscaled to fill).
        val wPx = constraints.maxWidth.coerceAtLeast(1)
        val hPx = constraints.maxHeight.coerceAtLeast(1)
        val outW = 560
        val outH = (560L * hPx / wPx).toInt().coerceIn(1, 1400)
        val DRAG_RES = 340   // lower res used while the view is actively moving

        suspend fun renderAt(w: Int) {
            val h = (w.toLong() * hPx / wPx).toInt().coerceIn(1, 1400)
            val px = withContext(Dispatchers.Default) {
                BrushTrainer.nativeRenderLook(
                    scene.plyPath, pose, yaw, pitch, fov,
                    scene.flX, scene.flY, scene.w, scene.h, w, h,
                    if (scene.gravityUp) 1 else 0,
                )
            }
            if (px != null) bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
        // Continuously render the LATEST view. A gesture just bumps `version`; we
        // never cancel the in-flight render — when it finishes we render the
        // current position again, so the view tracks your finger in real time (at
        // render fps) instead of only updating on release. Once you stop moving
        // for a moment, one full-resolution pass sharpens it.
        LaunchedEffect(Unit) {
            var lastLow = -1
            var lastSharp = -1
            var lastChangeAt = 0L
            while (true) {
                val v = version
                when {
                    v != lastLow -> {
                        lastLow = v
                        lastChangeAt = android.os.SystemClock.elapsedRealtime()
                        renderAt(DRAG_RES)
                    }
                    v != lastSharp &&
                        android.os.SystemClock.elapsedRealtime() - lastChangeAt > 150 -> {
                        lastSharp = v
                        renderAt(outW)
                    }
                    else -> delay(12)
                }
            }
        }

        val surface = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // Scale look sensitivity by the zoom level so a drag moves the
                    // view by a consistent on-screen amount — finer when zoomed in.
                    yaw += pan.x * 0.15f * fov
                    pitch = (pitch + pan.y * 0.15f * fov).coerceIn(-80f, 80f)
                    if (zoom != 1f) fov = (fov / zoom).coerceIn(0.4f, 2.2f)
                    version++
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    // Double-tap = WALK forward along your gaze. A splat has no geometry
                    // where the camera never went, so stay within maxDrift of a captured
                    // standpoint; when a step would leave the data, hop to the next scan
                    // standpoint ahead instead (and if there's none ahead, stay put).
                    onDoubleTap = {
                        val fwd = BrushTrainer.nativeLookForward(pose, yaw, pitch)
                        if (fwd != null) {
                            val cand = pose.copyOf()
                            cand[3] += fwd[0] * step; cand[7] += fwd[1] * step; cand[11] += fwd[2] * step
                            if (nearestStandpointDist(scene.frames, cand) <= maxDrift) {
                                pose = cand
                                sp = nearestStandpointIdx(scene.frames, pose)
                                version++
                            } else {
                                val j = forwardStandpoint(scene.frames, pose, fwd)
                                if (j >= 0) snapTo(j)
                            }
                        }
                    },
                    // Long-press = fast-travel straight to the next scan standpoint ahead.
                    onLongPress = {
                        val j = forwardStandpoint(scene.frames, pose,
                            BrushTrainer.nativeLookForward(pose, yaw, pitch))
                        if (j >= 0) snapTo(j)
                    },
                )
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
        // Frame counter flanked by step arrows — walk along the captured frames.
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconPill(Icons.Filled.KeyboardArrowLeft, "previous standpoint",
                Modifier.clickable { snapTo(sp - 1) })
            Pill("${sp + 1} / ${scene.frames.size}")
            IconPill(Icons.Filled.KeyboardArrowRight, "next standpoint",
                Modifier.clickable { snapTo(sp + 1) })
        }

        IconPill(Icons.Filled.Refresh, "recenter", Modifier
            .align(Alignment.TopEnd)
            .padding(top = 24.dp, end = 16.dp)
            .clickable { yaw = 0f; pitch = 0f; fov = 1f; version++ })

        IconPill(Icons.Filled.Share, "share", Modifier
            .align(Alignment.TopStart)
            .padding(top = 24.dp, start = 16.dp)
            .clickable { sharePly(ctx, scene.plyPath) })

        Text("drag: look   ·   pinch: zoom   ·   2-tap: walk   ·   hold: jump ahead",
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
