package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.location.Location
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import com.example.processing.AiProcessingProfile
import com.example.processing.ComputationalPhotographyEngine
import com.example.processing.ExifProcessor
import com.example.processing.YuvUtils
import com.example.system.SavedMediaResult
import com.example.system.StorageManager
import com.example.system.UltraCamSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

data class PhotoCaptureOutcome(
    val savedResults: List<SavedMediaResult>,
    val statusWarning: String? = null,
    val detailInfo: String? = null
)

class PhotoCaptureEngine(
    private val context: Context,
    private val sessionManager: CameraSessionManager,
    private val manualController: ManualCameraController,
    private val rawCaptureEngine: RawCaptureEngine,
    private val compEngine: ComputationalPhotographyEngine,
    private val storageManager: StorageManager
) {

    fun computeJpegOrientation(caps: CameraDeviceCapabilities, deviceRotationDeg: Int): Int {
        val isFront = caps.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        return if (isFront) {
            (caps.sensorOrientation - deviceRotationDeg + 360) % 360
        } else {
            (caps.sensorOrientation + deviceRotationDeg) % 360
        }
    }

    suspend fun captureSingleJpeg(
        caps: CameraDeviceCapabilities,
        controls: ProControlsState,
        jpegReader: ImageReader,
        jpegQueue: ConcurrentLinkedQueue<Pair<Long, ByteArray>>,
        deviceRotationDeg: Int,
        settings: UltraCamSettings,
        location: Location?,
        modeTag: String = "PHOTO",
        applyPortraitBokeh: Boolean = false,
        detectedFaces: List<RectF> = emptyList(),
        applyDocScanner: Boolean = false,
        aiProfile: AiProcessingProfile? = null,
        focusPowerMode: Boolean = false,
        liveShadowBias: Float = 0f,
        liveHighlightBias: Float = 0f,
        aiSceneName: String? = null,
        tapMetered: Boolean = false,
        aiAutoWb: WhiteBalancePreset? = null
    ): PhotoCaptureOutcome {
        val device = sessionManager.cameraDevice ?: return PhotoCaptureOutcome(emptyList(), "Camera not ready")
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(jpegReader.surface)
        manualController.applyToBuilder(
            builder = builder,
            caps = caps,
            controls = controls,
            isVideoMode = false,
            eisEnabled = false,
            aiProfile = aiProfile,
            focusPowerMode = focusPowerMode,
            aiAutoWb = aiAutoWb
        )
        val outJpegQuality = if (focusPowerMode) maxOf(controls.jpegQuality, 98) else controls.jpegQuality
        builder.set(CaptureRequest.JPEG_QUALITY, outJpegQuality.toByte())
        builder.set(CaptureRequest.JPEG_ORIENTATION, computeJpegOrientation(caps, deviceRotationDeg))

        jpegQueue.clear()
        val result = sessionManager.captureSingle(builder).getOrElse {
            return PhotoCaptureOutcome(emptyList(), it.message ?: "Capture failed")
        }
        val ts = result.get(TotalCaptureResult.SENSOR_TIMESTAMP) ?: 0L
        val jpegBytes = awaitJpegBytes(jpegQueue, ts)
            ?: return PhotoCaptureOutcome(emptyList(), "Timed out waiting for JPEG buffer")

        var finalBytes = jpegBytes
        var warning: String? = null
        var outWidth = 0
        var outHeight = 0
        var outExifOrientation = ExifInterface.ORIENTATION_NORMAL

        val halOrientationDeg = ExifProcessor.readJpegOrientationDegrees(jpegBytes)
        val shouldRefine = applyPortraitBokeh ||
            applyDocScanner ||
            settings.watermarkEnabled ||
            settings.aiAssistantEnabled ||
            focusPowerMode ||
            !controls.isManualMode ||
            settings.libPatcher.colorProfile != com.example.processing.CameraColorProfile.NATURAL_TRUE

        if (shouldRefine) {
            val decodeMaxDim = if (focusPowerMode) 4096 else 3264
            val rawBmp = YuvUtils.decodeJpegDownsampled(jpegBytes, maxDim = decodeMaxDim)
            val bmp = YuvUtils.rotateBitmapIfNeeded(rawBmp, halOrientationDeg)
            var processed = when {
                applyDocScanner -> compEngine.documentScannerEngine.scanDocument(bmp)
                applyPortraitBokeh -> compEngine.processPortrait(
                    src = bmp,
                    facesNormalized = detectedFaces,
                    aiProfile = aiProfile,
                    fullPower = focusPowerMode,
                    libPatcher = settings.libPatcher,
                    liveShadowBias = liveShadowBias,
                    liveHighlightBias = liveHighlightBias
                )
                else -> {
                    val assessed = compEngine.refineStandardPhoto(
                        src = bmp,
                        aiProfile = aiProfile,
                        fullPower = focusPowerMode,
                        libPatcher = settings.libPatcher,
                        liveShadowBias = liveShadowBias,
                        liveHighlightBias = liveHighlightBias
                    )
                    if (assessed.isUnstableOrBlurred) warning = "unstable"
                    assessed.processedBitmap
                }
            }
            if (settings.watermarkEnabled) {
                val shotIso = result.get(TotalCaptureResult.SENSOR_SENSITIVITY) ?: controls.iso
                val shotExp = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME) ?: controls.exposureTimeNs
                val shotAp = caps.apertures.firstOrNull() ?: 1.8f
                val stamped = stampWatermark(
                    src = processed,
                    text = settings.watermarkText,
                    style = settings.watermarkStyle,
                    exifLine = formatExifBadge(shotAp, shotExp, shotIso, settings.libPatcher.colorProfile.badge)
                )
                if (stamped !== processed) processed.recycle()
                processed = stamped
            }
            outWidth = processed.width
            outHeight = processed.height
            outExifOrientation = ExifInterface.ORIENTATION_NORMAL
            finalBytes = encodeBitmapJpeg(processed, outJpegQuality)
            if (processed !== bmp) processed.recycle()
            bmp.recycle()
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
            outWidth = bounds.outWidth
            outHeight = bounds.outHeight
            outExifOrientation = when (halOrientationDeg) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
        }

        val exifPayload = ExifProcessor.buildPayload(
            caps = caps,
            controls = controls,
            settings = settings,
            captureResult = result,
            modeTag = modeTag,
            frameCount = 1,
            focusPowerMode = focusPowerMode,
            aiSceneName = aiSceneName,
            imageWidth = outWidth,
            imageHeight = outHeight,
            exifOrientation = outExifOrientation,
            location = location,
            tapMetered = tapMetered
        )
        finalBytes = ExifProcessor.injectJpegExifBytes(
            context = context,
            jpegBytes = finalBytes,
            payload = exifPayload,
            sourceHalJpegBytes = jpegBytes
        )

        val name = storageManager.buildUniqueFilename(settings.filenamePrefix, modeTag, "jpg")
        val saved = storageManager.savePhotoBytes(finalBytes, name, "image/jpeg", settings, location)
        val infoTag = buildString {
            append(settings.libPatcher.colorProfile.badge)
            if (focusPowerMode) append(" · TURBO 100%")
        }
        return PhotoCaptureOutcome(listOfNotNull(saved), statusWarning = warning, detailInfo = infoTag)
    }

    /**
     * High-speed Camera2 continuous burst capture for action photography.
     * Queues [frameCount] requests in one atomic HAL captureBurst() call with live viewfinder feed
     * preserved on [previewSurface], calculates actual achieved sensor FPS, and injects EXIF on all frames.
     */
    suspend fun captureHighSpeedBurst(
        caps: CameraDeviceCapabilities,
        controls: ProControlsState,
        jpegReader: ImageReader,
        previewSurface: Surface?,
        jpegQueue: ConcurrentLinkedQueue<Pair<Long, ByteArray>>,
        deviceRotationDeg: Int,
        frameCount: Int,
        settings: UltraCamSettings,
        location: Location?,
        aiProfile: AiProcessingProfile? = null,
        focusPowerMode: Boolean = false,
        liveShadowBias: Float = 0f,
        liveHighlightBias: Float = 0f,
        aiSceneName: String? = null,
        tapMetered: Boolean = false,
        aiAutoWb: WhiteBalancePreset? = null,
        onProgress: (BurstCaptureProgress) -> Unit
    ): PhotoCaptureOutcome {
        val device = sessionManager.cameraDevice ?: return PhotoCaptureOutcome(emptyList(), "Camera not ready")
        val targetCount = frameCount.coerceIn(2, 15)
        val jpegOrientation = computeJpegOrientation(caps, deviceRotationDeg)
        val outJpegQuality = if (focusPowerMode) maxOf(controls.jpegQuality, 96) else controls.jpegQuality.coerceAtMost(95)

        val builders = List(targetCount) {
            buildBurstRequestBuilder(
                device = device,
                caps = caps,
                controls = controls,
                jpegSurface = jpegReader.surface,
                previewSurface = previewSurface,
                jpegQuality = outJpegQuality,
                jpegOrientation = jpegOrientation,
                aiProfile = aiProfile,
                focusPowerMode = focusPowerMode,
                aiAutoWb = aiAutoWb
            )
        }

        jpegQueue.clear()
        onProgress(BurstCaptureProgress(capturedCount = 0, targetCount = targetCount, achievedFps = 0f))

        var firstTs = 0L
        val burstResults = sessionManager.captureBurstWithProgress(builders) { completed, latest ->
            val ts = latest.get(TotalCaptureResult.SENSOR_TIMESTAMP) ?: 0L
            if (firstTs == 0L && ts > 0L) firstTs = ts
            val fps = if (completed > 1 && ts > firstTs) {
                ((completed - 1) * 1_000_000_000f) / (ts - firstTs).toFloat()
            } else 0f
            onProgress(BurstCaptureProgress(capturedCount = completed, targetCount = targetCount, achievedFps = fps))
        }.getOrElse {
            return PhotoCaptureOutcome(emptyList(), it.message ?: "High-speed burst failed")
        }

        val collectedFrames = awaitBurstJpegFrames(jpegQueue, burstResults)
        if (collectedFrames.isEmpty()) {
            return PhotoCaptureOutcome(emptyList(), "Timed out waiting for burst frames")
        }

        return finalizeAndSaveBurstFrames(
            frames = collectedFrames,
            caps = caps,
            controls = controls,
            outJpegQuality = outJpegQuality,
            settings = settings,
            location = location,
            aiProfile = aiProfile,
            focusPowerMode = focusPowerMode,
            liveShadowBias = liveShadowBias,
            liveHighlightBias = liveHighlightBias,
            aiSceneName = aiSceneName,
            tapMetered = tapMetered
        )
    }

    /**
     * Press-and-hold continuous action burst using pipelined mini-burst waves via Camera2 captureBurst()
     * until [isHoldActive] returns false or [maxFrames] is reached.
     */
    suspend fun captureContinuousHoldBurst(
        caps: CameraDeviceCapabilities,
        controls: ProControlsState,
        jpegReader: ImageReader,
        previewSurface: Surface?,
        jpegQueue: ConcurrentLinkedQueue<Pair<Long, ByteArray>>,
        deviceRotationDeg: Int,
        maxFrames: Int,
        settings: UltraCamSettings,
        location: Location?,
        aiProfile: AiProcessingProfile? = null,
        focusPowerMode: Boolean = false,
        liveShadowBias: Float = 0f,
        liveHighlightBias: Float = 0f,
        aiSceneName: String? = null,
        tapMetered: Boolean = false,
        aiAutoWb: WhiteBalancePreset? = null,
        isHoldActive: () -> Boolean,
        onProgress: (BurstCaptureProgress) -> Unit
    ): PhotoCaptureOutcome {
        val device = sessionManager.cameraDevice ?: return PhotoCaptureOutcome(emptyList(), "Camera not ready")
        val cap = maxFrames.coerceIn(2, 15)
        val jpegOrientation = computeJpegOrientation(caps, deviceRotationDeg)
        val outJpegQuality = if (focusPowerMode) maxOf(controls.jpegQuality, 96) else controls.jpegQuality.coerceAtMost(95)

        jpegQueue.clear()
        val allResults = ArrayList<TotalCaptureResult>(cap)
        val allFrames = ArrayList<Pair<TotalCaptureResult?, ByteArray>>(cap)
        var firstTs = 0L

        while (isHoldActive() && allResults.size < cap) {
            val waveCount = minOf(3, cap - allResults.size)
            val waveBuilders = List(waveCount) {
                buildBurstRequestBuilder(
                    device = device,
                    caps = caps,
                    controls = controls,
                    jpegSurface = jpegReader.surface,
                    previewSurface = previewSurface,
                    jpegQuality = outJpegQuality,
                    jpegOrientation = jpegOrientation,
                    aiProfile = aiProfile,
                    focusPowerMode = focusPowerMode,
                    aiAutoWb = aiAutoWb
                )
            }
            val waveResults = sessionManager.captureBurstWithProgress(waveBuilders) { _, latest ->
                val ts = latest.get(TotalCaptureResult.SENSOR_TIMESTAMP) ?: 0L
                if (firstTs == 0L && ts > 0L) firstTs = ts
                val countNow = allResults.size + 1
                val fps = if (countNow > 1 && ts > firstTs) {
                    ((countNow - 1) * 1_000_000_000f) / (ts - firstTs).toFloat()
                } else 0f
                onProgress(
                    BurstCaptureProgress(
                        capturedCount = minOf(countNow, cap),
                        targetCount = cap,
                        achievedFps = fps,
                        isContinuousHold = true
                    )
                )
            }.getOrNull() ?: break

            allResults.addAll(waveResults)
            val matched = awaitBurstJpegFrames(jpegQueue, waveResults)
            allFrames.addAll(matched)
        }

        if (allFrames.isEmpty()) {
            return PhotoCaptureOutcome(emptyList(), "No continuous burst frames captured")
        }

        return finalizeAndSaveBurstFrames(
            frames = allFrames,
            caps = caps,
            controls = controls,
            outJpegQuality = outJpegQuality,
            settings = settings,
            location = location,
            aiProfile = aiProfile,
            focusPowerMode = focusPowerMode,
            liveShadowBias = liveShadowBias,
            liveHighlightBias = liveHighlightBias,
            aiSceneName = aiSceneName,
            tapMetered = tapMetered
        )
    }

    private fun buildBurstRequestBuilder(
        device: CameraDevice,
        caps: CameraDeviceCapabilities,
        controls: ProControlsState,
        jpegSurface: Surface,
        previewSurface: Surface?,
        jpegQuality: Int,
        jpegOrientation: Int,
        aiProfile: AiProcessingProfile?,
        focusPowerMode: Boolean,
        aiAutoWb: WhiteBalancePreset? = null
    ): CaptureRequest.Builder {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(jpegSurface)
        if (previewSurface != null && previewSurface.isValid) {
            builder.addTarget(previewSurface)
        }
        manualController.applyToBuilder(
            builder = builder,
            caps = caps,
            controls = controls,
            isVideoMode = false,
            eisEnabled = false,
            aiProfile = aiProfile,
            focusPowerMode = focusPowerMode,
            aiAutoWb = aiAutoWb
        )
        // Prioritize HAL pipeline throughput for high-speed burst unless user forced Focus Turbo 100%
        if (!focusPowerMode) {
            if (caps.noiseReductionModeIds.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            }
            if (caps.edgeModeIds.contains(CameraMetadata.EDGE_MODE_FAST)) {
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !controls.manualExposureEnabled) {
            runCatching { builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, true) }
        }
        builder.set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
        builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
        return builder
    }

    private suspend fun awaitBurstJpegFrames(
        jpegQueue: ConcurrentLinkedQueue<Pair<Long, ByteArray>>,
        results: List<TotalCaptureResult>
    ): List<Pair<TotalCaptureResult?, ByteArray>> {
        val expected = results.size.coerceAtLeast(1)
        val tsMap = HashMap<Long, TotalCaptureResult>(expected)
        for (res in results) {
            val ts = res.get(TotalCaptureResult.SENSOR_TIMESTAMP)
            if (ts != null) tsMap[ts] = res
        }
        val out = ArrayList<Pair<TotalCaptureResult?, ByteArray>>(expected)
        withTimeoutOrNull(4500L) {
            while (out.size < expected) {
                val item = jpegQueue.poll()
                if (item != null) {
                    val matchedRes = tsMap[item.first] ?: results.getOrNull(out.size)
                    out.add(matchedRes to item.second)
                } else {
                    kotlinx.coroutines.delay(15L)
                }
            }
        }
        // Drain any straggler frames briefly
        while (out.size < expected) {
            val item = jpegQueue.poll() ?: break
            val matchedRes = tsMap[item.first] ?: results.getOrNull(out.size)
            out.add(matchedRes to item.second)
        }
        return out
    }

    private suspend fun finalizeAndSaveBurstFrames(
        frames: List<Pair<TotalCaptureResult?, ByteArray>>,
        caps: CameraDeviceCapabilities,
        controls: ProControlsState,
        outJpegQuality: Int,
        settings: UltraCamSettings,
        location: Location?,
        aiProfile: AiProcessingProfile?,
        focusPowerMode: Boolean,
        liveShadowBias: Float,
        liveHighlightBias: Float,
        aiSceneName: String?,
        tapMetered: Boolean
    ): PhotoCaptureOutcome = coroutineScope {
        val timestamps = frames.mapNotNull { it.first?.get(TotalCaptureResult.SENSOR_TIMESTAMP) }.sorted()
        val achievedFps = if (timestamps.size >= 2 && timestamps.last() > timestamps.first()) {
            ((timestamps.size - 1) * 1_000_000_000f) / (timestamps.last() - timestamps.first()).toFloat()
        } else 0f

        val needsPixelPass = settings.watermarkEnabled ||
            focusPowerMode ||
            settings.libPatcher.colorProfile != com.example.processing.CameraColorProfile.NATURAL_TRUE ||
            kotlin.math.abs(liveShadowBias) > 0.01f ||
            kotlin.math.abs(liveHighlightBias) > 0.01f

        val burstBatchPrefix = storageManager.buildUniqueFilename(settings.filenamePrefix, "BURST", "jpg")
            .substringBeforeLast('.')

        val totalCount = frames.size
        val savedResults = frames.mapIndexed { idx, (capRes, rawJpeg) ->
            async(Dispatchers.Default) {
                val halRot = ExifProcessor.readJpegOrientationDegrees(rawJpeg)
                var outBytes = rawJpeg
                var outW = 0
                var outH = 0
                var outOrient = when (halRot) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }

                if (needsPixelPass) {
                    val maxDim = if (focusPowerMode) 3264 else 2560
                    val decoded = YuvUtils.decodeJpegDownsampled(rawJpeg, maxDim = maxDim)
                    val upright = YuvUtils.rotateBitmapIfNeeded(decoded, halRot)
                    var processed = compEngine.refineStandardPhoto(
                        src = upright,
                        aiProfile = aiProfile,
                        fullPower = focusPowerMode,
                        libPatcher = settings.libPatcher,
                        liveShadowBias = liveShadowBias,
                        liveHighlightBias = liveHighlightBias
                    ).processedBitmap
                    if (settings.watermarkEnabled) {
                        val shotIso = capRes?.get(TotalCaptureResult.SENSOR_SENSITIVITY) ?: controls.iso
                        val shotExp = capRes?.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME) ?: controls.exposureTimeNs
                        val shotAp = caps.apertures.firstOrNull() ?: 1.8f
                        val stamped = stampWatermark(
                            src = processed,
                            text = settings.watermarkText,
                            style = settings.watermarkStyle,
                            exifLine = formatExifBadge(shotAp, shotExp, shotIso, settings.libPatcher.colorProfile.badge)
                        )
                        if (stamped !== processed) processed.recycle()
                        processed = stamped
                    }
                    outW = processed.width
                    outH = processed.height
                    outOrient = ExifInterface.ORIENTATION_NORMAL
                    outBytes = encodeBitmapJpeg(processed, outJpegQuality)
                    if (processed !== upright) processed.recycle()
                    upright.recycle()
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size, bounds)
                    outW = bounds.outWidth
                    outH = bounds.outHeight
                }

                val payload = ExifProcessor.buildPayload(
                    caps = caps,
                    controls = controls,
                    settings = settings,
                    captureResult = capRes,
                    modeTag = "BURST",
                    frameCount = totalCount,
                    focusPowerMode = focusPowerMode,
                    aiSceneName = aiSceneName,
                    imageWidth = outW,
                    imageHeight = outH,
                    exifOrientation = outOrient,
                    location = location,
                    tapMetered = tapMetered
                )
                val enriched = ExifProcessor.injectJpegExifBytes(
                    context = context,
                    jpegBytes = outBytes,
                    payload = payload,
                    sourceHalJpegBytes = rawJpeg
                )
                val seq = String.format(Locale.US, "%02d", idx + 1)
                storageManager.savePhotoBytes(
                    bytes = enriched,
                    displayName = "${burstBatchPrefix}_F$seq.jpg",
                    mimeType = "image/jpeg",
                    settings = settings,
                    captureLocation = location
                )
            }
        }.awaitAll().filterNotNull()

        val fpsText = if (achievedFps > 0.5f) String.format(Locale.US, " · %.1f fps", achievedFps) else ""
        PhotoCaptureOutcome(
            savedResults = savedResults,
            detailInfo = "Burst ${savedResults.size}F$fpsText"
        )
    }

    suspend fun captureRawOrDual(
        caps: CameraDeviceCapabilities,
        controls: ProControlsState,
        jpegReader: ImageReader?,
        rawReader: ImageReader,
        jpegQueue: ConcurrentLinkedQueue<Pair<Long, ByteArray>>,
        rawQueue: ConcurrentLinkedQueue<Image>,
        deviceRotationDeg: Int,
        settings: UltraCamSettings,
        location: Location?,
        alsoSaveJpeg: Boolean,
        aiSceneName: String? = null,
        focusPowerMode: Boolean = false,
        tapMetered: Boolean = false
    ): PhotoCaptureOutcome {
        if (!caps.supportsRaw) {
            return PhotoCaptureOutcome(emptyList(), "RAW capture not supported on this lens")
        }
        val device = sessionManager.cameraDevice ?: return PhotoCaptureOutcome(emptyList(), "Camera not ready")
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(rawReader.surface)
        if (alsoSaveJpeg && jpegReader != null) {
            builder.addTarget(jpegReader.surface)
            builder.set(CaptureRequest.JPEG_QUALITY, controls.jpegQuality.toByte())
            builder.set(CaptureRequest.JPEG_ORIENTATION, computeJpegOrientation(caps, deviceRotationDeg))
        }
        manualController.applyToBuilder(builder, caps, controls, isVideoMode = false, eisEnabled = false)

        jpegQueue.clear()
        drainRawQueue(rawQueue)

        val result = sessionManager.captureSingle(builder).getOrElse {
            return PhotoCaptureOutcome(emptyList(), it.message ?: "RAW capture failed")
        }
        val ts = result.get(TotalCaptureResult.SENSOR_TIMESTAMP) ?: 0L
        val rawImg = awaitRawImage(rawQueue, ts)
            ?: return PhotoCaptureOutcome(emptyList(), "Timed out waiting for RAW_SENSOR buffer")

        val saved = mutableListOf<SavedMediaResult>()
        try {
            val dngSaved = rawCaptureEngine.saveDng(
                caps = caps,
                controls = controls,
                rawImage = rawImg,
                captureResult = result,
                deviceOrientationDeg = deviceRotationDeg,
                settings = settings,
                location = location,
                aiSceneName = aiSceneName,
                focusPowerMode = focusPowerMode,
                tapMetered = tapMetered
            )
            if (dngSaved != null) saved.add(dngSaved)
        } finally {
            rawImg.close()
        }

        if (alsoSaveJpeg) {
            val jpgBytes = awaitJpegBytes(jpegQueue, ts)
            if (jpgBytes != null) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.size, bounds)
                val halRot = ExifProcessor.readJpegOrientationDegrees(jpgBytes)
                val exifOrient = when (halRot) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                val payload = ExifProcessor.buildPayload(
                    caps = caps,
                    controls = controls,
                    settings = settings,
                    captureResult = result,
                    modeTag = "RAW+JPG",
                    frameCount = 1,
                    focusPowerMode = focusPowerMode,
                    aiSceneName = aiSceneName,
                    imageWidth = bounds.outWidth,
                    imageHeight = bounds.outHeight,
                    exifOrientation = exifOrient,
                    location = location,
                    tapMetered = tapMetered
                )
                val enrichedJpg = ExifProcessor.injectJpegExifBytes(
                    context = context,
                    jpegBytes = jpgBytes,
                    payload = payload,
                    sourceHalJpegBytes = jpgBytes
                )
                val base = saved.firstOrNull()?.displayName?.substringBeforeLast('.')
                    ?: storageManager.buildUniqueFilename(settings.filenamePrefix, "RAW", "jpg").substringBeforeLast('.')
                val jpgResult = storageManager.savePhotoBytes(enrichedJpg, "$base.jpg", "image/jpeg", settings, location)
                if (jpgResult != null) saved.add(jpgResult)
            }
        }

        return PhotoCaptureOutcome(saved)
    }

    suspend fun captureMultiFrameComputational(
        caps: CameraDeviceCapabilities,
        controls: ProControlsState,
        yuvCaptureSurface: Surface,
        previewSurface: Surface,
        yuvFrameQueue: ConcurrentLinkedQueue<Bitmap>,
        frameCount: Int,
        mode: AppDestination,
        superResEnabled: Boolean,
        astroMode: Boolean = false,
        settings: UltraCamSettings,
        location: Location?,
        aiProfile: AiProcessingProfile? = null,
        focusPowerMode: Boolean = false,
        liveShadowBias: Float = 0f,
        liveHighlightBias: Float = 0f,
        aiSceneName: String? = null,
        tapMetered: Boolean = false,
        aiAutoWb: WhiteBalancePreset? = null,
        onStageUpdate: (String) -> Unit
    ): PhotoCaptureOutcome {
        val device = sessionManager.cameraDevice ?: return PhotoCaptureOutcome(emptyList(), "Camera not ready")
        yuvFrameQueue.clear()

        val builders = ArrayList<CaptureRequest.Builder>(frameCount)
        val evSteps = when (mode) {
            AppDestination.HDR -> {
                val minEv = caps.aeCompensationRange.first
                val maxEv = caps.aeCompensationRange.last
                val span = maxOf(2, minOf(6, (maxEv - minEv) / 2))
                if (frameCount >= 5) {
                    val midSpan = (span / 2).coerceAtLeast(1)
                    listOf(
                        (-span).coerceAtLeast(minEv),
                        (-midSpan).coerceAtLeast(minEv),
                        0,
                        midSpan.coerceAtMost(maxEv),
                        span.coerceAtMost(maxEv)
                    )
                } else {
                    listOf((-span).coerceAtLeast(minEv), 0, span.coerceAtMost(maxEv))
                }
            }
            else -> List(frameCount) { 0 }
        }

        for (i in evSteps.indices) {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(yuvCaptureSurface)
            b.addTarget(previewSurface)
            val stepControls = if (mode == AppDestination.HDR && !controls.manualExposureEnabled) {
                controls.copy(exposureCompensationSteps = evSteps[i])
            } else controls
            manualController.applyToBuilder(
                builder = b,
                caps = caps,
                controls = stepControls,
                isVideoMode = false,
                eisEnabled = false,
                aiProfile = aiProfile,
                focusPowerMode = focusPowerMode || astroMode,
                aiAutoWb = aiAutoWb
            )
            builders.add(b)
        }

        onStageUpdate("CAPTURING")
        val burstResults = sessionManager.captureBurst(builders).getOrElse {
            return PhotoCaptureOutcome(emptyList(), it.message ?: "Burst failed")
        }
        val refCaptureResult = burstResults.getOrNull(burstResults.size / 2) ?: burstResults.firstOrNull()

        val captured = collectBitmaps(yuvFrameQueue, evSteps.size)
        if (captured.isEmpty()) {
            return PhotoCaptureOutcome(emptyList(), "No YUV frames received")
        }

        return withContext(Dispatchers.Default) {
            var warning: String? = null
            var info: String? = null
            var outputBmp: Bitmap = try {
                when {
                    mode == AppDestination.HDR -> {
                        onStageUpdate("MERGING_HDR")
                        val res = compEngine.hdrProcessor.processHdr(
                            frames = captured,
                            fullPower = focusPowerMode,
                            vibranceBoost = (aiProfile?.vibranceBoost ?: settings.libPatcher.vibrance),
                            toneStyle = settings.libPatcher.hdrToneStyle,
                            shadowBias = liveShadowBias,
                            highlightBias = liveHighlightBias
                        )
                        if (res.isUnstableCapture) warning = "unstable"
                        info = "HDR ${res.alignedFrameCount}F | ${settings.libPatcher.colorProfile.badge}"
                        compEngine.finishComputationalFrame(
                            src = res.bitmap,
                            sharpnessAmount = settings.libPatcher.sharpness,
                            aiProfile = aiProfile,
                            fullPower = focusPowerMode,
                            libPatcher = settings.libPatcher,
                            liveShadowBias = liveShadowBias,
                            liveHighlightBias = liveHighlightBias
                        ).also { if (it !== res.bitmap) res.bitmap.recycle() }
                    }
                    mode == AppDestination.NIGHT || astroMode -> {
                        onStageUpdate("MERGING_NIGHT")
                        val res = compEngine.nightProcessor.processNight(
                            frames = captured,
                            fullPower = focusPowerMode,
                            astroMode = astroMode
                        )
                        if (res.isUnstableCapture) warning = "unstable"
                        info = when {
                            astroMode -> "ASTRO:${res.usedFrames}F | ${settings.libPatcher.colorProfile.badge}"
                            res.isTripodDetected -> "TRIPOD:${res.usedFrames}F | ${settings.libPatcher.colorProfile.badge}"
                            else -> "HANDHELD:${res.usedFrames}F | ${settings.libPatcher.colorProfile.badge}"
                        }
                        compEngine.finishComputationalFrame(
                            src = res.bitmap,
                            sharpnessAmount = settings.libPatcher.sharpness,
                            aiProfile = aiProfile,
                            fullPower = focusPowerMode || astroMode,
                            libPatcher = settings.libPatcher,
                            liveShadowBias = liveShadowBias,
                            liveHighlightBias = liveHighlightBias
                        ).also { if (it !== res.bitmap) res.bitmap.recycle() }
                    }
                    else -> {
                        if (superResEnabled) {
                            onStageUpdate("SUPER_RES")
                            val sr = compEngine.superResolutionProcessor.processSuperResolution(
                                frames = captured,
                                fullPower = focusPowerMode
                            )
                            info = "${sr.statusReason} | ${settings.libPatcher.colorProfile.badge}"
                            compEngine.finishComputationalFrame(
                                src = sr.bitmap,
                                sharpnessAmount = settings.libPatcher.sharpness,
                                aiProfile = aiProfile,
                                fullPower = focusPowerMode,
                                libPatcher = settings.libPatcher,
                                liveShadowBias = liveShadowBias,
                                liveHighlightBias = liveHighlightBias
                            ).also { if (it !== sr.bitmap) sr.bitmap.recycle() }
                        } else {
                            info = settings.libPatcher.colorProfile.badge
                            compEngine.refineStandardPhoto(
                                src = captured[0],
                                aiProfile = aiProfile,
                                fullPower = focusPowerMode,
                                libPatcher = settings.libPatcher,
                                liveShadowBias = liveShadowBias,
                                liveHighlightBias = liveHighlightBias
                            ).processedBitmap
                        }
                    }
                }
            } finally {
                captured.forEach { if (!it.isRecycled) it.recycle() }
            }

            if (settings.watermarkEnabled) {
                val ap = caps.apertures.firstOrNull() ?: 1.8f
                val stamped = stampWatermark(
                    src = outputBmp,
                    text = settings.watermarkText,
                    style = settings.watermarkStyle,
                    exifLine = formatExifBadge(ap, controls.exposureTimeNs, controls.iso, settings.libPatcher.colorProfile.badge)
                )
                if (stamped !== outputBmp) outputBmp.recycle()
                outputBmp = stamped
            }

            val outW = outputBmp.width
            val outH = outputBmp.height
            val outJpegQuality = if (focusPowerMode) maxOf(controls.jpegQuality, 98) else controls.jpegQuality
            val rawBytes = encodeBitmapJpeg(outputBmp, outJpegQuality)
            outputBmp.recycle()

            val tag = when {
                astroMode -> "ASTRO"
                mode == AppDestination.HDR -> "HDR"
                mode == AppDestination.NIGHT -> "NIGHT"
                superResEnabled -> "SR"
                else -> "PHOTO"
            }
            val exifPayload = ExifProcessor.buildPayload(
                caps = caps,
                controls = controls,
                settings = settings,
                captureResult = refCaptureResult,
                modeTag = tag,
                frameCount = captured.size,
                focusPowerMode = focusPowerMode || astroMode,
                aiSceneName = aiSceneName,
                imageWidth = outW,
                imageHeight = outH,
                exifOrientation = ExifInterface.ORIENTATION_NORMAL,
                location = location,
                tapMetered = tapMetered
            )
            val bytes = ExifProcessor.injectJpegExifBytes(
                context = context,
                jpegBytes = rawBytes,
                payload = exifPayload
            )
            val name = storageManager.buildUniqueFilename(settings.filenamePrefix, tag, "jpg")
            val saved = storageManager.savePhotoBytes(bytes, name, "image/jpeg", settings, location)
            val fullInfo = if (focusPowerMode && info != null) "$info · 100%" else info
            PhotoCaptureOutcome(listOfNotNull(saved), statusWarning = warning, detailInfo = fullInfo)
        }
    }

    suspend fun stitchAndSavePanorama(
        frames: List<Bitmap>,
        caps: CameraDeviceCapabilities?,
        controls: ProControlsState,
        jpegQuality: Int,
        settings: UltraCamSettings,
        location: Location?,
        tapMetered: Boolean = false
    ): PhotoCaptureOutcome = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext PhotoCaptureOutcome(emptyList(), "No panorama frames")
        var stitched = compEngine.panoramaEngine.stitchHorizontal(frames)
        if (settings.watermarkEnabled) {
            val stamped = stampWatermark(
                src = stitched,
                text = settings.watermarkText,
                style = settings.watermarkStyle,
                exifLine = "PANO · ${settings.libPatcher.colorProfile.badge}"
            )
            if (stamped !== stitched) stitched.recycle()
            stitched = stamped
        }
        val outW = stitched.width
        val outH = stitched.height
        val rawBytes = encodeBitmapJpeg(stitched, jpegQuality)
        stitched.recycle()
        val finalBytes = if (caps != null) {
            val payload = ExifProcessor.buildPayload(
                caps = caps,
                controls = controls,
                settings = settings,
                captureResult = null,
                modeTag = "PANO",
                frameCount = frames.size,
                focusPowerMode = settings.focusPowerMode,
                aiSceneName = "LANDSCAPE",
                imageWidth = outW,
                imageHeight = outH,
                exifOrientation = ExifInterface.ORIENTATION_NORMAL,
                location = location,
                tapMetered = tapMetered
            )
            ExifProcessor.injectJpegExifBytes(context, rawBytes, payload)
        } else rawBytes
        val name = storageManager.buildUniqueFilename(settings.filenamePrefix, "PANO", "jpg")
        val saved = storageManager.savePhotoBytes(finalBytes, name, "image/jpeg", settings, location)
        PhotoCaptureOutcome(listOfNotNull(saved))
    }

    fun stampWatermark(
        src: Bitmap,
        text: String,
        style: WatermarkStyle = WatermarkStyle.PRO_FILM_FRAME,
        exifLine: String = ""
    ): Bitmap {
        if (style == WatermarkStyle.TEXT_CORNER) {
            val copy = src.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(copy)
            val textSize = (minOf(copy.width, copy.height) * 0.026f).coerceIn(22f, 64f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(225, 245, 158, 11)
                this.textSize = textSize
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }
            val pad = textSize * 1.1f
            canvas.drawText(text, pad, copy.height - pad, paint)
            return copy
        }

        // Flagship Leica / Hasselblad-style Gallery Film Frame footer
        val footerH = (minOf(src.width, src.height) * 0.095f).toInt().coerceIn(72, 220)
        val out = Bitmap.createBitmap(src.width, src.height + footerH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, null)

        val bgPaint = Paint().apply { color = Color.rgb(245, 244, 240) }
        canvas.drawRect(0f, src.height.toFloat(), src.width.toFloat(), out.height.toFloat(), bgPaint)

        val padX = footerH * 0.45f
        val centerY = src.height + footerH * 0.56f
        val titleSize = (footerH * 0.28f).coerceIn(18f, 52f)
        val subSize = (footerH * 0.21f).coerceIn(14f, 40f)

        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(217, 56, 30) }
        val dotR = titleSize * 0.28f
        canvas.drawCircle(padX + dotR, centerY - titleSize * 0.28f, dotR, accentPaint)

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 22, 26)
            this.textSize = titleSize
            isFakeBoldText = true
        }
        canvas.drawText(text, padX + dotR * 3.2f, centerY - titleSize * 0.08f, brandPaint)

        if (exifLine.isNotBlank()) {
            val exifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(92, 96, 104)
                this.textSize = subSize
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(exifLine, src.width - padX, centerY, exifPaint)
        }
        return out
    }

    private fun formatExifBadge(aperture: Float, expNs: Long, iso: Int, colorBadge: String): String {
        val shutter = if (expNs <= 0L) "Auto" else if (expNs >= 400_000_000L) {
            "${"%.1f".format(expNs / 1_000_000_000.0)}s"
        } else {
            "1/${(1_000_000_000.0 / expNs).toInt().coerceAtLeast(1)}s"
        }
        return "f/${"%.1f".format(aperture)} · $shutter · ISO $iso · $colorBadge"
    }

    private fun encodeBitmapJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(60, 100), out)
        return out.toByteArray()
    }

    private suspend fun awaitJpegBytes(
        queue: ConcurrentLinkedQueue<Pair<Long, ByteArray>>,
        targetTs: Long
    ): ByteArray? = withTimeoutOrNull(3500L) {
        while (true) {
            val match = queue.firstOrNull { it.first == targetTs } ?: queue.poll()
            if (match != null) return@withTimeoutOrNull match.second
            kotlinx.coroutines.delay(25L)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private suspend fun awaitRawImage(
        queue: ConcurrentLinkedQueue<Image>,
        targetTs: Long
    ): Image? = withTimeoutOrNull(4000L) {
        while (true) {
            val img = queue.poll()
            if (img != null) {
                if (img.timestamp == targetTs || targetTs == 0L) return@withTimeoutOrNull img
                else return@withTimeoutOrNull img
            }
            kotlinx.coroutines.delay(25L)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private suspend fun collectBitmaps(
        queue: ConcurrentLinkedQueue<Bitmap>,
        expected: Int
    ): List<Bitmap> {
        val list = ArrayList<Bitmap>(expected)
        withTimeoutOrNull(5500L) {
            while (list.size < expected) {
                val bmp = queue.poll()
                if (bmp != null) list.add(bmp)
                else kotlinx.coroutines.delay(30L)
            }
        }
        return list
    }

    private fun drainRawQueue(queue: ConcurrentLinkedQueue<Image>) {
        while (true) {
            val img = queue.poll() ?: break
            img.close()
        }
    }
}
