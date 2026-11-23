package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.example.system.SavedMediaResult
import com.example.system.StorageManager
import com.example.system.UltraCamSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

class ImageEditorEngine(
    private val context: Context,
    private val glImageProcessor: GlImageProcessor = GlImageProcessor(),
    private val detailEngine: DetailEngine = DetailEngine()
) {

    suspend fun loadEditableBitmap(uri: Uri, maxDim: Int = 1800): Bitmap? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val longest = maxOf(opts.outWidth, opts.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxDim) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return@withContext null

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)

        YuvUtils.rotateBitmapIfNeeded(rawBmp, orientation)
    }

    suspend fun renderPreview(source: Bitmap, params: StudioEditParams): Bitmap = withContext(Dispatchers.Default) {
        if (params.isIdentity) return@withContext source

        // 1. Geometry: rotation + aspect crop + keystone perspective correction
        var working = applyGeometry(source, params)

        // 2. Optional Noise Reduction (edge-aware mild smoothing)
        if (params.noiseReduction > 0.05f) {
            val denoised = applyEdgePreservingDenoise(working, params.noiseReduction)
            if (working !== source) working.recycle()
            working = denoised
        }

        // 3. GPU-accelerated Color, Exposure, Tone & Detail grading
        val graded = glImageProcessor.processColorAndTone(working, params)
        if (working !== source) working.recycle()
        working = graded

        // 4. Adaptive Sharpness pass if requested
        if (params.sharpness > 0.05f) {
            val sharp = detailEngine.enhanceDetails(
                working,
                sharpnessAmount = params.sharpness * 0.7f,
                correctChromaticAberration = false,
                correctLensShading = false
            )
            if (working !== source) working.recycle()
            working = sharp
        }
        working
    }

    private fun applyGeometry(source: Bitmap, params: StudioEditParams): Bitmap {
        var cur = source
        if (params.rotationDegrees % 360 != 0) {
            val m = Matrix().apply { postRotate(params.rotationDegrees.toFloat()) }
            cur = Bitmap.createBitmap(cur, 0, 0, cur.width, cur.height, m, true)
        }

        val targetRatio = params.cropRatio.ratio
        if (targetRatio != null) {
            val w = cur.width
            val h = cur.height
            val curRatio = w.toFloat() / h.coerceAtLeast(1)
            val cropW: Int
            val cropH: Int
            if (curRatio > targetRatio) {
                cropH = h
                cropW = (h * targetRatio).roundToInt().coerceAtMost(w)
            } else {
                cropW = w
                cropH = (w / targetRatio).roundToInt().coerceAtMost(h)
            }
            val x0 = (w - cropW) / 2
            val y0 = (h - cropH) / 2
            val cropped = Bitmap.createBitmap(cur, x0, y0, cropW, cropH)
            if (cur !== source) cur.recycle()
            cur = cropped
        }

        if (abs(params.keystoneVertical) > 0.01f || abs(params.keystoneHorizontal) > 0.01f) {
            val w = cur.width.toFloat()
            val h = cur.height.toFloat()
            val kv = params.keystoneVertical
            val kh = params.keystoneHorizontal

            val srcPts = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
            val topInset = (kv.coerceAtLeast(0f)) * w * 0.5f
            val botInset = ((-kv).coerceAtLeast(0f)) * w * 0.5f
            val leftInset = (kh.coerceAtLeast(0f)) * h * 0.5f
            val rightInset = ((-kh).coerceAtLeast(0f)) * h * 0.5f

            val dstPts = floatArrayOf(
                topInset, leftInset,
                w - topInset, rightInset,
                w - botInset, h - rightInset,
                botInset, h - leftInset
            )
            val m = Matrix()
            m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
            val warped = Bitmap.createBitmap(cur.width, cur.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(warped)
            canvas.drawBitmap(cur, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            if (cur !== source) cur.recycle()
            cur = warped
        }
        return cur
    }

    private fun applyEdgePreservingDenoise(src: Bitmap, amount: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val thresh = (14 + amount * 34).roundToInt()

        for (y in 0 until h) {
            val ym = (y - 1).coerceAtLeast(0)
            val yp = (y + 1).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val xm = (x - 1).coerceAtLeast(0)
                val xp = (x + 1).coerceAtMost(w - 1)
                val idx = y * w + x
                val c = px[idx]
                val cr = (c ushr 16) and 0xFF
                val cg = (c ushr 8) and 0xFF
                val cb = c and 0xFF
                val cLum = (cr * 77 + cg * 150 + cb * 29) shr 8

                var sr = cr * 2; var sg = cg * 2; var sb = cb * 2; var cnt = 2
                val nIdx = intArrayOf(ym * w + x, yp * w + x, y * w + xm, y * w + xp)
                for (ni in nIdx) {
                    val nc = px[ni]
                    val nr = (nc ushr 16) and 0xFF
                    val ng = (nc ushr 8) and 0xFF
                    val nb = nc and 0xFF
                    val nLum = (nr * 77 + ng * 150 + nb * 29) shr 8
                    if (abs(nLum - cLum) <= thresh) {
                        sr += nr; sg += ng; sb += nb; cnt++
                    }
                }
                out[idx] = (0xFF shl 24) or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    suspend fun saveNonDestructiveRecipe(
        storageManager: StorageManager,
        mediaUri: Uri,
        params: StudioEditParams
    ) = withContext(Dispatchers.IO) {
        val file = storageManager.getStudioRecipeFile(mediaUri)
        val obj = JSONObject().apply {
            put("colorProfile", params.colorProfile.name)
            put("exposureEv", params.exposureEv.toDouble())
            put("contrast", params.contrast.toDouble())
            put("highlights", params.highlights.toDouble())
            put("shadows", params.shadows.toDouble())
            put("whites", params.whites.toDouble())
            put("blacks", params.blacks.toDouble())
            put("saturation", params.saturation.toDouble())
            put("vibrance", params.vibrance.toDouble())
            put("temperature", params.temperature.toDouble())
            put("tint", params.tint.toDouble())
            put("sharpness", params.sharpness.toDouble())
            put("noiseReduction", params.noiseReduction.toDouble())
            put("detailEnhance", params.detailEnhance.toDouble())
            put("rotationDegrees", params.rotationDegrees)
            put("cropRatio", params.cropRatio.name)
            put("keystoneVertical", params.keystoneVertical.toDouble())
            put("keystoneHorizontal", params.keystoneHorizontal.toDouble())
        }
        file.writeText(obj.toString())
    }

    suspend fun loadNonDestructiveRecipe(
        storageManager: StorageManager,
        mediaUri: Uri
    ): StudioEditParams = withContext(Dispatchers.IO) {
        val file = storageManager.getStudioRecipeFile(mediaUri)
        if (!file.exists()) return@withContext StudioEditParams()
        runCatching {
            val obj = JSONObject(file.readText())
            StudioEditParams(
                colorProfile = runCatching { CameraColorProfile.valueOf(obj.optString("colorProfile")) }
                    .getOrDefault(CameraColorProfile.NATURAL_TRUE),
                exposureEv = obj.optDouble("exposureEv", 0.0).toFloat(),
                contrast = obj.optDouble("contrast", 0.0).toFloat(),
                highlights = obj.optDouble("highlights", 0.0).toFloat(),
                shadows = obj.optDouble("shadows", 0.0).toFloat(),
                whites = obj.optDouble("whites", 0.0).toFloat(),
                blacks = obj.optDouble("blacks", 0.0).toFloat(),
                saturation = obj.optDouble("saturation", 0.0).toFloat(),
                vibrance = obj.optDouble("vibrance", 0.0).toFloat(),
                temperature = obj.optDouble("temperature", 0.0).toFloat(),
                tint = obj.optDouble("tint", 0.0).toFloat(),
                sharpness = obj.optDouble("sharpness", 0.0).toFloat(),
                noiseReduction = obj.optDouble("noiseReduction", 0.0).toFloat(),
                detailEnhance = obj.optDouble("detailEnhance", 0.0).toFloat(),
                rotationDegrees = obj.optInt("rotationDegrees", 0),
                cropRatio = runCatching { CropAspectOption.valueOf(obj.optString("cropRatio")) }
                    .getOrDefault(CropAspectOption.ORIGINAL),
                keystoneVertical = obj.optDouble("keystoneVertical", 0.0).toFloat(),
                keystoneHorizontal = obj.optDouble("keystoneHorizontal", 0.0).toFloat()
            )
        }.getOrDefault(StudioEditParams())
    }

    suspend fun exportEditedImage(
        storageManager: StorageManager,
        sourceUri: Uri,
        params: StudioEditParams,
        exportAsPng: Boolean,
        jpegQuality: Int,
        scaleFactor: Float,
        settings: UltraCamSettings
    ): SavedMediaResult? = withContext(Dispatchers.Default) {
        val fullBmp = loadEditableBitmap(sourceUri, maxDim = 4096) ?: return@withContext null
        val rendered = renderPreview(fullBmp, params)
        if (rendered !== fullBmp) fullBmp.recycle()

        val finalBmp = if (scaleFactor < 0.99f) {
            val tw = (rendered.width * scaleFactor).roundToInt().coerceAtLeast(64)
            val th = (rendered.height * scaleFactor).roundToInt().coerceAtLeast(64)
            val scaled = Bitmap.createScaledBitmap(rendered, tw, th, true)
            rendered.recycle()
            scaled
        } else rendered

        val outW = finalBmp.width
        val outH = finalBmp.height
        val outStream = ByteArrayOutputStream()
        val format = if (exportAsPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val ext = if (exportAsPng) "png" else "jpg"
        val mime = if (exportAsPng) "image/png" else "image/jpeg"
        finalBmp.compress(format, jpegQuality.coerceIn(60, 100), outStream)
        finalBmp.recycle()

        val rawBytes = outStream.toByteArray()
        val finalBytes = if (!exportAsPng) {
            ExifProcessor.injectStudioExportExif(
                context = context,
                exportedJpegBytes = rawBytes,
                sourceUri = sourceUri,
                params = params,
                outWidth = outW,
                outHeight = outH
            )
        } else rawBytes

        val name = storageManager.buildUniqueFilename(settings.filenamePrefix, "STUDIO", ext)
        saveNonDestructiveRecipe(storageManager, sourceUri, params)
        storageManager.savePhotoBytes(finalBytes, name, mime, settings)
    }
}
