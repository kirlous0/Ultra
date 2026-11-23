package com.example.processing

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Immutable
data class SceneLabelObservation(
    val label: String,
    val confidence: Float
)

/**
 * On-device ML Kit Image Labeling wrapper using the bundled offline model.
 * Produces structured content labels for live AI scene classification and preset adaptation.
 */
class MlKitSceneClassifier {

    private val labeler by lazy {
        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.52f)
            .build()
        ImageLabeling.getClient(options)
    }

    suspend fun classifyBitmap(bitmap: Bitmap): List<SceneLabelObservation> =
        suspendCancellableCoroutine { cont ->
            if (bitmap.isRecycled) {
                if (cont.isActive) cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            val input = InputImage.fromBitmap(bitmap, 0)
            labeler.process(input)
                .addOnSuccessListener { labels ->
                    if (cont.isActive) {
                        val mapped = labels
                            .sortedByDescending { it.confidence }
                            .take(6)
                            .map { SceneLabelObservation(it.text, it.confidence) }
                        cont.resume(mapped)
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    fun close() {
        runCatching { labeler.close() }
    }
}
