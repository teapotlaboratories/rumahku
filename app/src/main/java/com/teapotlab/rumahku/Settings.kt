package com.teapotlab.rumahku

import android.content.Context

/**
 * Small persisted settings (SharedPreferences). Currently just the cloud-build
 * backend URL, so the server address isn't hard-coded.
 */
object Settings {
    private const val PREFS = "rumahku_prefs"
    private const val KEY_BACKEND_URL = "backend_url"

    /** Default backend (reachable over the NetBird VPN). */
    const val DEFAULT_BACKEND_URL = "http://carbonite-noble.kugelblitz.internal:8000"

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
}
