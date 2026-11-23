package com.example.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

data class FrameAnalysisResult(
    val lumaHistogram: IntArray,
    val redHistogram: IntArray,
    val greenHistogram: IntArray,
    val blueHistogram: IntArray,
    val highlightClipPercent: Float,
    val shadowClipPercent: Float,
    val focusPeakingBitmap: Bitmap?,
    val zebraBitmap: Bitmap?,
    val meanLuma: Float,
    val edgeDensity: Float,
    val warmColorRatio: Float,
    val greenColorRatio: Float,
    val blueSkyRatio: Float,
    val topBottomLumaRatio: Float,
    val redHighlightClipPercent: Float = 0f,
    val greenHighlightClipPercent: Float = 0f,
    val blueHighlightClipPercent: Float = 0f,
    val focusSharpnessPercent: Int = 0
)

object YuvUtils {

    fun computeFocusEdgeResponse(c: Int, l: Int, r: Int, t: Int, b: Int): Int {
        val sobel = abs(r - l) + abs(b - t)
        val lap = abs(4 * c - l - r - t - b)
        return maxOf(sobel, (lap * 3) / 4)
    }

    fun mapOverlayCoord(
        gx: Int,
        gy: Int,
        gridW: Int,
        gridH: Int,
        displayRotationDeg: Int,
        mirrorHorizontal: Boolean
    ): Pair<Int, Int> {
        val rot = ((displayRotationDeg % 360) + 360) % 360
        var rx: Int
        var ry: Int
        when (rot) {
            90 -> {
                rx = (gridH - 1 - gy).coerceIn(0, gridH - 1)
                ry = gx.coerceIn(0, gridW - 1)
            }
            180 -> {
                rx = (gridW - 1 - gx).coerceIn(0, gridW - 1)
                ry = (gridH - 1 - gy).coerceIn(0, gridH - 1)
            }
            270 -> {
                rx = gy.coerceIn(0, gridH - 1)
                ry = (gridW - 1 - gx).coerceIn(0, gridW - 1)
            }
            else -> {
                rx = gx.coerceIn(0, gridW - 1)
                ry = gy.coerceIn(0, gridH - 1)
            }
        }
        val outW = if (rot == 90 || rot == 270) gridH else gridW
        if (mirrorHorizontal) {
            rx = (outW - 1 - rx).coerceIn(0, outW - 1)
        }
        return rx to ry
    }

