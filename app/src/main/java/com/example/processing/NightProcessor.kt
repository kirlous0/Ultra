package com.example.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

data class NightProcessResult(
    val bitmap: Bitmap,
    val isTripodDetected: Boolean,
    val isUnstableCapture: Boolean,
    val usedFrames: Int,
    val discardedBlurFrames: Int
)

class NightProcessor(
    private val alignmentEngine: ImageAlignmentEngine = ImageAlignmentEngine()
) {

    /**
     * Multi-frame night reconstruction:
     * 1. Smart frame selection (discards severely shaken frames relative to sharpest frame).
     * 2. Subpixel-guided spatial alignment & tripod detection.
     * 3. Robust Huber temporal merge with motion-ghost rejection.
     * 4. Adaptive low-light exposure fusion + edge-preserving chroma denoise.
     */
    fun processNight(
        frames: List<Bitmap>,
        fullPower: Boolean = false,
        astroMode: Boolean = false,
        lowLightBoost: Boolean = true
    ): NightProcessResult {
        require(frames.isNotEmpty()) { "Night processor requires at least 1 frame" }
        val w = frames[0].width
        val h = frames[0].height

        val alignment = alignmentEngine.analyzeAndAlign(frames, fullPower = fullPower || astroMode)
        val bestSharpness = alignment.sharpnessScores.maxOrNull() ?: 1.0
        val refIdx = alignment.referenceIndex

        val sharpThreshold = if (fullPower || astroMode) 0.48 else 0.55
        val selectedIndices = frames.indices.filter { idx ->
            idx == refIdx || alignment.sharpnessScores[idx] >= bestSharpness * sharpThreshold
        }
        val discarded = frames.size - selectedIndices.size

        val refPixels = IntArray(w * h)
        frames[refIdx].getPixels(refPixels, 0, w, 0, 0, w, h)

        val accumR = FloatArray(w * h)
        val accumG = FloatArray(w * h)
        val accumB = FloatArray(w * h)
        val accumW = FloatArray(w * h)

        val framePixels = IntArray(w * h)
        val motionSigma = when {
            astroMode -> 22f
            fullPower -> 29f
            else -> 26f
        }

        for (idx in selectedIndices) {
            frames[idx].getPixels(framePixels, 0, w, 0, 0, w, h)
            val (dx, dy) = alignment.shifts[idx]
            val sx = dx.roundToInt()
            val sy = dy.roundToInt()

            ParallelCompute.parallelRows(h, fullPower || astroMode) { startY, endY ->
                for (y in startY until endY) {
                    val srcY = (y - sy).coerceIn(0, h - 1)
                    val outRow = y * w
                    val srcRow = srcY * w
                    for (x in 0 until w) {
                        val srcX = (x - sx).coerceIn(0, w - 1)
                        val outIdx = outRow + x
                        val argb = framePixels[srcRow + srcX]
                        val r = (argb ushr 16) and 0xFF
                        val g = (argb ushr 8) and 0xFF
                        val b = argb and 0xFF

                        val wMotion = if (idx == refIdx) {
                            1.0f
                        } else {
                            val refArgb = refPixels[outIdx]
                            val dr = abs(r - ((refArgb ushr 16) and 0xFF))
                            val dg = abs(g - ((refArgb ushr 8) and 0xFF))
                            val db = abs(b - (refArgb and 0xFF))
                            val dist = (dr + dg * 2 + db) * 0.25f
                            exp(-(dist * dist) / (2f * motionSigma * motionSigma))
                        }

                        accumR[outIdx] += r * wMotion
                        accumG[outIdx] += g * wMotion
                        accumB[outIdx] += b * wMotion
                        accumW[outIdx] += wMotion
                    }
                }
            }
        }

        // Measure merged frame illumination & color cast on strided grid
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sampleCount = 0L
        val step = 12
        var sy = 0
        while (sy < h) {
            val row = sy * w
            var sx = 0
            while (sx < w) {
                val i = row + sx
                val invW = 1f / accumW[i].coerceAtLeast(1e-4f)
                sumR += (accumR[i] * invW) / 255.0
                sumG += (accumG[i] * invW) / 255.0
                sumB += (accumB[i] * invW) / 255.0
                sampleCount++
                sx += step
            }
            sy += step
        }
        val cnt = sampleCount.coerceAtLeast(1L).toFloat()
        val meanR = (sumR / cnt).toFloat().coerceAtLeast(0.02f)
        val meanG = (sumG / cnt).toFloat().coerceAtLeast(0.02f)
        val meanB = (sumB / cnt).toFloat().coerceAtLeast(0.02f)
        val avgLumaNorm = (0.299f * meanR + 0.587f * meanG + 0.114f * meanB).coerceIn(0.02f, 1f)

        // Gentle 18% residual gray-world WB cast damping when artificial dim light skews channels
        val gray = ((meanR + meanG + meanB) / 3f).coerceAtLeast(0.04f)
        val wbMix = if (lowLightBoost && avgLumaNorm < 0.42f) 0.20f else 0f
        val rWbGain = (1f + ((gray / meanR).coerceIn(0.75f, 1.35f) - 1f) * wbMix)
        val gWbGain = (1f + ((gray / meanG).coerceIn(0.82f, 1.22f) - 1f) * wbMix)
        val bWbGain = (1f + ((gray / meanB).coerceIn(0.75f, 1.35f) - 1f) * wbMix)

        // Adaptive low-light / Astro shadow reconstruction + highlight/starlight protection
        val mergedR = IntArray(w * h)
        val mergedG = IntArray(w * h)
        val mergedB = IntArray(w * h)

        val dimExtra = if (lowLightBoost && avgLumaNorm < 0.22f) 0.04f else 0f
        val shadowLiftGamma = when {
            astroMode -> 0.65f
            alignment.isTripodStatic -> (0.71f - dimExtra)
            else -> (0.76f - dimExtra)
        }.coerceIn(0.62f, 0.84f)

        ParallelCompute.parallelRows(h, fullPower || astroMode) { startY, endY ->
            for (y in startY until endY) {
                val off = y * w
                for (x in 0 until w) {
                    val i = off + x
                    val invW = 1f / accumW[i].coerceAtLeast(1e-4f)
                    val nr = ((accumR[i] * invW) / 255f * rWbGain).coerceIn(0f, 1f)
                    val ng = ((accumG[i] * invW) / 255f * gWbGain).coerceIn(0f, 1f)
                    val nb = ((accumB[i] * invW) / 255f * bWbGain).coerceIn(0f, 1f)

                    val luma = 0.299f * nr + 0.587f * ng + 0.114f * nb
                    val highlightGuard = (1f - luma).coerceIn(0.18f, 1f)
                    val gain = if (luma > 0.008f) {
                        val targetLuma = (luma.pow(shadowLiftGamma) * (0.88f + 0.28f * highlightGuard))
                            .coerceIn(0f, 1f)
                        (targetLuma / luma).coerceIn(0.84f, if (astroMode) 2.75f else if (lowLightBoost) 2.55f else 2.30f)
                    } else 1.22f

                    mergedR[i] = (nr * gain * 255f).roundToInt().coerceIn(0, 255)
                    mergedG[i] = (ng * gain * 255f).roundToInt().coerceIn(0, 255)
                    mergedB[i] = (nb * gain * 255f).roundToInt().coerceIn(0, 255)
                }
            }
        }

        // Spatial bilateral denoise (5x5 in fullPower/lowLightBoost, 3x3 cross otherwise) with strict edge preservation
        val outPixels = IntArray(w * h)
        val radius = if (fullPower || lowLightBoost) 2 else 1
        ParallelCompute.parallelRows(h, fullPower || astroMode) { startY, endY ->
            for (y in startY until endY) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val cr = mergedR[idx]
                    val cg = mergedG[idx]
                    val cb = mergedB[idx]
                    val cLuma = (cr * 77 + cg * 150 + cb * 29) shr 8

                    val rangeThreshold = if (cLuma < 56) 30 else 22

                    var sumR2 = cr * 2.4f
                    var sumG2 = cg * 2.4f
                    var sumB2 = cb * 2.4f
                    var wSum = 2.4f

                    for (ky in -radius..radius) {
                        val ny = (y + ky).coerceIn(0, h - 1)
                        val rowOff = ny * w
                        for (kx in -radius..radius) {
                            if (kx == 0 && ky == 0) continue
                            if (radius == 2 && abs(kx) + abs(ky) > 3) continue
                            val nx = (x + kx).coerceIn(0, w - 1)
                            val nIdx = rowOff + nx
                            val nr = mergedR[nIdx]
                            val ng = mergedG[nIdx]
                            val nb = mergedB[nIdx]
                            val nLuma = (nr * 77 + ng * 150 + nb * 29) shr 8
                            val diff = abs(nLuma - cLuma)
                            if (diff < rangeThreshold) {
                                val distW = if (abs(kx) + abs(ky) <= 1) 1.0f else 0.55f
                                val rangeW = 1f - (diff / rangeThreshold.toFloat())
                                val nw = distW * rangeW
                                sumR2 += nr * nw
                                sumG2 += ng * nw
                                sumB2 += nb * nw
                                wSum += nw
                            }
                        }
                    }

                    val avgR = (sumR2 / wSum).roundToInt()
                    val avgG = (sumG2 / wSum).roundToInt()
                    val avgB = (sumB2 / wSum).roundToInt()
                    val avgLuma = (avgR * 77 + avgG * 150 + avgB * 29) shr 8

                    // Preserve high-frequency luma details while adopting clean denoised chroma
                    val lumaKeep = if (cLuma < 52) 0.76f else 0.94f
                    val dLuma = ((cLuma - avgLuma) * lumaKeep).roundToInt()
                    val fr = (avgR + dLuma).coerceIn(0, 255)
                    val fg = (avgG + dLuma).coerceIn(0, 255)
                    val fb = (avgB + dLuma).coerceIn(0, 255)
                    outPixels[idx] = (0xFF shl 24) or (fr shl 16) or (fg shl 8) or fb
                }
            }
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(outPixels, 0, w, 0, 0, w, h)

        return NightProcessResult(
            bitmap = outBmp,
            isTripodDetected = alignment.isTripodStatic,
            isUnstableCapture = alignment.isUnstableCapture,
            usedFrames = selectedIndices.size,
            discardedBlurFrames = discarded
        )
    }
}
