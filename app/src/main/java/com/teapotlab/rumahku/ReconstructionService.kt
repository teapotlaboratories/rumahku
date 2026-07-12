package com.teapotlab.rumahku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Foreground service that runs on-device reconstruction
 * ([BrushTrainer.nativeTrain]) so a multi-minute GPU training survives the app
 * being backgrounded or the screen locking. Holds a partial wakelock and shows
 * a progress notification; publishes state via [running] / [result] for the UI.
 *
 * The heavy work is a blocking native call, so it runs on a dedicated thread; a
 * second thread polls the native progress atomics to refresh the notification.
 */
class ReconstructionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            mgr.getNotificationChannel(CHANNEL_ID) == null
        ) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Reconstruction", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satisfy the startForegroundService() contract before ANY early return, or
        // the OS raises ForegroundServiceDidNotStartInTimeException on a repeat start
        // of an already-running build.
        startForeground(NOTIF_ID, notification("Reconstructing…", ongoing = true))
        val datasetDir = intent?.getStringExtra(EXTRA_DATASET)
        val iters = intent?.getIntExtra(EXTRA_ITERS, TOTAL_ITERS) ?: TOTAL_ITERS
        if (datasetDir == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (_running.value) {
            // Already building (e.g. the user tapped the building card) — don't start
            // a second training thread; the UI just re-observes progress. Foreground
            // is already satisfied above, so the running build keeps its notification.
            return START_NOT_STICKY
        }

        // Auto-adjusted per-device resolution (see DeviceCapability). resolveForRun
        // also reconciles a crash from a previous run (a hard OOM leaves no return
        // value — only a persisted attempt marker — so we detect it here). curRes
        // tracks the live resolution so the progress notification reflects a backoff.
        val maxRes = DeviceCapability.resolveForRun(this)
        val curRes = AtomicInteger(maxRes)
        notify("Starting… (${maxRes}p)", ongoing = true)
        acquireWakeLock()
        _result.value = null
        cancelRequested = false
        _currentDir.value = datasetDir
        _running.value = true

        thread(name = "brush-reconstruct") {
            BrushTrainer.nativeInit(cacheDir.absolutePath)

            val progress = thread(name = "recon-progress") {
                try {
                    while (_running.value) {
                        notify("Training… (${curRes.get()}p) iter ${BrushTrainer.nativeCurrentIter()}, " +
                            "${BrushTrainer.nativeCurrentSplats()} splats", ongoing = true)
                        Thread.sleep(1000)
                    }
                } catch (_: InterruptedException) { /* stopping */ }
            }

            // Train at the chosen resolution. If the trainer returns a *catchable*
            // OOM (the rare non-crash variant), step down the ladder one rung at a
            // time and retry, until it succeeds or hits the floor — each backoff is
            // learned so the next build starts lower. A hard crash never reaches this
            // loop; DeviceCapability recovers from that across runs via the marker.
            val outDir = File(datasetDir, "splat")
            val ctx = applicationContext
            var res = maxRes
            var trained: String
            while (true) {
                DeviceCapability.beginAttempt(ctx, res)
                trained = try {
                    BrushTrainer.nativeTrain(datasetDir, outDir.absolutePath, iters, res, MAX_FRAMES)
                } catch (t: Throwable) {
                    Log.e(TAG, "nativeTrain failed", t)
                    "ERROR: ${t.message}"
                }
                if (cancelRequested) {
                    DeviceCapability.finishAttempt(ctx, res, DeviceCapability.Outcome.OTHER)
                    break
                }
                val errored = trained.startsWith("ERROR")
                if (errored && DeviceCapability.isOomError(trained)) {
                    DeviceCapability.finishAttempt(ctx, res, DeviceCapability.Outcome.OOM)
                    val lower = DeviceCapability.stepBelow(res)
                    if (lower < res) {
                        Log.w(TAG, "caught OOM at ${res}p → retrying at ${lower}p")
                        res = lower
                        curRes.set(res)   // progress notification now shows the lower res
                        continue
                    }
                    break   // already at the floor; give up
                }
                DeviceCapability.finishAttempt(
                    ctx, res,
                    if (errored) DeviceCapability.Outcome.OTHER else DeviceCapability.Outcome.SUCCESS,
                )
                break
            }
            val out = if (cancelRequested) CANCELLED else trained

            _running.value = false
            _currentDir.value = null
            progress.interrupt()
            _result.value = out
            notify(
                when {
                    out == CANCELLED -> "Cancelled"
                    out.startsWith("ERROR") -> out
                    else -> "Done: ${out.substringAfterLast('/')}"
                },
                ongoing = false,
            )
            releaseWakeLock()
            // Keep the final (non-ongoing) notification but leave the foreground.
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun notification(text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("rumahku — reconstructing")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()

    private fun notify(text: String, ongoing: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notification(text, ongoing))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rumahku:reconstruct").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // 1h safety cap
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "rumahku-recon-svc"
        private const val CHANNEL_ID = "reconstruction"
        private const val NOTIF_ID = 42
        private const val EXTRA_DATASET = "dataset_dir"
        private const val EXTRA_ITERS = "iters"

        // Tuned from the on-device iteration study (docs: worklog 2026-07-07):
        // 2000 iters ≈ PSNR 24.8 (near the 25.9 off-device baseline) in ~7 min
        // on Mali at 24–28 °C (no throttling); 2000→3000 adds only +0.5 dB.
        const val TOTAL_ITERS = 2000
        // Resolution is now chosen per-device (RAM-tier + crash-learning); see
        // DeviceCapability. Frame budget stays fixed — resolution is the memory lever.
        private const val MAX_FRAMES = 40

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private val _result = MutableStateFlow<String?>(null)
        val result: StateFlow<String?> = _result

        /** Absolute path of the scan currently being built (null when idle) —
         *  lets the home show progress on that specific card. */
        private val _currentDir = MutableStateFlow<String?>(null)
        val currentDir: StateFlow<String?> = _currentDir

        /** Sentinel result when the user cancels a build. */
        const val CANCELLED = "CANCELLED"

        @Volatile
        private var cancelRequested = false

        /** Start a reconstruction of [datasetDir] for [iters] iterations. */
        fun start(context: Context, datasetDir: String, iters: Int = TOTAL_ITERS) {
            val intent = Intent(context, ReconstructionService::class.java)
                .putExtra(EXTRA_DATASET, datasetDir)
                .putExtra(EXTRA_ITERS, iters)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Cooperatively cancel a running build. The native trainer checks a
         * CANCEL flag each iteration and returns cleanly — so we stop *gracefully*
         * instead of force-killing mid-GPU-op (which can abort the process).
         */
        fun cancel() {
            cancelRequested = true
            BrushTrainer.nativeCancel()
        }
    }
}