    fun analyzePreviewYuv(
        image: Image,
        computePeaking: Boolean,
        computeZebra: Boolean,
        peakingColorArgb: Int = 0xFF00F0FF.toInt(),
        peakingThreshold: Int = 62,
        displayRotationDeg: Int = 0,
        mirrorHorizontal: Boolean = false
    ): FrameAnalysisResult {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer.duplicate().apply { rewind() }
        val uBuf = uPlane.buffer.duplicate().apply { rewind() }
        val vBuf = vPlane.buffer.duplicate().apply { rewind() }

        val yBytes = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
        val uBytes = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
        val vBytes = ByteArray(vBuf.remaining()).also { vBuf.get(it) }

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride

        val lumaHist = IntArray(64)
        val rHist = IntArray(64)
        val gHist = IntArray(64)
        val bHist = IntArray(64)

        val step = (minOf(width, height) / 96).coerceAtLeast(4)
        var totalSamples = 0
        var highlightClips = 0
        var shadowClips = 0
        var rClips = 0
        var gClips = 0
        var bClips = 0
        var sumLuma = 0L
        var topLumaSum = 0L
        var topCount = 0
        var bottomLumaSum = 0L
        var bottomCount = 0
        var warmCount = 0
        var greenCount = 0
        var blueSkyCount = 0

        val halfH = height / 2
        var y = 0
        while (y < height) {
            var x = 0
            val yRowOffset = y * yRowStride
            val uvRowOffset = (y / 2) * uvRowStride
            while (x < width) {
                val yIdx = yRowOffset + x * yPixStride
                val uvIdx = uvRowOffset + (x / 2) * uvPixStride
                if (yIdx in yBytes.indices && uvIdx in uBytes.indices && uvIdx in vBytes.indices) {
                    val yVal = yBytes[yIdx].toInt() and 0xFF
                    val uVal = (uBytes[uvIdx].toInt() and 0xFF) - 128
                    val vVal = (vBytes[uvIdx].toInt() and 0xFF) - 128

                    val r = (yVal + (1.402f * vVal)).toInt().coerceIn(0, 255)
                    val g = (yVal - 0.344136f * uVal - 0.714136f * vVal).toInt().coerceIn(0, 255)
                    val b = (yVal + (1.772f * uVal)).toInt().coerceIn(0, 255)

                    lumaHist[yVal shr 2]++
                    rHist[r shr 2]++
                    gHist[g shr 2]++
                    bHist[b shr 2]++

                    if (yVal >= 245) highlightClips++
                    if (yVal <= 10) shadowClips++
                    if (r >= 248) rClips++
                    if (g >= 248) gClips++
                    if (b >= 248) bClips++
                    sumLuma += yVal
                    if (y < halfH) {
                        topLumaSum += yVal
                        topCount++
                        if (b > r + 20 && b > g) blueSkyCount++
                    } else {
                        bottomLumaSum += yVal
                        bottomCount++
                    }

                    if (r > g + 18 && r > b + 30) warmCount++
                    if (g > r + 15 && g > b + 15) greenCount++
                    totalSamples++
                }
                x += step
            }
            y += step
        }

        val denom = totalSamples.coerceAtLeast(1).toFloat()
        val meanLuma = sumLuma / denom
        val topMean = if (topCount > 0) topLumaSum.toFloat() / topCount else meanLuma
        val bottomMean = if (bottomCount > 0) bottomLumaSum.toFloat() / bottomCount else meanLuma
        val topBottomRatio = if (bottomMean > 1f) topMean / bottomMean else 1f

        // High-precision Focus Peaking + Zebra overlay oriented to display space (200x150)
        val gridW = 200
        val gridH = (200f * height / width.coerceAtLeast(1)).toInt().coerceIn(90, 200)
        val rot = ((displayRotationDeg % 360) + 360) % 360
        val outW = if (rot == 90 || rot == 270) gridH else gridW
        val outH = if (rot == 90 || rot == 270) gridW else gridH

        val peakingPixels = if (computePeaking) IntArray(outW * outH) else null
        val zebraPixels = if (computeZebra) IntArray(outW * outH) else null
        var edgeHits = 0
        var peakHits = 0
        var peakEnergySum = 0L
        var edgeTotal = 0

        val baseRgb = peakingColorArgb and 0x00FFFFFF
        val strongColor = (0xFF shl 24) or baseRgb
        val softColor = (0xA8 shl 24) or baseRgb
        val strongCutoff = (peakingThreshold * 1.55f).toInt().coerceAtLeast(peakingThreshold + 24)

        for (gy in 1 until gridH - 1) {
            val sy = gy * height / gridH
            val syPrev = (gy - 1) * height / gridH
            val syNext = (gy + 1) * height / gridH
            for (gx in 1 until gridW - 1) {
                val sx = gx * width / gridW
                val sxPrev = (gx - 1) * width / gridW
                val sxNext = (gx + 1) * width / gridW

                val c = yAt(yBytes, sy, sx, yRowStride, yPixStride)
                val l = yAt(yBytes, sy, sxPrev, yRowStride, yPixStride)
                val r = yAt(yBytes, sy, sxNext, yRowStride, yPixStride)
                val t = yAt(yBytes, syPrev, sx, yRowStride, yPixStride)
                val b = yAt(yBytes, syNext, sx, yRowStride, yPixStride)

                val edgeResp = computeFocusEdgeResponse(c, l, r, t, b)
                if (edgeResp > 52) edgeHits++
                if (edgeResp >= peakingThreshold) {
                    peakHits++
                    peakEnergySum += (edgeResp - peakingThreshold).coerceAtMost(220)
                }
                edgeTotal++

                if (peakingPixels != null || zebraPixels != null) {
                    val (ox, oy) = mapOverlayCoord(gx, gy, gridW, gridH, rot, mirrorHorizontal)
                    val outIdx = oy * outW + ox
                    if (peakingPixels != null && edgeResp >= peakingThreshold && outIdx in peakingPixels.indices) {
                        peakingPixels[outIdx] = if (edgeResp >= strongCutoff) strongColor else softColor
                    }
                    if (zebraPixels != null && outIdx in zebraPixels.indices) {
                        val stripe = ((ox + oy) % 6) < 3
                        if (c >= 245 && stripe) {
                            zebraPixels[outIdx] = 0xCCEF4444.toInt() // highlight clip crimson
                        } else if (c <= 10 && stripe) {
                            zebraPixels[outIdx] = 0xCC38BDF8.toInt() // shadow clip cyan
                        }
                    }
                }
            }
        }

        val peakingBmp = peakingPixels?.let {
            Bitmap.createBitmap(it, outW, outH, Bitmap.Config.ARGB_8888)
        }
        val zebraBmp = zebraPixels?.let {
            Bitmap.createBitmap(it, outW, outH, Bitmap.Config.ARGB_8888)
        }
        val peakDensity = (peakHits.toFloat() / edgeTotal.coerceAtLeast(1)) * 100f
        val meanPeakIntensity = if (peakHits > 0) peakEnergySum.toFloat() / peakHits else 0f
        val focusSharpnessPercent = ((peakDensity * 4.5f) + (meanPeakIntensity * 0.45f))
            .toInt()
            .coerceIn(0, 100)

        return FrameAnalysisResult(
            lumaHistogram = lumaHist,
            redHistogram = rHist,
            greenHistogram = gHist,
            blueHistogram = bHist,
            highlightClipPercent = (highlightClips * 100f) / denom,
            shadowClipPercent = (shadowClips * 100f) / denom,
            focusPeakingBitmap = peakingBmp,
            zebraBitmap = zebraBmp,
            meanLuma = meanLuma,
            edgeDensity = edgeHits.toFloat() / edgeTotal.coerceAtLeast(1),
            warmColorRatio = warmCount / denom,
            greenColorRatio = greenCount / denom,
            blueSkyRatio = blueSkyCount / topCount.coerceAtLeast(1).toFloat(),
            topBottomLumaRatio = topBottomRatio,
            redHighlightClipPercent = (rClips * 100f) / denom,
            greenHighlightClipPercent = (gClips * 100f) / denom,
            blueHighlightClipPercent = (bClips * 100f) / denom,
            focusSharpnessPercent = focusSharpnessPercent
        )
    }

