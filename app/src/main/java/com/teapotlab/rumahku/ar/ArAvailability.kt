package com.teapotlab.rumahku.ar

import android.content.Context
import com.google.ar.core.ArCoreApk

/**
 * Thin wrapper around ARCore's device/support checks.
 *
 * Before we can open an AR camera session we must know:
 *   1. Does this device support ARCore at all?
 *   2. Is the "Google Play Services for AR" APK installed and up to date?
 *
 * ARCore models this as [ArCoreApk.Availability]. We collapse it into a small
 * sealed result so the UI layer can react without touching ARCore types.
 */
sealed interface ArStatus {
    /** Ready to create an AR session. */
    data object Ready : ArStatus

    /** Supported, but the ARCore APK needs installing/updating (user prompt). */
    data object NeedsInstall : ArStatus

    /** ARCore is still deciding — the caller should re-check shortly. */
    data object Checking : ArStatus

    /** This device cannot run ARCore. */
    data object Unsupported : ArStatus
}

object ArAvailability {

    /**
     * One-shot check of ARCore availability.
     *
     * Note: the first call on a fresh install may return [ArStatus.Checking]
     * because ARCore queries Google servers asynchronously. Callers should
     * poll again after a short delay in that case.
     */
    fun check(context: Context): ArStatus =
        when (ArCoreApk.getInstance().checkAvailability(context)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED ->
                ArStatus.Ready

            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ->
                ArStatus.NeedsInstall

            ArCoreApk.Availability.UNKNOWN_CHECKING ->
                ArStatus.Checking

            ArCoreApk.Availability.UNKNOWN_ERROR,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                ArStatus.Unsupported
        }
}
