package com.teapotlab.rumahku

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.teapotlab.rumahku.ui.theme.RumahkuTheme
import java.io.File

/**
 * Full-screen viewer for a scan's captured keyframes — step through the actual
 * JPEGs the reconstruction was built from with prev/next arrows or the bottom
 * thumbnail strip, pinch to zoom, and share/export any frame. QA tool for
 * eyeballing capture quality (sharpness, exposure, coverage).
 */
class ImageGalleryActivity : ComponentActivity() {
    companion object {
        const val EXTRA_DATASET = "dataset"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dir = intent.getStringExtra(EXTRA_DATASET) ?: run { finish(); return }
        val images = File(dir, "images").listFiles()
            ?.filter { it.extension.equals("jpg", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()
        setContent { RumahkuTheme { GalleryScreen(images, ::finish) } }
    }
}

@Composable
private fun GalleryScreen(images: List<File>, onClose: () -> Unit) {
    val context = LocalContext.current
    if (images.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Text("No frames in this scan", color = Color.White,
                modifier = Modifier.align(Alignment.Center))
            CloseButton(onClose)
        }
        return
    }

    var current by remember { mutableIntStateOf(0) }
    val strip = rememberLazyListState()
    LaunchedEffect(current) { strip.animateScrollToItem(current) }  // keep it in view

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ZoomableImage(images[current])

            Text(
                "${current + 1} / ${images.size}",
                color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            CloseButton(onClose)
            IconButton(
                onClick = { shareImage(context, images[current]) },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 8.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = "Share frame", tint = Color.White)
            }

            if (current > 0) {
                ArrowButton(Alignment.CenterStart, Icons.Filled.KeyboardArrowLeft, "Previous") { current-- }
            }
            if (current < images.size - 1) {
                ArrowButton(Alignment.CenterEnd, Icons.Filled.KeyboardArrowRight, "Next") { current++ }
            }
        }

        // Bottom thumbnail carousel — tap any frame to jump to it.
        LazyRow(
            state = strip,
            modifier = Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(vertical = 8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(images) { i, f ->
                val thumb = remember(f) { decodeUpright(f, 160) }
                Box(
                    Modifier
                        .size(46.dp, 62.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .then(
                            if (i == current) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp))
                            else Modifier
                        )
                        .clickable { current = i },
                ) {
                    if (thumb != null) {
                        Image(thumb.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.fillMaxSize().background(Color.DarkGray))
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ArrowButton(
    align: Alignment, icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String, onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.align(align).padding(horizontal = 4.dp)
            .size(52.dp).clip(CircleShape).background(Color(0x66000000)),
    ) {
        Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun BoxScope.CloseButton(onClose: () -> Unit) {
    IconButton(
        onClick = onClose,
        modifier = Modifier.align(Alignment.TopStart).padding(top = 36.dp, start = 8.dp),
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
    }
}

/** Pinch-to-zoom + pan for the current frame; double-tap resets to fit. */
@Composable
private fun ZoomableImage(file: File) {
    val bmp = remember(file) { decodeUpright(file) } ?: return
    var scale by remember(file) { mutableStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Image(
        bmp.asImageBitmap(), contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(file) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                    if (newScale > 1f) {
                        val maxX = size.width * (newScale - 1f) / 2f
                        val maxY = size.height * (newScale - 1f) / 2f
                        offset = Offset(
                            (offset.x + pan.x).coerceIn(-maxX, maxX),
                            (offset.y + pan.y).coerceIn(-maxY, maxY),
                        )
                    } else {
                        offset = Offset.Zero
                    }
                    scale = newScale
                }
            }
            .pointerInput(file) {
                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
            }
            .graphicsLayer(
                scaleX = scale, scaleY = scale,
                translationX = offset.x, translationY = offset.y,
            ),
    )
}

/** Share/export a single keyframe via the system sheet (content:// FileProvider). */
private fun shareImage(context: Context, file: File) {
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share frame"))
}

/** Decode a keyframe downsampled to ~[maxPx] and rotate it to the upright
 *  (portrait) orientation the phone was held in — keyframes are stored in the
 *  camera's landscape sensor frame (same as the home thumbnails). */
private fun decodeUpright(file: File, maxPx: Int = 1440): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
    val bmp = BitmapFactory.decodeFile(
        file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    bmp?.let {
        val m = Matrix().apply { postRotate(90f) }
        Bitmap.createBitmap(it, 0, 0, it.width, it.height, m, true)
    }
} catch (e: Exception) {
    null
}
