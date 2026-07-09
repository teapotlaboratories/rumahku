package com.teapotlab.rumahku

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hybrid reconstruction: upload a capture to the GPU backend (see backend/serve.py
 * on carbonite-noble), which trains the splat with Brush on a real GPU and returns
 * the `.ply`. The phone only captures + views; the heavy lifting is off-device —
 * the same split Polycam uses (on-device capture, cloud reconstruction).
 *
 * Endpoints: POST /jobs?iters=&max_res= (dataset.zip) → {job_id}; GET /jobs/{id}
 * (status); GET /jobs/{id}/result (the .ply). Uses only the JDK HTTP + zip.
 */
object CloudBuild {

    data class Progress(
        val phase: String,      // Packaging | Uploading | Reconstructing | Downloading | Done
        val pct: Int = -1,      // 0..100 for upload; -1 = indeterminate
        val iter: Int = 0,
        val total: Int = 0,
        val elapsed: Int = 0,
        val jobId: String? = null,   // backend job id (once known) — for cancel
    )

    /**
     * Zip the scan dataset, upload, poll to completion, download the result into
     * `<scanDir>/cloud.ply`. Returns that file. Throws on failure.
     */
    suspend fun build(
        scanDir: File, iters: Int, maxRes: Int, baseUrl: String, onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        onProgress(Progress("Packaging"))
        val zip = File(scanDir.parentFile, "${scanDir.name}_upload.zip")
        try {
            zipDataset(scanDir, zip)

            onProgress(Progress("Uploading", pct = 0))
            val jobId = postJob(zip, iters, maxRes, baseUrl) { pct ->
                onProgress(Progress("Uploading", pct = pct))
            }
            Log.i(TAG, "cloud job $jobId started")

            while (true) {
                val st = getStatus(jobId, baseUrl)
                when (st.optString("state")) {
                    "done" -> break
                    "error" -> throw RuntimeException(st.optString("error", "backend error"))
                    "cancelled" -> throw kotlinx.coroutines.CancellationException("cancelled")
                    "queued" -> onProgress(
                        Progress("Queued", iter = st.optInt("queue_pos"), jobId = jobId))
                    else -> onProgress(
                        Progress("Reconstructing", iter = st.optInt("iter"),
                            total = st.optInt("total"), elapsed = st.optInt("elapsed"), jobId = jobId),
                    )
                }
                delay(2000)
            }

            onProgress(Progress("Downloading", jobId = jobId))
            // The app marks a scan READY when <scanDir>/splat/ holds a .ply and
            // the viewer loads from there.
            val out = File(scanDir, "splat/cloud.ply")
            out.parentFile?.mkdirs()
            downloadResult(jobId, baseUrl, out)
            onProgress(Progress("Done"))
            out
        } finally {
            zip.delete()
        }
    }

    /** Zip transforms.json + images/ + seed.ply (relative paths, no top folder).
     *  Excludes the `splat/` output dir so we never re-ship a prior result. */
    private fun zipDataset(scanDir: File, zipFile: File) {
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            scanDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(scanDir).path
                if (rel.startsWith("splat/") || rel.startsWith("splat\\")) return@forEach
                zos.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun postJob(zip: File, iters: Int, maxRes: Int, baseUrl: String, onUpload: (Int) -> Unit): String {
        val conn = URL("$baseUrl/jobs?iters=$iters&max_res=$maxRes").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/zip")
        conn.setFixedLengthStreamingMode(zip.length())
        conn.connectTimeout = 15000; conn.readTimeout = 60000
        val total = zip.length().coerceAtLeast(1)
        var sent = 0L
        conn.outputStream.buffered().use { os ->
            zip.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = ins.read(buf); if (r < 0) break
                    os.write(buf, 0, r); sent += r
                    onUpload(((sent * 100) / total).toInt())
                }
            }
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) throw RuntimeException("upload failed ($code): $body")
        return JSONObject(body).getString("job_id")
    }

    private fun getStatus(jobId: String, baseUrl: String): JSONObject {
        val conn = URL("$baseUrl/jobs/$jobId").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    }

    private fun downloadResult(jobId: String, baseUrl: String, out: File) {
        val conn = URL("$baseUrl/jobs/$jobId/result").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 120000
        conn.inputStream.use { ins -> out.outputStream().use { ins.copyTo(it) } }
    }

    /** Ask the backend to cancel a job (kills the GPU training). Best-effort. */
    fun cancelJob(jobId: String, baseUrl: String) {
        try {
            val conn = URL("$baseUrl/jobs/$jobId").openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "cancel failed: ${e.message}")
        }
    }

    private const val TAG = "rumahku-cloud"
}
