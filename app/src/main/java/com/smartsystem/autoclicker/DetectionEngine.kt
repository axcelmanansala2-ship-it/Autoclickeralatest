package com.smartsystem.autoclicker

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.smartsystem.autoclicker.models.DetectionTarget
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Analyses a [Bitmap] screenshot using ML Kit OCR and returns
 * the center coordinate of the first matched [DetectionTarget].
 *
 * All methods are suspend functions — call from a coroutine.
 */
class DetectionEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Given a screenshot [bitmap] and a list of [targets] (in order),
     * returns the first target whose text was found along with its center point.
     */
    suspend fun findFirst(
        bitmap: Bitmap,
        targets: List<DetectionTarget>
    ): Pair<DetectionTarget, PointF>? {
        val visionResult = runOcr(bitmap) ?: return null
        val blocks = visionResult.textBlocks

        for (target in targets) {
            if (!target.enabled) continue
            val query = target.textQuery.trim()
            val hit = findTextInBlocks(blocks, query)
            if (hit != null) {
                val center = rectCenter(hit)
                Log.d(TAG, "Found '${target.textQuery}' at $center")
                return Pair(target, center)
            }
        }
        return null
    }

    /**
     * Returns all detected text blocks as flat strings for debugging.
     */
    suspend fun debugText(bitmap: Bitmap): String {
        val result = runOcr(bitmap) ?: return "(OCR failed)"
        return result.textBlocks.joinToString("\n") { it.text }
    }

    // ─── private helpers ─────────────────────────────────────────────────────

    private suspend fun runOcr(bitmap: Bitmap): Text? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { text -> cont.resume(text) }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed", e)
                    cont.resume(null)
                }
        }

    /** Search for [query] (case-insensitive) across all text blocks/lines/elements. */
    private fun findTextInBlocks(blocks: List<Text.TextBlock>, query: String): RectF? {
        val q = query.lowercase()
        for (block in blocks) {
            for (line in block.lines) {
                if (line.text.lowercase().contains(q)) {
                    return line.boundingBox?.let {
                        RectF(it.left.toFloat(), it.top.toFloat(),
                              it.right.toFloat(), it.bottom.toFloat())
                    }
                }
                for (element in line.elements) {
                    if (element.text.lowercase().contains(q)) {
                        return element.boundingBox?.let {
                            RectF(it.left.toFloat(), it.top.toFloat(),
                                  it.right.toFloat(), it.bottom.toFloat())
                        }
                    }
                }
            }
        }
        return null
    }

    private fun rectCenter(rect: RectF) = PointF(rect.centerX(), rect.centerY())

    fun close() {
        recognizer.close()
    }

    companion object {
        private const val TAG = "DetectionEngine"
    }
}
