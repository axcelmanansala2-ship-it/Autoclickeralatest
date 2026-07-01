package com.smartsystem.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Service — the only component that can perform real screen taps
 * without root. Uses GestureDescription to dispatch synthetic tap gestures.
 *
 * Grant via: Settings → Accessibility → Smart Auto Clicker → Enable
 */
class AutoClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Perform a single tap at the given screen coordinates.
     * Safe to call from any thread.
     *
     * @param x  Screen X coordinate (pixels)
     * @param y  Screen Y coordinate (pixels)
     * @param onDone Optional callback invoked after the gesture completes or fails
     */
    fun tap(x: Float, y: Float, onDone: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap completed at ($x, $y)")
                onDone?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap cancelled at ($x, $y)")
                onDone?.invoke()
            }
        }, null)
    }

    companion object {
        private const val TAG = "AutoClickService"
        private const val TAP_DURATION_MS = 50L

        /** Singleton reference set when the service connects */
        var instance: AutoClickAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
