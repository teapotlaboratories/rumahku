package com.teapotlab.rumahku

import android.content.Context

/**
 * Small persisted settings (SharedPreferences). Currently just the cloud-build
 * backend URL, so the server address isn't hard-coded.
 */
object Settings {
    private const val PREFS = "rumahku_prefs"
    private const val KEY_BACKEND_URL = "backend_url"
    private const val KEY_BACKEND_TOKEN = "backend_token"
    private const val KEY_HIGH_RES = "high_res_capture"
    private const val KEY_WIREFRAME_MESH = "wireframe_mesh"

    /** Default backend (reachable over the NetBird VPN). */
    const val DEFAULT_BACKEND_URL = "http://carbonite-noble.kugelblitz.internal:8000"

    /** Capture keyframes from a high-res still stream (vs ARCore's 1080p CPU
     *  image). Off by default — more detail but bigger files + a Camera2 still
     *  per keyframe. Read once when the capture session starts. */
    fun highResCapture(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HIGH_RES, false)

    fun setHighResCapture(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIGH_RES, on).apply()
    }

    /** Draw the live mesh as a wireframe outline (vs. the original solid, shaded
     *  surface). Off by default. Read once when the capture session starts. */
    fun wireframeMesh(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WIREFRAME_MESH, false)

    fun setWireframeMesh(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIREFRAME_MESH, on).apply()
    }

    fun backendUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL)
            ?.trim()
            ?.trimEnd('/')
            ?.ifEmpty { DEFAULT_BACKEND_URL }
            ?: DEFAULT_BACKEND_URL

    fun setBackendUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND_URL, url.trim().trimEnd('/'))
            .apply()
    }

    /** Optional bearer token for the backend. Empty (the default) = no auth
     *  header, for the open single-user backend. When set it's sent as
     *  `Authorization: Bearer <token>` on every /jobs request and must match the
     *  backend's `RUMAHKU_TOKEN`. */
    fun backendToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND_TOKEN, "")?.trim() ?: ""

    fun setBackendToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND_TOKEN, token.trim())
            .apply()
    }
}
