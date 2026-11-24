package com.example.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@Immutable
data class EnhanceConfig(
    val sharpnessAmount: Float = 0.35f,
    val clarityAmount: Float = 0.22f,
    val lumaDenoiseStrength: Float = 0.18f,
    val chromaDenoiseStrength: Float = 0.30f,
    val vibranceBoost: Float = 0.14f,
    val shadowLift: Float = 0.08f,
    val highlightCompress: Float = 0.10f,
    val correctChromaticAberration: Boolean = true,
    val correctLensShading: Boolean = true,
    val fullPower: Boolean = false,
    val libPatcher: LibPatcherConfig? = null
)

class DetailEngine {

    /**
     * Adaptive two-pass edge-aware computational finishing pipeline:
     * Pass 1: Lateral chromatic aberration correction + radial lens shading recovery + edge-preserving luma/chroma denoise.
     * Pass 2: Two-scale local clarity + halo-clamped sharpening with skin-tone guard + fused pro color & tone grading.
     */
    fun enhanceDetails(
        src: Bitmap,
        sharpnessAmount: Float = 0.35f,
        correctChromaticAberration: Boolean = true,
        correctLensShading: Boolean = true
    ): Bitmap = enhanceDetails(
        src = src,
        config = EnhanceConfig(
            sharpnessAmount = sharpnessAmount,
            correctChromaticAberration = correctChromaticAberration,
            correctLensShading = correctLensShading
        )
    )

