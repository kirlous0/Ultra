package com.example.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

data class SuperResResult(
    val bitmap: Bitmap,
    val isTrueSuperResolution: Boolean,
    val scaleFactor: Float,
    val statusReason: String
)

class SuperResolutionProcessor(
    private val alignmentEngine: ImageAlignmentEngine = ImageAlignmentEngine()
) {

    /**
     * Multi-frame Drizzle subpixel super-resolution (1.5x–1.75x bounded within 3600px max dim
     * for 4GB-RAM stability) with graceful multi-frame temporal denoise stack on tripod/static holds.
     */
    fun processSuperResolution(
        frames: List<Bitmap>,
        fullPower: Boolean = false
    ): SuperResResult {
        require(frames.isNotEmpty()) { "SuperRes requires at least 1 frame" }
        val base = frames[0]
        if (frames.size < 2) {
            return SuperResResult(
                bitmap = base.copy(Bitmap.Config.ARGB_8888, false),
                isTrueSuperResolution = false,
                scaleFactor = 1.0f,
                statusReason = "Native resolution preserved (<2 frames)"
            )
        }

        val alignment = alignmentEngine.analyzeAndAlign(frames, fullPower = fullPower)
        val refIdx = alignment.referenceIndex
        val srcW = base.width
        val srcH = base.height

        if (alignment.isUnstableCapture) {
            val ref = frames[refIdx]
            return SuperResResult(
                bitmap = ref.copy(Bitmap.Config.ARGB_8888, false),
                isTrueSuperResolution = false,
                scaleFactor = 1.0f,
                statusReason = "Native resolution preserved (excessive motion blur)"
            )
        }

        // Verify meaningful fractional subpixel micro-shift across non-reference frames
        val distinctFractionalShifts = alignment.shifts.withIndex().count { (idx, shift) ->
            if (idx == refIdx) false
            else {
                val (dx, dy) = shift
                val fx = abs(dx - dx.roundToInt())
                val fy = abs(dy - dy.roundToInt())
                hypot(fx, fy) >= 0.08f
            }
        }

        // On zero-shift static / tripod holds, perform true 1.0x multi-frame temporal fusion instead of dropping frames
        if (distinctFractionalShifts == 0 || frames.size < 3) {
            val fused = mergeTemporalStack(frames, alignment, fullPower)
            return SuperResResult(
                bitmap = fused,
                isTrueSuperResolution = false,
                scaleFactor = 1.0f,
                statusReason = "Multi-frame fused (${frames.size}F)"
            )
        }

        // Choose safe scale capped at 3600px longest side to guarantee zero OOM on mid-range RAM
        val desiredScale = if (fullPower && frames.size >= 5) 1.75f else 1.5f
        val maxSrcDim = maxOf(srcW, srcH).coerceAtLeast(1)
        val maxSafeScale = (3600f / maxSrcDim).coerceIn(1.25f, desiredScale)
        val scale = if (maxSafeScale < 1.30f) 1.35f else maxSafeScale
        val outW = (srcW * scale).roundToInt().coerceAtMost(3600)
        val outH = (srcH * scale).roundToInt().coerceAtMost(3600)

        val accR = FloatArray(outW * outH)
        val accG = FloatArray(outW * outH)
        val accB = FloatArray(outW * outH)
        val accW = FloatArray(outW * outH)

        val srcPixels = IntArray(srcW * srcH)
        val refPixels = IntArray(srcW * srcH)
        frames[refIdx].getPixels(refPixels, 0, srcW, 0, 0, srcW, srcH)

        for (idx in frames.indices) {
            frames[idx].getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
            val (dx, dy) = alignment.shifts[idx]
            val isRef = idx == refIdx

            for (y in 0 until srcH) {
                val outYf = (y + dy) * scale
                val y0 = floor(outYf).toInt()
                val wy = outYf - y0
                if (y0 < 0 || y0 >= outH - 1) continue
                val yOff = y * srcW
                val refMatchY = (y + dy.roundToInt()).coerceIn(0, srcH - 1)
                val refRowOff = refMatchY * srcW

                for (x in 0 until srcW) {
                    val outXf = (x + dx) * scale
                    val x0 = floor(outXf).toInt()
                    val wx = outXf - x0
                    if (x0 < 0 || x0 >= outW - 1) continue

                    val argb = srcPixels[yOff + x]
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF

                    val consistency = if (isRef) {
                        1.15f
                    } else {
                        val refMatchX = (x + dx.roundToInt()).coerceIn(0, srcW - 1)
                        val refLuma = YuvUtils.luma(refPixels[refRowOff + refMatchX])
                        val curLuma = (r * 77 + g * 150 + b * 29) shr 8
                        val diff = (curLuma - refLuma).toFloat()
                        exp(-(diff * diff) / (2f * 26f * 26f)).coerceAtLeast(0.06f)
                    }

                    val w00 = (1f - wx) * (1f - wy) * consistency
                    val w10 = wx * (1f - wy) * consistency
                    val w01 = (1f - wx) * wy * consistency
                    val w11 = wx * wy * consistency

                    val i00 = y0 * outW + x0
                    val i10 = i00 + 1
                    val i01 = i00 + outW
                    val i11 = i01 + 1

                    accR[i00] += r * w00; accG[i00] += g * w00; accB[i00] += b * w00; accW[i00] += w00
                    accR[i10] += r * w10; accG[i10] += g * w10; accB[i10] += b * w10; accW[i10] += w10
                    accR[i01] += r * w01; accG[i01] += g * w01; accB[i01] += b * w01; accW[i01] += w01
                    accR[i11] += r * w11; accG[i11] += g * w11; accB[i11] += b * w11; accW[i11] += w11
                }
            }
        }

        val outPixels = IntArray(outW * outH)
        val invScale = 1f / scale
        ParallelCompute.parallelRows(outH, fullPower) { startY, endY ->
            for (y in startY until endY) {
                val off = y * outW
                val sy = (y * invScale).roundToInt().coerceIn(0, srcH - 1)
                val syOff = sy * srcW
                for (x in 0 until outW) {
                    val i = off + x
                    val wSum = accW[i]
                    if (wSum > 0.08f) {
                        val r = (accR[i] / wSum).roundToInt().coerceIn(0, 255)
                        val g = (accG[i] / wSum).roundToInt().coerceIn(0, 255)
                        val b = (accB[i] / wSum).roundToInt().coerceIn(0, 255)
                        outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        val sx = (x * invScale).roundToInt().coerceIn(0, srcW - 1)
                        outPixels[i] = refPixels[syOff + sx]
                    }
                }
            }
        }

        val outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(outPixels, 0, outW, 0, 0, outW, outH)

        return SuperResResult(
            bitmap = outBmp,
            isTrueSuperResolution = true,
            scaleFactor = scale,
            statusReason = "SR ${"%.2f".format(scale)}x (${frames.size}F)"
        )
    }

    private fun mergeTemporalStack(
        frames: List<Bitmap>,
        alignment: AlignmentResult,
        fullPower: Boolean
    ): Bitmap {
        val refIdx = alignment.referenceIndex
        val w = frames[refIdx].width
        val h = frames[refIdx].height
        val refPx = IntArray(w * h)
        frames[refIdx].getPixels(refPx, 0, w, 0, 0, w, h)

        val accR = FloatArray(w * h)
        val accG = FloatArray(w * h)
        val accB = FloatArray(w * h)
        val accW = FloatArray(w * h)
        val framePx = IntArray(w * h)

        for (idx in frames.indices) {
            frames[idx].getPixels(framePx, 0, w, 0, 0, w, h)
            val (dx, dy) = alignment.shifts[idx]
            val sx = dx.roundToInt()
            val sy = dy.roundToInt()
            val isRef = idx == refIdx

            ParallelCompute.parallelRows(h, fullPower) { startY, endY ->
                for (y in startY until endY) {
                    val srcY = (y - sy).coerceIn(0, h - 1)
                    val outRow = y * w
                    val srcRow = srcY * w
                    for (x in 0 until w) {
                        val srcX = (x - sx).coerceIn(0, w - 1)
                        val outIdx = outRow + x
                        val c = framePx[srcRow + srcX]
                        val r = (c ushr 16) and 0xFF
                        val g = (c ushr 8) and 0xFF
                        val b = c and 0xFF

                        val wMotion = if (isRef) {
                            1.1f
                        } else {
                            val rc = refPx[outIdx]
                            val dr = abs(r - ((rc ushr 16) and 0xFF))
                            val dg = abs(g - ((rc ushr 8) and 0xFF))
                            val db = abs(b - (rc and 0xFF))
                            val d = (dr + dg * 2 + db) * 0.25f
                            exp(-(d * d) / (2f * 24f * 24f)).coerceAtLeast(0.05f)
                        }
                        accR[outIdx] += r * wMotion
                        accG[outIdx] += g * wMotion
                        accB[outIdx] += b * wMotion
                        accW[outIdx] += wMotion
                    }
                }
            }
        }

        val outPx = IntArray(w * h)
        ParallelCompute.parallelRows(h, fullPower) { startY, endY ->
            for (y in startY until endY) {
                val off = y * w
                for (x in 0 until w) {
                    val i = off + x
                    val inv = 1f / accW[i].coerceAtLeast(1e-4f)
                    val r = (accR[i] * inv).roundToInt().coerceIn(0, 255)
                    val g = (accG[i] * inv).roundToInt().coerceIn(0, 255)
                    val b = (accB[i] * inv).roundToInt().coerceIn(0, 255)
                    outPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
    }
}
