package com.teapotlab.rumahku

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Per-device resolution ceiling for on-device Brush training.
 *
 * On-device reconstruction rasterises every view at `max_res`, so peak GPU memory
 * scales with resolution — and the phone's GPU shares system RAM. Too high a
 * `max_res` runs the device out of memory. The Brush FFI is built `panic=abort`,
 * so a GPU OOM usually manifests as a **hard process crash** (SIGABRT), or the
 * kernel OOM-kills us (SIGKILL) — NOT a catchable error. So "auto-adjust" cannot
 * be a simple in-process retry; it has to survive a crash.
 *
 * Strategy ("table + crash-learning"):
 *  1. A RAM-tier table picks a safe default resolution for the device.
 *  2. Before each train we durably record the resolution we're attempting
 *     ([beginAttempt], committed to disk). A clean return from the trainer clears
 *     it ([finishAttempt]). If the process dies, the marker survives — so on the
 *     next run [resolveForRun] sees a stale attempt and treats it as a crash.
 *  3. Learning is asymmetric by confidence:
 *     - A crash in NEW territory (above the proven-good res) is almost certainly a
 *       real OOM, so the ceiling drops one rung immediately.
 *     - A crash at/below a PROVEN-good res is ambiguous (a real regression — hotter
 *       device, heavier scan — vs a one-off external kill). We require
 *       [STRIKE_LIMIT] consecutive such crashes before lowering, so a single
 *       spurious kill doesn't permanently cost a rung.
 *     - A confirmed OOM (either path) also DECAYS the proven-good res below the
 *       failing resolution, so the ceiling can actually settle — a resolution that
 *       once worked but now OOMs is never resurrected.
 *
 * The learned ceiling only ever moves DOWN from the table — we never gamble a
 * resolution above the table's default on our own.
 */
object DeviceCapability {

    private const val TAG = "rumahku-devcap"
    private const val PREFS = "recon_caps"
    private const val K_KNOWN_GOOD = "known_good_res"   // highest res that finished OK here
    private const val K_CRASH_CEIL = "crash_ceiling"    // lowest res confirmed to OOM (0 = none)
    private const val K_ATTEMPT = "attempt_res"         // res of an in-flight train (0 = none)
    private const val K_STRIKE_RES = "strike_res"       // res of the current unconfirmed-crash streak
    private const val K_STRIKE_CNT = "strike_count"     // consecutive stale crashes at strike_res

    /** Consecutive crash-terminated runs at/below the proven-good res before we
     *  lower the ceiling. Any clean return (success/cancel/error) breaks the streak.
     *  Distinguishes a reproducible OOM from a one-off external kill. */
    private const val STRIKE_LIMIT = 2

    /** Discrete resolution rungs. Backoff/step-down always moves along this ladder,
     *  so learned ceilings are clean, testable values. */
    private val LADDER = intArrayOf(512, 640, 720, 900, 1024, 1280)
    private const val FLOOR = 512

    /** Outcome of a training attempt, reported by the service. */
    enum class Outcome { SUCCESS, OOM, OTHER }

    /** Mutable snapshot of the learned per-device state. */
    private class Caps(var knownGood: Int, var crashCeil: Int, var strikeRes: Int, var strikeCount: Int)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readCaps(p: SharedPreferences) = Caps(
        p.getInt(K_KNOWN_GOOD, 0), p.getInt(K_CRASH_CEIL, 0),
        p.getInt(K_STRIKE_RES, 0), p.getInt(K_STRIKE_CNT, 0),
    )

    private fun writeCaps(e: SharedPreferences.Editor, c: Caps): SharedPreferences.Editor =
        e.putInt(K_KNOWN_GOOD, c.knownGood).putInt(K_CRASH_CEIL, c.crashCeil)
            .putInt(K_STRIKE_RES, c.strikeRes).putInt(K_STRIKE_CNT, c.strikeCount)

    /** Total physical RAM in bytes (the GPU shares it on mobile). */
    private fun totalRamBytes(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem
    }

    /** Safe default resolution for this device's RAM. Nominal-8GB phones report
     *  ~7.4-7.8 GiB (the kernel reserves some), so the thresholds sit below the
     *  round numbers. Crash-learning corrects any tier that proves too optimistic. */
    private fun ramTierRes(ctx: Context): Int {
        val gib = totalRamBytes(ctx) / (1024.0 * 1024.0 * 1024.0)
        return when {
            gib >= 11.0 -> 1280   // ~12 GB
            gib >= 7.0  -> 1024   // ~8 GB  (Pixel 6 = 7.4 GiB; 1024 validated on-device)
            gib >= 5.3  -> 900    // ~6 GB
            gib >= 3.4  -> 720    // ~4 GB
            else        -> 512
        }
    }

    /** Largest ladder rung strictly below [res] (or [FLOOR] if none). */
    fun stepBelow(res: Int): Int = LADDER.lastOrNull { it < res } ?: FLOOR

    /** Snap [x] down to the nearest ladder rung (always in [[FLOOR], LADDER.last()]). */
    private fun snap(x: Int): Int = LADDER.lastOrNull { it <= x } ?: FLOOR

