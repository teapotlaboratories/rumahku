package com.teapotlab.rumahku

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Root directory under which scans (`captures/…`) are stored.
 *
 * Normally the app's external files dir. But some devices — e.g. certain Samsung
 * / Android-16 configs — return a **non-null external path that isn't actually
 * writable** (`open()` → `EACCES`), so the usual `getExternalFilesDir(null) ?:
 * filesDir` fallback never triggers and every capture write fails silently.
 *
 * Here we probe the external dir with a real write and fall back to internal
 * storage if it fails. Readers (loadScans) and writers (CaptureSession) must
 * both call this so they agree on the location.
 */
fun rumahkuRoot(context: Context): File {
    val ext = context.getExternalFilesDir(null)
    if (ext != null && isWritable(ext)) return ext
    Log.w("rumahku-storage", "external files dir not writable; using internal storage")
    return context.filesDir
}

/**
 * Probe a **nested** write (dir + file), matching how captures are actually
 * written (`captures/<ts>/images/…`). Some broken FUSE/scoped-storage states
 * allow a write at the root but reject nested subdirs, so a root-only probe
 * gives a false positive.
 */
private fun isWritable(root: File): Boolean = try {
    val probeDir = File(root, ".wtest/nested")
    probeDir.mkdirs()
    val probe = File(probeDir, "probe.tmp")
    probe.outputStream().use { it.write(0) }
    val ok = probe.exists()
    File(root, ".wtest").deleteRecursively()
    ok
} catch (e: Exception) {
    false
}
