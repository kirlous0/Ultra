package com.example.processing

import android.graphics.Bitmap
import kotlin.math.abs
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
     * Performs multi-frame subpixel shift-and-add (1.5x Drizzle) ONLY when burst frames
     * exhibit genuine subpixel micro-shift diversity and sufficient sharpness.
     * Never pretends fake upscaling is native resolution.
     */
    fun processSuperResolution(
        frames: List<Bitmap>,
        fullPower: Boolean = false
    ): SuperResResult {
        require(frames.isNotEmpty()) { "SuperRes requires at least 1 frame" }
        val base = frames[0]
        if (frames.size < 3) {
            return SuperResResult(
                bitmap = base.copy(Bitmap.Config.ARGB_8888, false),
                isTrueSuperResolution = false,
                scaleFactor = 1.0f,
                statusReason = "Native resolution preserved (<3 frames)"
            )
        }

        val alignment = alignmentEngine.analyzeAndAlign(frames, fullPower = fullPower)
        if (alignment.isUnstableCapture) {
            val ref = frames[alignment.referenceIndex]
            return SuperResResult(
                bitmap = ref.copy(Bitmap.Config.ARGB_8888, false),
                isTrueSuperResolution = false,
                scaleFactor = 1.0f,
                statusReason = "Native resolution preserved (excessive motion blur)"
            )
        }

        // Verify genuine fractional subpixel shift diversity across frames
        val distinctFractionalShifts = alignment.shifts.count { (dx, dy) ->
            val fx = abs(dx - dx.roundToInt())
            val fy = abs(dy - dy.roundToInt())
            hypot(fx, fy) >= 0.14f
        }

        if (distinctFractionalShifts < 2) {
            val ref = frames[alignment.referenceIndex]
            return SuperResResult(
                bitmap = ref.copy(Bitmap.Config.ARGB_8888, false),
                isTrueSuperResolution = false,
                scaleFactor = 1.0f,
                statusReason = "Native resolution preserved (static tripod / insufficient subpixel shift)"
            )
        }

        val srcW = base.width
        val srcH = base.height
        val scale = if (fullPower && frames.size >= 5 && srcW <= 2600) 1.75f else 1.5f
        val outW = (srcW * scale).roundToInt()
        val outH = (srcH * scale).roundToInt()

        val accR = FloatArray(outW * outH)
        val accG = FloatArray(outW * outH)
        val accB = FloatArray(outW * outH)
        val accW = FloatArray(outW * outH)

        val srcPixels = IntArray(srcW * srcH)
        val refPixels = IntArray(srcW * srcH)
        val refIdx = alignment.referenceIndex
        frames[refIdx].getPixels(refPixels, 0, srcW, 0, 0, srcW, srcH)

        for (idx in frames.indices) {
            frames[idx].getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
            val (dx, dy) = alignment.shifts[idx]

            for (y in 0 until srcH) {
                val outYf = (y + dy) * scale
                val y0 = floor(outYf).toInt()
                val wy = outYf - y0
                if (y0 < 0 || y0 >= outH - 1) continue

                for (x in 0 until srcW) {
                    val outXf = (x + dx) * scale
                    val x0 = floor(outXf).toInt()
                    val wx = outXf - x0
                    if (x0 < 0 || x0 >= outW - 1) continue

                    val argb = srcPixels[y * srcW + x]
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF

                    val refMatchX = (x + dx.roundToInt()).coerceIn(0, srcW - 1)
                    val refMatchY = (y + dy.roundToInt()).coerceIn(0, srcH - 1)
                    val refLuma = YuvUtils.luma(refPixels[refMatchY * srcW + refMatchX])
                    val curLuma = (r * 77 + g * 150 + b * 29) shr 8
                    val consistency = if (abs(curLuma - refLuma) > 36) 0.1f else 1.0f

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

        val scaledRef = Bitmap.createScaledBitmap(frames[refIdx], outW, outH, true)
        val scaledRefPx = IntArray(outW * outH)
        scaledRef.getPixels(scaledRefPx, 0, outW, 0, 0, outW, outH)
        scaledRef.recycle()

        val outPixels = IntArray(outW * outH)
        ParallelCompute.parallelRows(outH, fullPower) { startY, endY ->
            for (y in startY until endY) {
                val off = y * outW
                for (x in 0 until outW) {
                    val i = off + x
                    val wSum = accW[i]
                    if (wSum > 0.05f) {
                        val r = (accR[i] / wSum).roundToInt().coerceIn(0, 255)
                        val g = (accG[i] / wSum).roundToInt().coerceIn(0, 255)
                        val b = (accB[i] / wSum).roundToInt().coerceIn(0, 255)
                        outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        outPixels[i] = scaledRefPx[i]
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
            statusReason = "${scale}x multi-frame subpixel reconstruction (${frames.size} frames)"
        )
    }
}
