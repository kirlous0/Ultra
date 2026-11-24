package com.example.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

data class AlignmentResult(
    val referenceIndex: Int,
    val shifts: List<Pair<Float, Float>>, // (dx, dy) subpixel shift of each frame onto reference
    val sharpnessScores: List<Double>,
    val residualDiffs: List<Float>,
    val isTripodStatic: Boolean,
    val isUnstableCapture: Boolean
)

class ImageAlignmentEngine {

    fun analyzeAndAlign(
        frames: List<Bitmap>,
        fullPower: Boolean = false
    ): AlignmentResult {
        if (frames.isEmpty()) {
            return AlignmentResult(0, emptyList(), emptyList(), emptyList(), false, false)
        }
        if (frames.size == 1) {
            val w = frames[0].width
            val h = frames[0].height
            val px = IntArray(w * h)
            frames[0].getPixels(px, 0, w, 0, 0, w, h)
            val sharp = YuvUtils.computeLaplacianSharpness(px, w, h)
            return AlignmentResult(
                referenceIndex = 0,
                shifts = listOf(0f to 0f),
                sharpnessScores = listOf(sharp),
                residualDiffs = listOf(0f),
                isTripodStatic = true,
                isUnstableCapture = sharp < 6.0
            )
        }

        // Higher-precision proxy alignment when Focus Turbo (100% power) is enabled
        val proxyW = if (fullPower) 384 else 300
        val proxyH = (proxyW.toFloat() * frames[0].height / frames[0].width.coerceAtLeast(1))
            .toInt().coerceIn(140, 480)
        val scaleToFull = frames[0].width.toFloat() / proxyW

        val proxyLumas = ArrayList<IntArray>(frames.size)
        val sharpnessScores = ArrayList<Double>(frames.size)

        for (bmp in frames) {
            val scaled = Bitmap.createScaledBitmap(bmp, proxyW, proxyH, true)
            val px = IntArray(proxyW * proxyH)
            scaled.getPixels(px, 0, proxyW, 0, 0, proxyW, proxyH)
            if (scaled !== bmp) scaled.recycle()

            val lumas = IntArray(proxyW * proxyH)
            ParallelCompute.parallelRows(proxyH, fullPower) { startY, endY ->
                for (y in startY until endY) {
                    val off = y * proxyW
                    for (x in 0 until proxyW) {
                        lumas[off + x] = YuvUtils.luma(px[off + x])
                    }
                }
            }
            proxyLumas.add(lumas)
            sharpnessScores.add(YuvUtils.computeLaplacianSharpness(px, proxyW, proxyH))
        }

        // Pick sharpest frame as reference (prefer middle index on ties for bracketing)
        val maxSharp = sharpnessScores.maxOrNull() ?: 0.0
        val refIdx = sharpnessScores.indices.maxByOrNull { idx ->
            val centerBonus = if (idx == frames.size / 2) 0.05 * maxSharp else 0.0
            sharpnessScores[idx] + centerBonus
        } ?: 0

        val refLuma = proxyLumas[refIdx]
        val shifts = ArrayList<Pair<Float, Float>>(frames.size)
        val residuals = ArrayList<Float>(frames.size)
        var maxShiftPx = 0f

        val searchRadius = if (fullPower) 22 else 18
        for (i in frames.indices) {
            if (i == refIdx) {
                shifts.add(0f to 0f)
                residuals.add(0f)
                continue
            }
            val (dxProxy, dyProxy, sad) = estimateSubpixelShift(
                ref = refLuma,
                target = proxyLumas[i],
                w = proxyW,
                h = proxyH,
                searchRadius = searchRadius
            )
            val dxFull = dxProxy * scaleToFull
            val dyFull = dyProxy * scaleToFull
            shifts.add(dxFull to dyFull)
            residuals.add(sad)
            val dist = hypot(dxFull, dyFull)
            if (dist > maxShiftPx) maxShiftPx = dist
        }

        val isTripod = maxShiftPx < 1.6f
        val avgResidual = residuals.filterIndexed { idx, _ -> idx != refIdx }.average().toFloat()
        val isUnstable = maxShiftPx > frames[0].width * 0.075f || avgResidual > 42f || maxSharp < 2.8

        return AlignmentResult(
            referenceIndex = refIdx,
            shifts = shifts,
            sharpnessScores = sharpnessScores,
            residualDiffs = residuals,
            isTripodStatic = isTripod,
            isUnstableCapture = isUnstable
        )
    }