    /** Resolution to train at, given the learned state — no side effects.
     *  Invariant maintained by [foldOom]: knownGood <= stepBelow(crashCeil), so the
     *  `max(_, knownGood)` floor can never resurrect a resolution that OOMs. */
    private fun computeRes(ramTier: Int, knownGood: Int, crashCeil: Int): Int {
        var r = ramTier
        if (crashCeil > 0) r = minOf(r, stepBelow(crashCeil))  // stay below a known OOM
        if (knownGood > 0) r = maxOf(r, knownGood)             // don't regress below proven-good
        return snap(r)
    }

    /** Record a CONFIRMED OOM at [res]: cap the ceiling and decay proven-good below it. */
    private fun foldOom(c: Caps, res: Int) {
        c.crashCeil = if (c.crashCeil <= 0) res else minOf(c.crashCeil, res)
        if (c.knownGood > 0) c.knownGood = minOf(c.knownGood, stepBelow(c.crashCeil))
        c.strikeRes = 0; c.strikeCount = 0
    }

    /** Fold a stale attempt marker (the previous run's process died) into the learned
     *  state. New territory lowers immediately; a proven-good res needs [STRIKE_LIMIT]
     *  strikes. Mutates [c]; used by both [plannedRes] (on a throwaway copy) and
     *  [resolveForRun] (persisted). */
    private fun reconcileCrash(c: Caps, attempt: Int) {
        if (attempt == 0) return
        if (attempt > c.knownGood) {
            foldOom(c, attempt)                                // new territory → confident OOM
        } else {
            if (c.strikeRes == attempt) c.strikeCount++ else { c.strikeRes = attempt; c.strikeCount = 1 }
            if (c.strikeCount >= STRIKE_LIMIT) foldOom(c, attempt)
        }
    }

    /**
     * Resolution the NEXT on-device build would use, WITHOUT mutating state — for a
     * UI label. Reflects a pending (crashed) attempt so the label is honest, but
     * doesn't commit the backoff; that happens in [resolveForRun].
     */
    fun plannedRes(ctx: Context, buildRunning: Boolean = false): Int {
        val p = prefs(ctx)
        val c = readCaps(p)
        // A present attempt marker means "crashed" only when NO build is running; while
        // one is in flight the marker is the live resolution, not a crash — don't fold it.
        if (!buildRunning) reconcileCrash(c, p.getInt(K_ATTEMPT, 0))   // local copy only — no persist
        return computeRes(ramTierRes(ctx), c.knownGood, c.crashCeil)
    }

    /**
     * Reconcile any crash from the previous run, then return the resolution to train
     * at now. Call once at the start of a build (before [beginAttempt]).
     */
    fun resolveForRun(ctx: Context): Int {
        val p = prefs(ctx)
        val c = readCaps(p)
        val attempt = p.getInt(K_ATTEMPT, 0)
        val ram = ramTierRes(ctx)
        if (attempt != 0) {
            reconcileCrash(c, attempt)
            writeCaps(p.edit().remove(K_ATTEMPT), c).commit()
            Log.w(TAG, "stale attempt ${attempt}p → known=${c.knownGood} crashCeil=${c.crashCeil} " +
                "strike=${c.strikeRes}x${c.strikeCount}")
        }
        val res = computeRes(ram, c.knownGood, c.crashCeil)
        Log.i(TAG, "resolveForRun: ram=$ram known=${c.knownGood} crashCeil=${c.crashCeil} → ${res}p")
        return res
    }

    /** Durably mark [res] as the in-flight attempt. MUST be committed (not apply())
     *  so it survives a hard crash a moment later. */
    fun beginAttempt(ctx: Context, res: Int) {
        prefs(ctx).edit().putInt(K_ATTEMPT, res).commit()
    }

    /** Record the outcome of the attempt at [res] and clear the in-flight marker. Any
     *  clean return (even an error) proves the process survived, so the marker is
     *  always cleared — only a real crash leaves it set for the next run. */
    fun finishAttempt(ctx: Context, res: Int, outcome: Outcome) {
        val p = prefs(ctx)
        val c = readCaps(p)
        val e = p.edit().remove(K_ATTEMPT)
        when (outcome) {
            Outcome.SUCCESS -> c.knownGood = maxOf(c.knownGood, res)
            Outcome.OOM -> {
                foldOom(c, res)                                // catchable OOM is unambiguous
                Log.w(TAG, "caught OOM at ${res}p → crashCeil=${c.crashCeil} known=${c.knownGood}")
            }
            Outcome.OTHER -> { /* non-OOM error / cancel: process survived; ceiling untouched */ }
        }
        // Any clean, non-OOM return proves this run did NOT hard-crash at res, so it
        // breaks a consecutive-crash streak there (foldOom already resets on OOM).
        if (outcome != Outcome.OOM && c.strikeRes == res) { c.strikeRes = 0; c.strikeCount = 0 }
        writeCaps(e, c).commit()
    }

    /** Does a trainer "ERROR: …" string look like an out-of-memory / device-lost
     *  failure (the catchable variant)? Hard crashes never reach here. Matches only
     *  specific wgpu/Vulkan signatures — no bare "oom" token (it would match paths
     *  like "bedroom"). */
    fun isOomError(msg: String): Boolean {
        val m = msg.lowercase()
        return "out of memory" in m || "out of device memory" in m ||
            "erroroutofdevicememory" in m || "outofmemory" in m ||
            "device lost" in m || "devicelost" in m || "device_lost" in m ||
            "failed to allocate" in m || "allocation failed" in m
    }
}