    private fun yAt(
        bytes: ByteArray,
        row: Int,
        col: Int,
        rowStride: Int,
        pixStride: Int
    ): Int {
        val idx = row * rowStride + col * pixStride
        return if (idx in bytes.indices) bytes[idx].toInt() and 0xFF else 0
    }

    fun yuv420ToBitmap(image: Image, rotationDegrees: Int, maxDim: Int = 2048): Bitmap {
        val width = image.width
        val height = image.height
        val nv21 = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 99, out)
        val jpegBytes = out.toByteArray()

        val opts = BitmapFactory.Options().apply {
            val longest = maxOf(width, height)
            inSampleSize = if (longest > maxDim) {
                var s = 1
                while (longest / (s * 2) >= maxDim) s *= 2
                s
            } else 1
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
        return rotateBitmapIfNeeded(decoded, rotationDegrees)
    }

    fun rotateBitmapIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val rot = ((rotationDegrees % 360) + 360) % 360
        if (rot == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rot.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    fun decodeJpegDownsampled(bytes: ByteArray, maxDim: Int = 2560): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer.duplicate().apply { rewind() }
        val uBuf = uPlane.buffer.duplicate().apply { rewind() }
        val vBuf = vPlane.buffer.duplicate().apply { rewind() }
        val yLimit = yBuf.limit()
        val uLimit = uBuf.limit()
        val vLimit = vBuf.limit()

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        var pos = 0
        if (yPixStride == 1 && yRowStride == width && yLimit >= ySize) {
            yBuf.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                val rowOff = row * yRowStride
                for (col in 0 until width) {
                    val idx = rowOff + col * yPixStride
                    nv21[pos++] = if (idx in 0 until yLimit) yBuf.get(idx) else 0
                }
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride

        for (row in 0 until chromaHeight) {
            val uOff = row * uRowStride
            val vOff = row * vRowStride
            for (col in 0 until chromaWidth) {
                if (pos + 1 < nv21.size) {
                    val vIdx = vOff + col * vPixStride
                    val uIdx = uOff + col * uPixStride
                    nv21[pos++] = if (vIdx in 0 until vLimit) vBuf.get(vIdx) else 128.toByte()
                    nv21[pos++] = if (uIdx in 0 until uLimit) uBuf.get(uIdx) else 128.toByte()
                }
            }
        }
        return nv21
    }

    fun computeLaplacianSharpness(pixels: IntArray, width: Int, height: Int): Double {
        if (width < 4 || height < 4) return 0.0
        var sumSq = 0.0
        var count = 0
        val step = (minOf(width, height) / 160).coerceAtLeast(1)
        var y = step
        while (y < height - step) {
            var x = step
            while (x < width - step) {
                val c = luma(pixels[y * width + x])
                val l = luma(pixels[y * width + (x - step)])
                val r = luma(pixels[y * width + (x + step)])
                val t = luma(pixels[(y - step) * width + x])
                val b = luma(pixels[(y + step) * width + x])
                val lap = 4 * c - l - r - t - b
                sumSq += lap * lap
                count++
                x += step
            }
            y += step
        }
        return if (count > 0) sqrt(sumSq / count) else 0.0
    }

    inline fun luma(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }
}