    fun enhanceDetails(
        src: Bitmap,
        config: EnhanceConfig
    ): Bitmap {
        val w = src.width
        val h = src.height
        val inPx = IntArray(w * h)
        src.getPixels(inPx, 0, w, 0, 0, w, h)
        val stagePx = IntArray(w * h)
        val outPx = IntArray(w * h)

        // Quick strided scan to classify global scene illumination (Good Light vs Dim/Low Light)
        var scanSum = 0L
        var scanCount = 0L
        val stepScan = (minOf(w, h) / 64).coerceAtLeast(4)
        var syScan = 0
        while (syScan < h) {
            val row = syScan * w
            var sxScan = 0
            while (sxScan < w) {
                scanSum += YuvUtils.luma(inPx[row + sxScan])
                scanCount++
                sxScan += stepScan
            }
            syScan += stepScan
        }
        val frameMeanLuma = scanSum.toFloat() / scanCount.coerceAtLeast(1L)
        val isLowLightScene = frameMeanLuma < 86f
        val isBrightDaylight = frameMeanLuma >= 102f

        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxR = hypot(cx, cy).coerceAtLeast(1f)
        val invMaxR = 1f / maxR
        val baseChromaBlend = config.chromaDenoiseStrength.coerceIn(0f, 0.85f)
        val baseLumaBlend = config.lumaDenoiseStrength.coerceIn(0f, 0.75f)
        val patch = config.libPatcher

        // In bright good light, lighten chroma/luma denoise so leaves, hair, and fine textures remain ultra-crisp;
        // in low/bad light, engage strong opponent YCbCr chroma smoothing while preserving crisp luma edges.
        val sceneChromaGain = when {
            isLowLightScene -> 1.28f
            isBrightDaylight -> 0.58f
            else -> 0.92f
        }
        val sceneLumaGain = when {
            isLowLightScene -> 1.15f
            isBrightDaylight -> 0.42f
            else -> 0.82f
        }
        val effectiveChromaW = (baseChromaBlend * sceneChromaGain).coerceIn(0f, 0.88f)
        val effectiveLumaW = (baseLumaBlend * sceneLumaGain).coerceIn(0f, 0.75f)

        // Pass 1: Lateral chromatic aberration + radial lens shading + opponent YCbCr edge-preserving denoise
        ParallelCompute.parallelRows(h, config.fullPower) { startY, endY ->
            for (y in startY until endY) {
                val yM = (y - 1).coerceAtLeast(0)
                val yP = (y + 1).coerceAtMost(h - 1)
                val dy = y - cy
                val rowOff = y * w

                for (x in 0 until w) {
                    val xM = (x - 1).coerceAtLeast(0)
                    val xP = (x + 1).coerceAtMost(w - 1)
                    val idx = rowOff + x
                    val c = inPx[idx]

                    var r = (c ushr 16) and 0xFF
                    var g = (c ushr 8) and 0xFF
                    var b = c and 0xFF

                    val rNorm = hypot(x - cx, dy) * invMaxR

                    if (config.correctChromaticAberration && rNorm > 0.32f) {
                        val shiftFrac = (rNorm - 0.32f) * 0.90f
                        val rx = (x - ((x - cx) * invMaxR) * shiftFrac).roundToInt().coerceIn(0, w - 1)
                        val ry = (y - (dy * invMaxR) * shiftFrac).roundToInt().coerceIn(0, h - 1)
                        val bx = (x + ((x - cx) * invMaxR) * shiftFrac).roundToInt().coerceIn(0, w - 1)
                        val by = (y + (dy * invMaxR) * shiftFrac).roundToInt().coerceIn(0, h - 1)
                        r = (inPx[ry * w + rx] ushr 16) and 0xFF
                        b = inPx[by * w + bx] and 0xFF
                    }

                    if (config.correctLensShading && rNorm > 0.22f) {
                        val gain = 1f + 0.15f * (rNorm * rNorm)
                        r = (r * gain).roundToInt().coerceIn(0, 255)
                        g = (g * gain).roundToInt().coerceIn(0, 255)
                        b = (b * gain).roundToInt().coerceIn(0, 255)
                    }

                    // Edge-preserving opponent YCbCr chroma + texture-aware luma denoise
                    if (effectiveChromaW > 0.02f || effectiveLumaW > 0.02f) {
                        val cLuma = (r * 77 + g * 150 + b * 29) shr 8
                        val cCb = b - cLuma
                        val cCr = r - cLuma

                        // Measure local detail contrast; attenuate denoise on well-lit fine textures
                        val n0 = inPx[yM * w + x]
                        val n1 = inPx[yP * w + x]
                        val n2 = inPx[rowOff + xM]
                        val n3 = inPx[rowOff + xP]
                        val l0 = YuvUtils.luma(n0)
                        val l1 = YuvUtils.luma(n1)
                        val l2 = YuvUtils.luma(n2)
                        val l3 = YuvUtils.luma(n3)
                        val localContrast = maxOf(abs(l0 - cLuma), abs(l1 - cLuma), abs(l2 - cLuma), abs(l3 - cLuma))

                        // Skip Pass 1 smoothing completely on crisp well-lit detail edges
                        if (!isBrightDaylight || cLuma < 85 || localContrast < 18) {
                            val lumaThresh = when {
                                cLuma < 52 -> 28
                                isLowLightScene -> 24
                                else -> 16
                            }

                            var sumY = cLuma * 2f
                            var sumCb = cCb * 1.6f
                            var sumCr = cCr * 1.6f
                            var yW = 2f
                            var cW = 1.6f

                            val ns = intArrayOf(n0, n1, n2, n3)
                            val ls = intArrayOf(l0, l1, l2, l3)
                            for (k in 0..3) {
                                val diff = abs(ls[k] - cLuma)
                                if (diff < lumaThresh) {
                                    val wY = 1f - diff / lumaThresh.toFloat()
                                    val nr = (ns[k] ushr 16) and 0xFF
                                    val nb = ns[k] and 0xFF
                                    val nL = ls[k]
                                    sumY += nL * wY
                                    yW += wY
                                    sumCb += (nb - nL)
                                    sumCr += (nr - nL)
                                    cW += 1f
                                }
                            }

                            // Include 4 diagonal neighbors in dim shadows or Turbo mode for clean blotch-free night backgrounds
                            if ((isLowLightScene && cLuma < 90) || (config.fullPower && cLuma < 64)) {
                                val diag = intArrayOf(
                                    inPx[yM * w + xM],
                                    inPx[yM * w + xP],
                                    inPx[yP * w + xM],
                                    inPx[yP * w + xP]
                                )
                                for (dPx in diag) {
                                    val dl = YuvUtils.luma(dPx)
                                    val diff = abs(dl - cLuma)
                                    if (diff < lumaThresh + 4) {
                                        val nr = (dPx ushr 16) and 0xFF
                                        val nb = dPx and 0xFF
                                        sumY += dl * 0.65f
                                        yW += 0.65f
                                        sumCb += (nb - dl) * 0.8f
                                        sumCr += (nr - dl) * 0.8f
                                        cW += 0.8f
                                    }
                                }
                            }

                            if (cW > 1.7f) {
                                val avgY = sumY / yW
                                val avgCb = sumCb / cW
                                val avgCr = sumCr / cW

                                val shadowBoost = if (cLuma < 64) 1.0f else if (isBrightDaylight) 0.35f else 0.65f
                                val texGuard = if (localContrast > 20 && !isLowLightScene) 0.35f else 1.0f
                                val yMix = (effectiveLumaW * shadowBoost * texGuard * 0.55f).coerceIn(0f, 0.58f)
                                val chMix = (effectiveChromaW * (if (cLuma < 72) 1.05f else 0.78f) * texGuard).coerceIn(0f, 0.85f)

                                val outY = cLuma * (1f - yMix) + avgY * yMix
                                val outCb = cCb * (1f - chMix) + avgCb * chMix
                                val outCr = cCr * (1f - chMix) + avgCr * chMix

                                // Exact inverse of Y = 0.299R + 0.587G + 0.114B with Cr=R-Y, Cb=B-Y
                                val fr = (outY + outCr).roundToInt().coerceIn(0, 255)
                                val fb = (outY + outCb).roundToInt().coerceIn(0, 255)
                                val fg = (outY - 0.5093f * outCr - 0.1942f * outCb).roundToInt().coerceIn(0, 255)
                                r = fr
                                g = fg
                                b = fb
                            }
                        }
                    }

                    stagePx[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        // Pass 2: Adaptive Two-Scale Clarity + Halo-Clamped Micro-Detail + Filmic Dynamic Range + Fused Color Grading
        val sharpW = (config.sharpnessAmount + if (isBrightDaylight) 0.04f else 0f).coerceIn(0f, 1.25f)
        val clarityW = (config.clarityAmount + if (isBrightDaylight) 0.04f else 0f).coerceIn(0f, 0.85f)
        val vibW = config.vibranceBoost.coerceIn(0f, 0.65f)
        val shadowLift = (config.shadowLift + if (isLowLightScene) 0.04f else 0f).coerceIn(0f, 0.45f)
        val highlightComp = (config.highlightCompress + if (isBrightDaylight) 0.03f else 0f).coerceIn(0f, 0.45f)
        val protectSkin = patch?.skinToneProtect ?: true

        ParallelCompute.parallelRows(h, config.fullPower) { startY, endY ->
            for (y in startY until endY) {
                val yM1 = (y - 1).coerceAtLeast(0)
                val yP1 = (y + 1).coerceAtMost(h - 1)
                val yM2 = (y - 2).coerceAtLeast(0)
                val yP2 = (y + 2).coerceAtMost(h - 1)
                val dy = y - cy
                val rowOff = y * w

                for (x in 0 until w) {
                    val xM1 = (x - 1).coerceAtLeast(0)
                    val xP1 = (x + 1).coerceAtMost(w - 1)
                    val xM2 = (x - 2).coerceAtLeast(0)
                    val xP2 = (x + 2).coerceAtMost(w - 1)
                    val idx = rowOff + x

                    val c = stagePx[idx]
                    val r0 = (c ushr 16) and 0xFF
                    val g0 = (c ushr 8) and 0xFF
                    val b0 = c and 0xFF
                    val lumaC = (r0 * 77 + g0 * 150 + b0 * 29) shr 8

                    val fineMean = (
                        YuvUtils.luma(stagePx[yM1 * w + x]) +
                            YuvUtils.luma(stagePx[yP1 * w + x]) +
                            YuvUtils.luma(stagePx[rowOff + xM1]) +
                            YuvUtils.luma(stagePx[rowOff + xP1])
                        ) shr 2

                    val clarityMean = if (clarityW > 0.01f) {
                        (
                            YuvUtils.luma(stagePx[yM2 * w + xM2]) +
                                YuvUtils.luma(stagePx[yM2 * w + x]) +
                                YuvUtils.luma(stagePx[yM2 * w + xP2]) +
                                YuvUtils.luma(stagePx[rowOff + xM2]) +
                                YuvUtils.luma(stagePx[rowOff + xP2]) +
                                YuvUtils.luma(stagePx[yP2 * w + xM2]) +
                                YuvUtils.luma(stagePx[yP2 * w + x]) +
                                YuvUtils.luma(stagePx[yP2 * w + xP2])
                            ) shr 3
                    } else fineMean

                    val fineDetail = lumaC - fineMean
                    val clarityDetail = lumaC - clarityMean

                    val isSkin = protectSkin && r0 > 95 && g0 > 40 && b0 > 20 &&
                        (r0 - g0) > 12 && r0 > b0 && abs(fineDetail) < 28

                    val shadowSharpGuard = when {
                        lumaC < 38 -> 0.45f
                        lumaC < 64 && isLowLightScene -> 0.65f
                        else -> 1.0f
                    }
                    val baseGate = if (isBrightDaylight) 2 else if (isLowLightScene) 5 else 3
                    val noiseGate = (baseGate + (effectiveLumaW * 8f).roundToInt()).coerceIn(2, 11)
                    val localSharp = (if (isSkin) sharpW * 0.25f else sharpW) * shadowSharpGuard
                    val localClarity = (if (isSkin) clarityW * 0.20f else clarityW) * (if (lumaC < 36) 0.70f else 1.0f)

                    val fineDelta = if (abs(fineDetail) > noiseGate) {
                        val excess = if (fineDetail > 0) fineDetail - noiseGate else fineDetail + noiseGate
                        (excess * localSharp).roundToInt().coerceIn(-28, 28)
                    } else 0
                    val clarityDelta = if (abs(clarityDetail) > (noiseGate + 2)) {
                        (clarityDetail * localClarity).roundToInt().coerceIn(-20, 20)
                    } else 0

                    // Dynamic-range gentle tone shaping (shadow recovery + highlight protection)
                    val normL = lumaC / 255f
                    val shadowBoost = ((1f - normL) * (1f - normL)) * shadowLift * 36f
                    val highPull = if (normL > 0.68f) {
                        ((normL - 0.68f) / 0.32f) * highlightComp * 32f
                    } else 0f

                    val totalDelta = fineDelta + clarityDelta + (shadowBoost - highPull).roundToInt()

                    var fr = (r0 + totalDelta).coerceIn(0, 255)
                    var fg = (g0 + totalDelta).coerceIn(0, 255)
                    var fb = (b0 + totalDelta).coerceIn(0, 255)

                    if (patch != null) {
                        val rNorm = hypot(x - cx, dy) * invMaxR
                        outPx[idx] = ColorProfileEngine.gradePixel(
                            rIn = fr / 255f,
                            gIn = fg / 255f,
                            bIn = fb / 255f,
                            normRadius = rNorm,
                            patch = patch,
                            isSkin = isSkin
                        )
                    } else {
                        if (vibW > 0.01f) {
                            val maxC = maxOf(fr, fg, fb)
                            val minC = minOf(fr, fg, fb)
                            val satRatio = (maxC - minC) / 255f
                            val newL = (fr * 77 + fg * 150 + fb * 29) shr 8
                            val highGuard = (1f - ((newL - 210).coerceAtLeast(0) / 45f)).coerceIn(0.25f, 1f)
                            val skinGuard = if (isSkin) 0.25f else 1f
                            val vibFactor = 1f + vibW * (1f - satRatio) * highGuard * skinGuard
                            fr = (newL + (fr - newL) * vibFactor).roundToInt().coerceIn(0, 255)
                            fg = (newL + (fg - newL) * vibFactor).roundToInt().coerceIn(0, 255)
                            fb = (newL + (fb - newL) * vibFactor).roundToInt().coerceIn(0, 255)
                        }
                        outPx[idx] = (0xFF shl 24) or (fr shl 16) or (fg shl 8) or fb
                    }
                }
            }
        }
        return Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
    }
}

data class MotionAssessment(
    val sharpnessScore: Double,
    val isUnstableOrBlurred: Boolean,
    val processedBitmap: Bitmap
)

class MotionEngine {

    fun assessAndStabilize(bitmap: Bitmap): MotionAssessment {
        val w = bitmap.width
        val h = bitmap.height
        val proxy = Bitmap.createScaledBitmap(bitmap, 320, (320f * h / w.coerceAtLeast(1)).toInt().coerceAtLeast(180), true)
        val px = IntArray(proxy.width * proxy.height)
        proxy.getPixels(px, 0, proxy.width, 0, 0, proxy.width, proxy.height)
        if (proxy !== bitmap) proxy.recycle()

        val sharpness = YuvUtils.computeLaplacianSharpness(px, proxy.width, proxy.height)
        val isBlurred = sharpness < 7.5
        return MotionAssessment(
            sharpnessScore = sharpness,
            isUnstableOrBlurred = isBlurred,
            processedBitmap = bitmap
        )
    }
}

class PortraitProcessor {

    /**
     * Software background bokeh using detected Camera2 face rectangles (or central subject
     * saliency fallback) + luminance-guided edge preservation + fast downsampled separable blur.
     */
    fun applyPortraitBokeh(
        src: Bitmap,
        faceRectsNormalized: List<RectF>,
        blurIntensity: Float = 0.75f,
        fullPower: Boolean = false
    ): Bitmap {
        val w = src.width
        val h = src.height

        // Build downsampled blurred background (fast on Snapdragon 680)
        val blurW = (w / 4).coerceAtLeast(120)
        val blurH = (h / 4).coerceAtLeast(120)
        val small = Bitmap.createScaledBitmap(src, blurW, blurH, true)
        val smallPx = IntArray(blurW * blurH)
        small.getPixels(smallPx, 0, blurW, 0, 0, blurW, blurH)
        small.recycle()

        var blurredSmall = boxBlurPass(
            boxBlurPass(smallPx, blurW, blurH, radius = 3, fullPower = fullPower),
            blurW,
            blurH,
            radius = 3,
            fullPower = fullPower
        )
        if (fullPower) {
            blurredSmall = boxBlurPass(blurredSmall, blurW, blurH, radius = 3, fullPower = true)
        }
        val blurredBmp = Bitmap.createBitmap(blurredSmall, blurW, blurH, Bitmap.Config.ARGB_8888)
        val fullBlur = Bitmap.createScaledBitmap(blurredBmp, w, h, true)
        blurredBmp.recycle()

        val sharpPx = IntArray(w * h)
        val blurPx = IntArray(w * h)
        src.getPixels(sharpPx, 0, w, 0, 0, w, h)
        fullBlur.getPixels(blurPx, 0, w, 0, 0, w, h)
        fullBlur.recycle()

        val focusEllipses = if (faceRectsNormalized.isNotEmpty()) {
            faceRectsNormalized.map { f ->
                val cx = f.centerX() * w
                val cy = (f.centerY() + f.height() * 0.35f) * h
                val rx = (f.width() * w * 1.15f).coerceAtLeast(w * 0.16f)
                val ry = (f.height() * h * 1.85f).coerceAtLeast(h * 0.24f)
                floatArrayOf(cx, cy, rx, ry)
            }
        } else {
            listOf(floatArrayOf(w * 0.5f, h * 0.52f, w * 0.28f, h * 0.36f))
        }

        val outPx = IntArray(w * h)
        val strength = blurIntensity.coerceIn(0f, 1f)
        ParallelCompute.parallelRows(h, fullPower) { startY, endY ->
            for (y in startY until endY) {
                val rowOff = y * w
                for (x in 0 until w) {
                    val idx = rowOff + x
                    var minNormDist = Float.MAX_VALUE
                    for (e in focusEllipses) {
                        val dx = (x - e[0]) / e[2]
                        val dy = (y - e[1]) / e[3]
                        val d = hypot(dx, dy)
                        if (d < minNormDist) minNormDist = d
                    }
                    val blurMix = (((minNormDist - 0.85f) / 0.65f).coerceIn(0f, 1f)) * strength
                    if (blurMix <= 0.01f) {
                        outPx[idx] = sharpPx[idx]
                    } else {
                        val sc = sharpPx[idx]
                        val bc = blurPx[idx]
                        val inv = 1f - blurMix
                        val r = (((sc ushr 16) and 0xFF) * inv + ((bc ushr 16) and 0xFF) * blurMix).roundToInt()
                        val g = (((sc ushr 8) and 0xFF) * inv + ((bc ushr 8) and 0xFF) * blurMix).roundToInt()
                        val b = ((sc and 0xFF) * inv + (bc and 0xFF) * blurMix).roundToInt()
                        outPx[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
        return Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlurPass(src: IntArray, w: Int, h: Int, radius: Int, fullPower: Boolean): IntArray {
        val temp = IntArray(w * h)
        val out = IntArray(w * h)
        val div = radius * 2 + 1
        ParallelCompute.parallelRows(h, fullPower) { startY, endY ->
            for (y in startY until endY) {
                val row = y * w
                for (x in 0 until w) {
                    var sr = 0; var sg = 0; var sb = 0
                    for (k in -radius..radius) {
                        val c = src[row + (x + k).coerceIn(0, w - 1)]
                        sr += (c ushr 16) and 0xFF
                        sg += (c ushr 8) and 0xFF
                        sb += c and 0xFF
                    }
                    temp[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                }
            }
        }
        ParallelCompute.parallelRows(h, fullPower) { startY, endY ->
            for (y in startY until endY) {
                for (x in 0 until w) {
                    var sr = 0; var sg = 0; var sb = 0
                    for (k in -radius..radius) {
                        val c = temp[(y + k).coerceIn(0, h - 1) * w + x]
                        sr += (c ushr 16) and 0xFF
                        sg += (c ushr 8) and 0xFF
                        sb += c and 0xFF
                    }
                    out[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                }
            }
        }
        return out
    }
}

class PanoramaEngine {

    /**
     * Stitches sequential horizontal panorama frames by matching overlapping edge strips
     * via normalized cross-correlation/SAD and feathering the blend seam.
     */
    fun stitchHorizontal(frames: List<Bitmap>): Bitmap {
        require(frames.isNotEmpty()) { "Panorama requires at least 1 frame" }
        if (frames.size == 1) return frames[0].copy(Bitmap.Config.ARGB_8888, false)

        // Normalize all frame heights to match first frame (capped for 6GB RAM safety)
        val targetH = frames[0].height.coerceAtMost(1440)
        val normFrames = frames.map { bmp ->
            if (bmp.height == targetH) bmp
            else {
                val tw = (bmp.width.toFloat() * targetH / bmp.height).roundToInt().coerceAtLeast(64)
                Bitmap.createScaledBitmap(bmp, tw, targetH, true)
            }
        }

        var canvasBmp = normFrames[0].copy(Bitmap.Config.ARGB_8888, true)
        for (i in 1 until normFrames.size) {
            canvasBmp = stitchPair(canvasBmp, normFrames[i])
            if (normFrames[i] !== frames[i]) normFrames[i].recycle()
        }
        return canvasBmp
    }

    private fun stitchPair(left: Bitmap, right: Bitmap): Bitmap {
        val lh = left.height
        val lw = left.width
        val rw = right.width

        // Search overlap in [15% .. 65%] of right width
        val minOverlap = (rw * 0.15f).roundToInt()
        val maxOverlap = (rw * 0.65f).roundToInt().coerceAtMost(lw - 10)
        if (maxOverlap <= minOverlap) {
            val combined = Bitmap.createBitmap(lw + rw, lh, Bitmap.Config.ARGB_8888)
            val c = Canvas(combined)
            c.drawBitmap(left, 0f, 0f, null)
            c.drawBitmap(right, lw.toFloat(), 0f, null)
            return combined
        }

        val leftStrip = IntArray(lw * lh)
        val rightStrip = IntArray(rw * lh)
        left.getPixels(leftStrip, 0, lw, 0, 0, lw, lh)
        right.getPixels(rightStrip, 0, rw, 0, 0, rw, lh)

        var bestOverlap = (minOverlap + maxOverlap) / 2
        var bestDy = 0
        var bestErr = Float.MAX_VALUE

        val yStep = (lh / 64).coerceAtLeast(4)
        val xStep = 6
        for (overlap in minOverlap..maxOverlap step 8) {
            for (dy in -16..16 step 4) {
                var sumErr = 0L
                var count = 0
                var y = 20
                while (y < lh - 20) {
                    val ly = (y + dy).coerceIn(0, lh - 1)
                    var x = 0
                    while (x < overlap) {
                        val lx = lw - overlap + x
                        val lLuma = YuvUtils.luma(leftStrip[ly * lw + lx])
                        val rLuma = YuvUtils.luma(rightStrip[y * rw + x])
                        sumErr += abs(lLuma - rLuma)
                        count++
                        x += xStep
                    }
                    y += yStep
                }
                val err = if (count > 0) sumErr.toFloat() / count else Float.MAX_VALUE
                if (err < bestErr) {
                    bestErr = err
                    bestOverlap = overlap
                    bestDy = dy
                }
            }
        }

        val outW = lw + rw - bestOverlap
        val outPx = IntArray(outW * lh)
        val overlapStart = lw - bestOverlap

        for (y in 0 until lh) {
            val outRow = y * outW
            val ly = (y + bestDy).coerceIn(0, lh - 1)
            for (x in 0 until outW) {
                outPx[outRow + x] = when {
                    x < overlapStart -> leftStrip[ly * lw + x]
                    x < lw -> {
                        val rx = x - overlapStart
                        val alpha = rx.toFloat() / bestOverlap.coerceAtLeast(1)
                        val lc = leftStrip[ly * lw + x]
                        val rc = rightStrip[y * rw + rx]
                        blendPixel(lc, rc, alpha)
                    }
                    else -> {
                        val rx = x - overlapStart
                        rightStrip[y * rw + rx.coerceIn(0, rw - 1)]
                    }
                }
            }
        }
        val out = Bitmap.createBitmap(outW, lh, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, outW, 0, 0, outW, lh)
        return out
    }

    private fun blendPixel(c1: Int, c2: Int, t: Float): Int {
        val inv = 1f - t
        val r = (((c1 ushr 16) and 0xFF) * inv + ((c2 ushr 16) and 0xFF) * t).roundToInt()
        val g = (((c1 ushr 8) and 0xFF) * inv + ((c2 ushr 8) and 0xFF) * t).roundToInt()
        val b = ((c1 and 0xFF) * inv + (c2 and 0xFF) * t).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

class DocumentScannerEngine {

    /**
     * Detects dominant bright rectangular document boundary and applies perspective homography
     * warp + adaptive contrast enhancement for clean document scans.
     */
    fun scanDocument(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val quad = detectDocumentQuad(src)

        val dstW = hypot(quad[2] - quad[0], quad[3] - quad[1])
            .coerceAtLeast(hypot(quad[4] - quad[6], quad[5] - quad[7]))
            .roundToInt().coerceIn(480, w)
        val dstH = hypot(quad[6] - quad[0], quad[7] - quad[1])
            .coerceAtLeast(hypot(quad[4] - quad[2], quad[5] - quad[3]))
            .roundToInt().coerceIn(640, h)

        val dst = floatArrayOf(
            0f, 0f,
            dstW.toFloat(), 0f,
            dstW.toFloat(), dstH.toFloat(),
            0f, dstH.toFloat()
        )
        val matrix = Matrix()
        matrix.setPolyToPoly(quad, 0, dst, 0, 4)

        val warped = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(warped)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, matrix, paint)

        // Document white-point normalization & crisp text contrast
        val px = IntArray(dstW * dstH)
        warped.getPixels(px, 0, dstW, 0, 0, dstW, dstH)
        for (i in px.indices) {
            val c = px[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            // Gentle white paper stretch + dark ink pull
            val nr = (((r - 22) * 1.16f)).roundToInt().coerceIn(0, 255)
            val ng = (((g - 22) * 1.16f)).roundToInt().coerceIn(0, 255)
            val nb = (((b - 22) * 1.16f)).roundToInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        warped.setPixels(px, 0, dstW, 0, 0, dstW, dstH)
        return warped
    }

    private fun detectDocumentQuad(src: Bitmap): FloatArray {
        val pw = 160
        val ph = (160f * src.height / src.width.coerceAtLeast(1)).roundToInt().coerceIn(90, 240)
        val proxy = Bitmap.createScaledBitmap(src, pw, ph, true)
        val px = IntArray(pw * ph)
        proxy.getPixels(px, 0, pw, 0, 0, pw, ph)
        proxy.recycle()

        // Find brightest foreground paper region using adaptive percentile threshold
        var lumaSum = 0L
        for (c in px) lumaSum += YuvUtils.luma(c)
        val mean = (lumaSum / px.size.coerceAtLeast(1)).toInt()
        val thresh = (mean + 24).coerceIn(115, 200)

        var minX = pw; var maxX = 0; var minY = ph; var maxY = 0
        var hits = 0
        for (y in 4 until ph - 4) {
            for (x in 4 until pw - 4) {
                if (YuvUtils.luma(px[y * pw + x]) >= thresh) {
                    hits++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val sx = src.width.toFloat() / pw
        val sy = src.height.toFloat() / ph
        return if (hits > (pw * ph * 0.12f) && (maxX - minX) > pw * 0.25f && (maxY - minY) > ph * 0.25f) {
            floatArrayOf(
                minX * sx, minY * sy,
                maxX * sx, minY * sy,
                maxX * sx, maxY * sy,
                minX * sx, maxY * sy
            )
        } else {
            // Safe default inset quad
            val mx = src.width * 0.06f
            val my = src.height * 0.06f
            floatArrayOf(
                mx, my,
                src.width - mx, my,
                src.width - mx, src.height - my,
                mx, src.height - my
            )
        }
    }
}