    /**
     * Estimates subpixel translation (dx, dy) such that target(x - dx, y - dy) ~= ref(x, y)
     * using normalized SAD over central 70% ROI + parabolic subpixel interpolation.
     */
    private fun estimateSubpixelShift(
        ref: IntArray,
        target: IntArray,
        w: Int,
        h: Int,
        searchRadius: Int = 16
    ): Triple<Float, Float, Float> {
        val marginX = (w * 0.15f).roundToInt().coerceAtLeast(searchRadius + 2)
        val marginY = (h * 0.15f).roundToInt().coerceAtLeast(searchRadius + 2)

        val gridW = searchRadius * 2 + 1
        val sadGrid = FloatArray(gridW * gridW) { Float.MAX_VALUE }

        var bestDx = 0
        var bestDy = 0
        var bestSad = Float.MAX_VALUE

        // Coarse step 2 search first for speed
        for (dy in -searchRadius..searchRadius step 2) {
            for (dx in -searchRadius..searchRadius step 2) {
                val sad = computeRoiSad(ref, target, w, h, marginX, marginY, dx, dy, step = 2)
                sadGrid[(dy + searchRadius) * gridW + (dx + searchRadius)] = sad
                if (sad < bestSad) {
                    bestSad = sad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        // Fine 1px refinement around best coarse peak
        var fineBestDx = bestDx
        var fineBestDy = bestDy
        var fineBestSad = Float.MAX_VALUE
        for (dy in (bestDy - 2)..(bestDy + 2)) {
            if (dy < -searchRadius || dy > searchRadius) continue
            for (dx in (bestDx - 2)..(bestDx + 2)) {
                if (dx < -searchRadius || dx > searchRadius) continue
                val sad = computeRoiSad(ref, target, w, h, marginX, marginY, dx, dy, step = 1)
                sadGrid[(dy + searchRadius) * gridW + (dx + searchRadius)] = sad
                if (sad < fineBestSad) {
                    fineBestSad = sad
                    fineBestDx = dx
                    fineBestDy = dy
                }
            }
        }

        // Parabolic subpixel refinement along X and Y
        val subDx = refineParabola(
            sadGrid.getOrNull((fineBestDy + searchRadius) * gridW + (fineBestDx - 1 + searchRadius)),
            fineBestSad,
            sadGrid.getOrNull((fineBestDy + searchRadius) * gridW + (fineBestDx + 1 + searchRadius))
        )
        val subDy = refineParabola(
            sadGrid.getOrNull((fineBestDy - 1 + searchRadius) * gridW + (fineBestDx + searchRadius)),
            fineBestSad,
            sadGrid.getOrNull((fineBestDy + 1 + searchRadius) * gridW + (fineBestDx + searchRadius))
        )

        return Triple(fineBestDx + subDx, fineBestDy + subDy, fineBestSad)
    }

    private fun computeRoiSad(
        ref: IntArray,
        target: IntArray,
        w: Int,
        h: Int,
        marginX: Int,
        marginY: Int,
        dx: Int,
        dy: Int,
        step: Int
    ): Float {
        var sum = 0L
        var count = 0
        var refMean = 0L
        var tgtMean = 0L
        var y = marginY
        while (y < h - marginY) {
            var x = marginX
            val refRow = y * w
            val tgtRow = (y - dy) * w
            while (x < w - marginX) {
                refMean += ref[refRow + x]
                tgtMean += target[tgtRow + (x - dx)]
                count++
                x += step * 2
            }
            y += step * 2
        }
        if (count == 0) return Float.MAX_VALUE
        val bias = ((tgtMean - refMean) / count).toInt()

        var sampCount = 0
        y = marginY
        while (y < h - marginY) {
            var x = marginX
            val refRow = y * w
            val tgtRow = (y - dy) * w
            while (x < w - marginX) {
                val diff = abs(ref[refRow + x] - (target[tgtRow + (x - dx)] - bias))
                sum += diff
                sampCount++
                x += step
            }
            y += step
        }
        return if (sampCount > 0) sum.toFloat() / sampCount else Float.MAX_VALUE
    }

    private fun refineParabola(left: Float?, center: Float, right: Float?): Float {
        if (left == null || right == null || left == Float.MAX_VALUE || right == Float.MAX_VALUE) return 0f
        val denom = 2f * (left - 2f * center + right)
        if (abs(denom) < 1e-4f) return 0f
        return ((left - right) / denom).coerceIn(-0.5f, 0.5f)
    }

    private fun FloatArray.getOrNull(index: Int): Float? =
        if (index in indices) this[index] else null
}
