package com.smartsystem.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service.
 *
 * Two capabilities:
 *  1. findNodeByText(text) — searches the entire UI tree for any node
 *     whose text/contentDescription contains [text] (case-insensitive).
 *     Returns the screen-center of the first match, or null.
 *
 *  2. tap(x, y) — dispatches a synthetic tap gesture at the given coords.
 *
 * This avoids OCR entirely; it reads the actual Android view hierarchy,
 * which works perfectly for launcher icons, buttons, text views, etc.
 *
 * Enable via: Settings → Accessibility → Smart Auto Clicker → Enable
 * (canRetrieveWindowContent must be true in accessibility_service_config.xml)
 */
class AutoClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "Accessibility service connected — node finding ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* unused */ }
    override fun onInterrupt() { Log.w(TAG, "Service interrupted") }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ─── Node finding ─────────────────────────────────────────────────────────

    /**
     * Search all windows for a node whose text OR contentDescription contains [query].
     * Returns the screen-center [PointF] of the first match, or null if not found.
     *
     * Call from a background thread — this does NOT block the main thread.
     */
    fun findNodeCenter(query: String): PointF? {
        val q = query.lowercase().trim()

        // Try rootInActiveWindow first (fastest)
        val roots = mutableListOf<AccessibilityNodeInfo?>()
        try {
            roots.add(rootInActiveWindow)
        } catch (e: Exception) {
            Log.w(TAG, "rootInActiveWindow failed", e)
        }

        // Also check all windows (catches launcher icons, system UI, etc.)
        try {
            windows?.forEach { win ->
                try { roots.add(win.root) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "windows enumeration failed", e)
        }

        for (root in roots) {
            if (root == null) continue
            val result = searchNode(root, q)
            try { root.recycle() } catch (_: Exception) {}
            if (result != null) return result
        }
        return null
    }

    private fun searchNode(node: AccessibilityNodeInfo, query: String): PointF? {
        // Check this node
        val nodeText = (node.text?.toString() ?: "").lowercase()
        val nodeDesc = (node.contentDescription?.toString() ?: "").lowercase()
        if (nodeText.contains(query) || nodeDesc.contains(query)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
                Log.d(TAG, "Found '$query' at bounds=$bounds text='${node.text}' desc='${node.contentDescription}'")
                return PointF(bounds.exactCenterX(), bounds.exactCenterY())
            }
        }
        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val result = searchNode(child, query)
            try { child.recycle() } catch (_: Exception) {}
            if (result != null) return result
        }
        return null
    }

    // ─── Tap gesture ──────────────────────────────────────────────────────────

    /**
     * Dispatch a single tap at (x, y) in screen coordinates.
     * [onDone] is called when the gesture completes or is cancelled.
     */
    fun tap(x: Float, y: Float, onDone: (() -> Unit)? = null) {
        Log.d(TAG, "Tapping ($x, $y)")
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { onDone?.invoke() }
            override fun onCancelled(g: GestureDescription?) {
                Log.w(TAG, "Tap cancelled at ($x, $y)")
                onDone?.invoke()
            }
        }, null)
    }

    companion object {
        private const val TAG = "AutoClickService"
        private const val TAP_DURATION_MS = 80L

        var instance: AutoClickAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
