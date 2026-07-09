package com.teapotlab.rumahku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Runs hybrid cloud builds in the background so they survive leaving the
 * progress page (the training runs server-side regardless; this keeps the
 * upload/poll/download alive) and so several scans can build at once.
 *
 * State is published in [jobs] (keyed by scan-dir path) for the home screen +
 * the progress activity to observe. Foreground service (dataSync) with a
 * progress notification.
 */
class CloudBuildService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            mgr.getNotificationChannel(CHANNEL_ID) == null
        ) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Cloud build", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val dir = intent?.getStringExtra(EXTRA_DATASET) ?: run { stopSelf(); return START_NOT_STICKY }
        val iters = intent.getIntExtra(EXTRA_ITERS, 3000)
        val maxRes = intent.getIntExtra(EXTRA_MAXRES, 1920)

        startForeground(NOTIF_ID, notification("Building your 3D scan…"))

        val existing = jobs.value[dir]
        if (existing != null && !existing.done && existing.error == null) {
            return START_NOT_STICKY // already building this scan; UI just re-observes
        }
        setJob(dir, JobState("Starting"))
        val baseUrl = Settings.backendUrl(this)
        scope.launch {
            try {
                CloudBuild.build(File(dir), iters, maxRes, baseUrl) { p ->
                    setJob(dir, JobState(p.phase, p.pct, p.iter, p.total, p.elapsed))
                }
                setJob(dir, JobState("Done", done = true))
            } catch (e: Exception) {
                setJob(dir, JobState("Error", error = e.message ?: "cloud build failed"))
            } finally {
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private fun setJob(dir: String, s: JobState) {
        jobs.value = jobs.value + (dir to s)
        val active = activeCount()
        if (active > 0) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, notification("Building $active 3D scan${if (active > 1) "s" else ""}…"))
        }
    }

    private fun activeCount() = jobs.value.values.count { !it.done && it.error == null }

    private fun stopIfIdle() {
        if (activeCount() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("rumahku — cloud build")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    data class JobState(
        val phase: String,
        val pct: Int = -1,
        val iter: Int = 0,
        val total: Int = 0,
        val elapsed: Int = 0,
        val done: Boolean = false,
        val error: String? = null,
    )

    companion object {
        const val EXTRA_DATASET = "dataset"
        const val EXTRA_ITERS = "iters"
        const val EXTRA_MAXRES = "maxres"
        private const val CHANNEL_ID = "cloud_build"
        private const val NOTIF_ID = 43

        /** Live per-scan build state, observed by the home + progress screens. */
        val jobs = MutableStateFlow<Map<String, JobState>>(emptyMap())

        fun start(context: Context, scanDir: String, iters: Int, maxRes: Int) {
            val i = Intent(context, CloudBuildService::class.java)
                .putExtra(EXTRA_DATASET, scanDir)
                .putExtra(EXTRA_ITERS, iters)
                .putExtra(EXTRA_MAXRES, maxRes)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
