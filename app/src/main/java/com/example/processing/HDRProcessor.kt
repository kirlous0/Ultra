package com.example.processing

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

data class HdrProcessResult(
    val bitmap: Bitmap,
    val isUnstableCapture: Boolean,
    val alignedFrameCount: Int,
    val ghostRejectedPercent: Float
)

class HDRProcessor(
    private val alignmentEngine: ImageAlignmentEngine = ImageAlignmentEngine()
) {

    /**
     * Multi-frame Mertens-style exposure fusion with multi-core parallel alignment,
     * ghost rejection, luminance-preserving highlight/shadow tone mapping, and natural vibrance.
     */
    fun processHdr(
        frames: List<Bitmap>,
        fullPower: Boolean = false,
        vibranceBoost: Float = 0.16f,
        toneStyle: HdrToneStyle = HdrToneStyle.PIXEL_PUNCH,
        shadowBias: Float = 0f,
        highlightBias: Float = 0f,
        enableGhostRejection: Boolean = true
    ): HdrProcessResult {
        require(frames.isNotEmpty()) { "HDR requires at least 1 frame" }
        val ref0 = frames[0]
        val w = ref0.width
        val h = ref0.height

        if (frames.size == 1) {
            val out = singleFrameToneBalance(ref0, fullPower, vibranceBoost, toneStyle, shadowBias, highlightBias)
            return HdrProcessResult(out, isUnstableCapture = false, alignedFrameCount = 1, ghostRejectedPercent = 0f)
        }

        val alignment = alignmentEngine.analyzeAndAlign(frames, fullPower = fullPower)
        val refIdx = alignment.referenceIndex

        val accumR = FloatArray(w * h)
        val accumG = FloatArray(w * h)
        val accumB = FloatArray(w * h)
        val accumW = FloatArray(w * h)

        val refPixels = IntArray(w * h)
        frames[refIdx].getPixels(refPixels, 0, w, 0, 0, w, h)

        val framePixels = IntArray(w * h)
        val totalGhostRejected = AtomicLong(0L)
        val totalSamples = AtomicLong(0L)

        // Gaussian well-exposedness lookup (sigma = 0.25 around 0.50)
        val expLut = FloatArray(256) { v ->
            val n = v / 255f - 0.50f
            exp(-(n * n) / (2f * 0.25f * 0.25f)) + 0.025f
        }

        for (fIdx in frames.indices) {
            frames[fIdx].getPixels(framePixels, 0, w, 0, 0, w, h)
            val (dx, dy) = alignment.shifts[fIdx]
            val shiftX = dx.roundToInt()
            val shiftY = dy.roundToInt()

            ParallelCompute.parallelRows(h, fullPower) { startY, endY ->
                var localGhost = 0L
                var localSamples = 0L
                for (y in startY until endY) {
                    val srcY = (y - shiftY).coerceIn(0, h - 1)
                    val rowOff = y * w
                    val srcRowOff = srcY * w
                    val srcNextRowOff = ((srcY + 1).coerceAtMost(h - 1)) * w
                    for (x in 0 until w) {
                        val srcX = (x - shiftX).coerceIn(0, w - 1)
                        val outIdx = rowOff + x
                        val argb = framePixels[srcRowOff + srcX]

                        val r = (argb ushr 16) and 0xFF
                        val g = (argb ushr 8) and 0xFF
                        val b = argb and 0xFF
                        val luma = (r * 77 + g * 150 + b * 29) shr 8

                        val wExp = expLut[luma]
                        val maxC = maxOf(r, g, b)
                        val minC = minOf(r, g, b)
                        val wSat = 0.35f + 0.65f * ((maxC - minC) / 255f)

                        val rightPx = framePixels[srcRowOff + (srcX + 1).coerceAtMost(w - 1)]
                        val downPx = framePixels[srcNextRowOff + srcX]
                        val grad = (abs(YuvUtils.luma(rightPx) - luma) + abs(YuvUtils.luma(downPx) - luma)) / 255f
                        val wContrast = 0.25f + grad

                        var weight = wExp * wSat * wContrast

                        if (enableGhostRejection && fIdx != refIdx) {
                            val refArgb = refPixels[outIdx]
                            val refLuma = YuvUtils.luma(refArgb)
                            val lumaDiff = abs(luma - refLuma)
                            val refR = (refArgb ushr 16) and 0xFF
                            val refB = refArgb and 0xFF
                            val chromaDiff = abs((r - b) - (refR - refB))
                            if (lumaDiff > 95 && chromaDiff > 42) {
                                weight *= 0.04f
                                localGhost++
                            } else if (lumaDiff > 70 && chromaDiff > 28) {
                                weight *= 0.25f
                            }
                            localSamples++
                        } else if (fIdx == refIdx) {
                            weight = weight.coerceAtLeast(0.08f)
                        }

                        accumR[outIdx] += r * weight
                        accumG[outIdx] += g * weight
                        accumB[outIdx] += b * weight
                        accumW[outIdx] += weight
                    }
                }
                if (localSamples > 0) {
                    totalGhostRejected.addAndGet(localGhost)
                    totalSamples.addAndGet(localSamples)
                }
            }
        }

        // Normalize & apply luminance-preserving filmlike tone curve + vibrance
        val outPixels = IntArray(w * h)
        ParallelCompute.parallelRows(h, fullPower) { startY, endY ->
            for (y in startY until endY) {
                val rowOff = y * w
                for (x in 0 until w) {
                    val i = rowOff + x
                    val invW = 1f / accumW[i].coerceAtLeast(1e-4f)
                    val r = ((accumR[i] * invW) / 255f).coerceIn(0f, 1f)
                    val g = ((accumG[i] * invW) / 255f).coerceIn(0f, 1f)
                    val b = ((accumB[i] * invW) / 255f).coerceIn(0f, 1f)

                    outPixels[i] = toneMapAndVibrance(r, g, b, vibranceBoost, toneStyle, shadowBias, highlightBias)
                }
            }
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(outPixels, 0, w, 0, 0, w, h)
        val totalSamp = totalSamples.get()
        val ghostPct = if (totalSamp > 0) (totalGhostRejected.get() * 100f) / totalSamp else 0f

        return HdrProcessResult(
            bitmap = outBmp,
            isUnstableCapture = alignment.isUnstableCapture,
            alignedFrameCount = frames.size,
            ghostRejectedPercent = ghostPct
        )
    }

    private fun singleFrameToneBalance(
        src: Bitmap,
        fullPower: Boolean,
        vibranceBoost: Float,
        toneStyle: HdrToneStyle,
        shadowBias: Float,
        highlightBias: Float
    ): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        ParallelCompute.parallelRows(h, fullPower) { startY, endY ->
            for (y in startY until endY) {
                val off = y * w
                for (x in 0 until w) {
                    val i = off + x
                    val c = px[i]
                    px[i] = toneMapAndVibrance(
                        r = ((c ushr 16) and 0xFF) / 255f,
                        g = ((c ushr 8) and 0xFF) / 255f,
                        b = (c and 0xFF) / 255f,
                        vibranceBoost = vibranceBoost,
                        toneStyle = toneStyle,
                        shadowBias = shadowBias,
                        highlightBias = highlightBias
                    )
                }
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun toneMapAndVibrance(
        r: Float,
        g: Float,
        b: Float,
        vibranceBoost: Float,
        toneStyle: HdrToneStyle,
        shadowBias: Float,
        highlightBias: Float
    ): Int {
        val luma = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
        val mappedLuma = naturalToneMap(luma, toneStyle, shadowBias, highlightBias)
        val lumaGain = if (luma > 0.004f) (mappedLuma / luma).coerceIn(0.6f, 2.35f) else 1f

        var tr = (r * lumaGain).coerceIn(0f, 1f)
        var tg = (g * lumaGain).coerceIn(0f, 1f)
        var tb = (b * lumaGain).coerceIn(0f, 1f)

        val newLuma = (0.299f * tr + 0.587f * tg + 0.114f * tb).coerceIn(0f, 1f)
        val sat = maxOf(tr, tg, tb) - minOf(tr, tg, tb)
        val highGuard = if (newLuma > 0.85f) (1f - (newLuma - 0.85f) / 0.15f).coerceIn(0.2f, 1f) else 1f
        val satMult = 1f + vibranceBoost.coerceIn(0f, 0.5f) * (1f - sat) * highGuard

        tr = (newLuma + (tr - newLuma) * satMult).coerceIn(0f, 1f)
        tg = (newLuma + (tg - newLuma) * satMult).coerceIn(0f, 1f)
        tb = (newLuma + (tb - newLuma) * satMult).coerceIn(0f, 1f)

        return (0xFF shl 24) or
            ((tr * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((tg * 255f).roundToInt().coerceIn(0, 255) shl 8) or
            (tb * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun naturalToneMap(
        v: Float,
        toneStyle: HdrToneStyle,
        shadowBias: Float,
        highlightBias: Float
    ): Float {
        val clamped = v.coerceIn(0f, 1f)
        val gamma = when (toneStyle) {
            HdrToneStyle.FILMIC_BALANCED -> 0.92f
            HdrToneStyle.PIXEL_PUNCH -> 0.87f
            HdrToneStyle.SOFT_CINEMA -> 0.94f
            HdrToneStyle.HIGH_DR -> 0.83f
        }
        val rolloff = when (toneStyle) {
            HdrToneStyle.FILMIC_BALANCED -> 0.16f
            HdrToneStyle.PIXEL_PUNCH -> 0.22f
            HdrToneStyle.SOFT_CINEMA -> 0.14f
            HdrToneStyle.HIGH_DR -> 0.26f
        }
        val lifted = clamped.pow(gamma)
        val base = lifted * (1f + rolloff * (1f - lifted))
        val shShift = ((1f - clamped) * (1f - clamped)) * shadowBias.coerceIn(-0.4f, 0.5f) * 0.24f
        val hiShift = (clamped * clamped) * highlightBias.coerceIn(-0.5f, 0.4f) * 0.24f
        return (base + shShift + hiShift).coerceIn(0f, 1f)
    }
}
