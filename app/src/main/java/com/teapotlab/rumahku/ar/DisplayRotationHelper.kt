package com.teapotlab.rumahku.ar

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.google.ar.core.Session

/**
 * Keeps ARCore informed about the display's size and rotation.
 *
 * ARCore needs to know the viewport dimensions and device rotation so it can
 * correctly project the camera image and 3D content. Those can change at any
 * time (the user rotates the phone, folds a foldable, etc.), so we listen for
 * display changes and push the new geometry into the Session on the next frame.
 *
 * Usage: call onResume/onPause with the Activity lifecycle, onSurfaceChanged
 * from the GL renderer, and updateSessionIfNeeded() once per frame before
 * session.update().
 */
class DisplayRotationHelper(context: Context) : DisplayManager.DisplayListener {

    private var viewportChanged = false
    private var viewportWidth = 0
    private var viewportHeight = 0

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    @Suppress("DEPRECATION")
    private val display: Display =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display ?: error("no display on this context")
        } else {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        }

    fun onResume() = displayManager.registerDisplayListener(this, null)

    fun onPause() = displayManager.unregisterDisplayListener(this)

    /** Called by the GL renderer when the surface size is known/changes. */
    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    /**
     * Pushes the latest geometry into the Session if anything changed. Must run
     * on the GL thread, before session.update().
     */
    fun updateSessionIfNeeded(session: Session) {
        if (viewportChanged) {
            session.setDisplayGeometry(display.rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
    }

    // DisplayManager.DisplayListener — we only care that *something* changed.
    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        viewportChanged = true
    }
}
