package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.util.Range
import android.util.Size
import android.view.Surface
import com.example.processing.AIPhotographyEngine
import com.example.processing.AiRecommendation
import com.example.processing.ComputationalPhotographyEngine
import com.example.processing.MlKitSceneClassifier
import com.example.processing.QrScanResult
import com.example.processing.QrScannerEngine
import com.example.processing.SceneLabelObservation
import com.example.processing.YuvUtils
import com.example.system.StorageManager
import com.example.system.ThermalMonitor
import com.example.system.UltraCamSettings
import com.example.video.VideoRecordingConfig
import com.example.video.VideoRecordingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CaptureFeedback(
    val message: String? = null,
    val isWarning: Boolean = false,
    val savedUri: android.net.Uri? = null
)

class CameraController(
    private val context: Context,
    private val hardwareScanner: CameraHardwareScanner,
    private val sessionManager: CameraSessionManager,
    private val manualController: ManualCameraController,
    private val rawCaptureEngine: RawCaptureEngine,
    private val compEngine: ComputationalPhotographyEngine,
    private val aiEngine: AIPhotographyEngine,
    private val qrEngine: QrScannerEngine,
    val videoEngine: VideoRecordingEngine,
    private val storageManager: StorageManager,
    private val thermalMonitor: ThermalMonitor
) : SensorEventListener {

    private val photoEngine = PhotoCaptureEngine(
        context = context,
        sessionManager = sessionManager,
        manualController = manualController,
        rawCaptureEngine = rawCaptureEngine,
        compEngine = compEngine,
        storageManager = storageManager
    )

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val _inventory = MutableStateFlow<HardwareInventory?>(null)
    val inventory: StateFlow<HardwareInventory?> = _inventory.asStateFlow()

    private val _activeCamera = MutableStateFlow<CameraDeviceCapabilities?>(null)
    val activeCamera: StateFlow<CameraDeviceCapabilities?> = _activeCamera.asStateFlow()

    private val _previewSize = MutableStateFlow(Size(1440, 1080))
    val previewSize: StateFlow<Size> = _previewSize.asStateFlow()

    private val _telemetry = MutableStateFlow(LiveTelemetry())
    val telemetry: StateFlow<LiveTelemetry> = _telemetry.asStateFlow()

    private val _peakingOverlay = MutableStateFlow<Bitmap?>(null)
    val peakingOverlay: StateFlow<Bitmap?> = _peakingOverlay.asStateFlow()

    private val _zebraOverlay = MutableStateFlow<Bitmap?>(null)
    val zebraOverlay: StateFlow<Bitmap?> = _zebraOverlay.asStateFlow()

    private val _aiRecommendation = MutableStateFlow<AiRecommendation?>(null)
    val aiRecommendation: StateFlow<AiRecommendation?> = _aiRecommendation.asStateFlow()

    private val _qrResult = MutableStateFlow<QrScanResult?>(null)
    val qrResult: StateFlow<QrScanResult?> = _qrResult.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _processingStage = MutableStateFlow<String?>(null)
    val processingStage: StateFlow<String?> = _processingStage.asStateFlow()

    private val _burstProgress = MutableStateFlow<BurstCaptureProgress?>(null)
    val burstProgress: StateFlow<BurstCaptureProgress?> = _burstProgress.asStateFlow()

    private val _countdownSec = MutableStateFlow(0)
    val countdownSec: StateFlow<Int> = _countdownSec.asStateFlow()

    private val _panoramaFramesCount = MutableStateFlow(0)
    val panoramaFramesCount: StateFlow<Int> = _panoramaFramesCount.asStateFlow()

    private val _intervalProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val intervalProgress: StateFlow<Pair<Int, Int>?> = _intervalProgress.asStateFlow()

    private val _captureFeedback = MutableStateFlow<CaptureFeedback?>(null)
    val captureFeedback: StateFlow<CaptureFeedback?> = _captureFeedback.asStateFlow()

    private val _focusPointNormalized = MutableStateFlow<Pair<Float, Float>?>(null)
    val focusPointNormalized: StateFlow<Pair<Float, Float>?> = _focusPointNormalized.asStateFlow()

    private val _liveShadowBias = MutableStateFlow(0f)
    val liveShadowBias: StateFlow<Float> = _liveShadowBias.asStateFlow()

    private val _liveHighlightBias = MutableStateFlow(0f)
    val liveHighlightBias: StateFlow<Float> = _liveHighlightBias.asStateFlow()

    fun setLiveToneBiases(shadowBias: Float, highlightBias: Float) {
        _liveShadowBias.value = shadowBias.coerceIn(-0.25f, 0.35f)
        _liveHighlightBias.value = highlightBias.coerceIn(-0.35f, 0.25f)
    }

    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var analysisYuvReader: ImageReader? = null
    private var stillJpegReader: ImageReader? = null
    private var stillRawReader: ImageReader? = null
    private var compYuvReader: ImageReader? = null

    private val jpegQueue = ConcurrentLinkedQueue<Pair<Long, ByteArray>>()
    private val rawQueue = ConcurrentLinkedQueue<Image>()
    private val compYuvQueue = ConcurrentLinkedQueue<Bitmap>()
    private val panoramaFrames = mutableListOf<Bitmap>()

    private var currentMode: AppDestination = AppDestination.CAMERA
    private var currentControls: ProControlsState = ProControlsState()
    private var currentSettings: UltraCamSettings = UltraCamSettings()
    private var currentToolMode: PhotoToolMode = PhotoToolMode.STANDARD
    private var currentEisEnabled: Boolean = true
    private var currentVideoConfig: VideoRecordingConfig? = null

    private val mlKitClassifier = MlKitSceneClassifier()

    private var lastAnalysisTimeMs = 0L
    private var lastMlKitTimeMs = 0L
    @Volatile
    private var latestMlKitLabels: List<SceneLabelObservation> = emptyList()
    @Volatile
    private var latestMlKitTimestampMs: Long = 0L
    private var lastIspSyncMs = 0L
    private var lastQrTimeMs = 0L
    private var lastSensorRotationDeg = 0
    private var rollDegrees = 0f
    private var pitchDegrees = 0f
    private var intervalJob: Job? = null
    @Volatile
    private var holdBurstActive = false
    private var holdBurstJob: Job? = null

    private var scopeRef: CoroutineScope? = null
    private val sessionMutex = Mutex()
    private val readerLock = Any()
    @Volatile
    private var previewTextureValid = false

    suspend fun scanHardwareAndSelectDefault(): HardwareInventory {
        val scanned = hardwareScanner.scanAllHardware()
        _inventory.value = scanned
        if (_activeCamera.value == null) {
            _activeCamera.value = scanned.mainRearCamera ?: scanned.cameras.firstOrNull { it.isUsableInApp }
        } else {
            val existingId = _activeCamera.value?.cameraId
            _activeCamera.value = scanned.cameras.firstOrNull { it.cameraId == existingId }
                ?: scanned.mainRearCamera
        }
        return scanned
    }

    fun startSensors() {
        val rotSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (rotSensor != null) {
            sensorManager?.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopSensors() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotMat = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                val orient = FloatArray(3)
                SensorManager.getOrientation(rotMat, orient)
                pitchDegrees = Math.toDegrees(orient[1].toDouble()).toFloat()
                rollDegrees = Math.toDegrees(orient[2].toDouble()).toFloat()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val norm = sqrt(x * x + y * y + z * z).coerceAtLeast(0.1f)
                rollDegrees = Math.toDegrees(atan2(-x.toDouble(), y.toDouble())).toFloat()
                pitchDegrees = Math.toDegrees(atan2(z.toDouble(), norm.toDouble())).toFloat()
            }
        }
        lastSensorRotationDeg = when {
            abs(rollDegrees) in 45f..135f -> if (rollDegrees > 0) 90 else 270
            abs(rollDegrees) > 135f -> 180
            else -> 0
        }
        val isLevel = abs(rollDegrees % 90f) < 1.8f || abs(abs(rollDegrees % 90f) - 90f) < 1.8f
        _telemetry.value = _telemetry.value.copy(
            rollDegrees = rollDegrees,
            pitchDegrees = pitchDegrees,
            isLevel = isLevel
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun isTextureReleased(tex: SurfaceTexture?): Boolean {
        if (tex == null) return true
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            tex.isReleased
        } else false
    }

    fun hasValidPreviewTexture(): Boolean =
        previewTextureValid && !isTextureReleased(previewSurfaceTexture)

    suspend fun ensureSessionIfReady() {
        if (hasValidPreviewTexture() && sessionManager.captureSession == null) {
            openAndConfigureSession()
        }
    }

    fun attachPreviewTexture(
        texture: SurfaceTexture,
        scope: CoroutineScope,
        mode: AppDestination,
        controls: ProControlsState,
        settings: UltraCamSettings,
        toolMode: PhotoToolMode,
        eisEnabled: Boolean
    ) {
        previewSurfaceTexture = texture
        previewTextureValid = !isTextureReleased(texture)
        scopeRef = scope
        currentMode = mode
        currentControls = controls
        currentSettings = settings
        currentToolMode = toolMode
        currentEisEnabled = eisEnabled
        scope.launch {
            if (_inventory.value == null) scanHardwareAndSelectDefault()
            openAndConfigureSession()
        }
    }

    fun detachPreviewTexture(texture: SurfaceTexture) {
        if (previewSurfaceTexture === texture) {
            previewTextureValid = false
            previewSurfaceTexture = null
            sessionManager.closeSessionOnly()
            closeReaders()
            runCatching { previewSurface?.release() }
            previewSurface = null
        }
    }

    suspend fun switchLens(role: LensRole) {
        val inv = _inventory.value ?: scanHardwareAndSelectDefault()
        val target = when (role) {
            LensRole.MAIN -> inv.mainRearCamera
            LensRole.MACRO -> inv.macroCamera
            LensRole.FRONT -> inv.frontCamera
            LensRole.AUXILIARY -> inv.cameras.firstOrNull { it.isUsableInApp }
        } ?: return

        if (target.cameraId == _activeCamera.value?.cameraId) return
        _activeCamera.value = target
        openAndConfigureSession()
    }

    suspend fun selectCameraById(cameraId: String) {
        val inv = _inventory.value ?: return
        val target = inv.cameras.firstOrNull { it.cameraId == cameraId && it.isUsableInApp } ?: return
        _activeCamera.value = target
        openAndConfigureSession()
    }

    suspend fun reconfigureForState(
        mode: AppDestination,
        controls: ProControlsState,
        settings: UltraCamSettings,
        toolMode: PhotoToolMode,
        eisEnabled: Boolean,
        recreateSession: Boolean = false
    ) {
        val formatChanged = currentControls.captureFormat != controls.captureFormat ||
            currentControls.selectedResolution != controls.selectedResolution
        val powerOrSuperChanged = currentSettings.focusPowerMode != settings.focusPowerMode ||
            currentSettings.superResolutionEnabled != settings.superResolutionEnabled
        val modeGroupChanged = (currentMode == AppDestination.SLOW_MOTION) != (mode == AppDestination.SLOW_MOTION) ||
            (currentMode in listOf(AppDestination.HDR, AppDestination.NIGHT)) !=
            (mode in listOf(AppDestination.HDR, AppDestination.NIGHT)) ||
            (currentToolMode == PhotoToolMode.ASTRO) != (toolMode == PhotoToolMode.ASTRO)

        currentMode = mode
        currentControls = controls
        currentSettings = settings
        currentToolMode = toolMode
        currentEisEnabled = eisEnabled

        if (!hasValidPreviewTexture()) return

        if (recreateSession || formatChanged || modeGroupChanged || powerOrSuperChanged) {
            openAndConfigureSession()
        } else {
            updateRepeatingRequest()
        }
    }

    suspend fun openAndConfigureSession(videoInputSurface: Surface? = null, highSpeedConfig: HighSpeedConfig? = null) {
        sessionMutex.withLock {
            val caps = _activeCamera.value ?: return
            val tex = previewSurfaceTexture ?: return
            if (!previewTextureValid || isTextureReleased(tex)) {
                previewTextureValid = false
                previewSurfaceTexture = null
                return
            }

            sessionManager.closeSessionOnly()
            closeReaders()
            val openRes = sessionManager.openCamera(caps.cameraId)
            if (openRes.isFailure) {
                _captureFeedback.value = CaptureFeedback(openRes.exceptionOrNull()?.message, isWarning = true)
                return
            }

            // Re-verify SurfaceTexture did not get abandoned while awaiting camera open
            if (!previewTextureValid || previewSurfaceTexture !== tex || isTextureReleased(tex)) {
                return
            }

            val isVideo = currentMode == AppDestination.VIDEO || currentMode == AppDestination.SLOW_MOTION
            val desiredPreview = when {
                highSpeedConfig != null -> highSpeedConfig.size
                isVideo -> currentVideoConfig?.size ?: chooseBestPreviewSize(caps.videoSizes.ifEmpty { caps.yuvSizes }, 1920, 1080)
                else -> chooseBestPreviewSize(caps.yuvSizes, 1440, 1080)
            }
            _previewSize.value = desiredPreview

            val prevSurface = runCatching {
                tex.setDefaultBufferSize(desiredPreview.width, desiredPreview.height)
                previewSurface?.release()
                Surface(tex)
            }.getOrNull()

            if (prevSurface == null || !prevSurface.isValid || !previewTextureValid || isTextureReleased(tex)) {
                runCatching { prevSurface?.release() }
                previewSurface = null
                return
            }
            previewSurface = prevSurface

            val surfaces = mutableListOf(prevSurface)
            val handler = sessionManager.getHandler()

        if (highSpeedConfig != null && videoInputSurface != null) {
            surfaces.add(videoInputSurface)
        } else {
            // Lightweight analysis YUV stream (640x480) for zero-lag histograms, focus peaking, zebra, AI, QR
            val analysisSize = chooseBestPreviewSize(caps.yuvSizes, 640, 480)
            val analysisReader = ImageReader.newInstance(
                analysisSize.width,
                analysisSize.height,
                ImageFormat.YUV_420_888,
                2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    synchronized(readerLock) {
                        if (analysisYuvReader !== reader) return@synchronized
                        var img: Image? = null
                        try {
                            img = reader.acquireLatestImage() ?: return@synchronized
                            processLiveAnalysisFrame(img, caps)
                        } catch (_: IllegalStateException) {
                            // Reader or Image closed concurrently during teardown
                        } finally {
                            runCatching { img?.close() }
                        }
                    }
                }, handler)
            }
            analysisYuvReader = analysisReader
            surfaces.add(analysisReader.surface)

            if (videoInputSurface != null) {
                surfaces.add(videoInputSurface)
            } else {
                // Still JPEG reader
                val jpegSize = currentControls.selectedResolution?.takeIf { caps.jpegSizes.contains(it) }
                    ?: caps.jpegSizes.firstOrNull()
                    ?: Size(1920, 1080)
                val jpegReader = ImageReader.newInstance(
                    jpegSize.width,
                    jpegSize.height,
                    ImageFormat.JPEG,
                    if (caps.supportsBurstCapture) 10 else 6
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        synchronized(readerLock) {
                            if (stillJpegReader !== reader) return@synchronized
                            var img: Image? = null
                            try {
                                img = reader.acquireNextImage() ?: return@synchronized
                                val buf = img.planes[0].buffer
                                val bytes = ByteArray(buf.remaining())
                                buf.get(bytes)
                                jpegQueue.offer(img.timestamp to bytes)
                            } catch (_: IllegalStateException) {
                            } finally {
                                runCatching { img?.close() }
                            }
                        }
                    }, handler)
                }
                stillJpegReader = jpegReader
                surfaces.add(jpegReader.surface)

                // Genuine RAW_SENSOR reader if supported and selected
                if (caps.supportsRaw &&
                    (currentControls.captureFormat == CaptureFormat.RAW_DNG ||
                        currentControls.captureFormat == CaptureFormat.RAW_PLUS_JPEG)
                ) {
                    val rawSize = caps.rawSizes.first()
                    val rReader = ImageReader.newInstance(
                        rawSize.width,
                        rawSize.height,
                        ImageFormat.RAW_SENSOR,
                        2
                    ).apply {
                        setOnImageAvailableListener({ reader ->
                            synchronized(readerLock) {
                                if (stillRawReader !== reader) return@synchronized
                                runCatching {
                                    val img = reader.acquireNextImage() ?: return@synchronized
                                    rawQueue.offer(img)
                                }
                            }
                        }, handler)
                    }
                    stillRawReader = rReader
                    surfaces.add(rReader.surface)
                }

                // Computational YUV capture reader (full native YUV in Focus Turbo, bounded 2560x1920 in standard)
                if (currentMode == AppDestination.HDR ||
                    currentMode == AppDestination.NIGHT ||
                    currentToolMode == PhotoToolMode.ASTRO ||
                    currentSettings.superResolutionEnabled ||
                    currentSettings.focusPowerMode
                ) {
                    val compSize = if (currentSettings.focusPowerMode) {
                        chooseBestPreviewSize(caps.yuvSizes, 4096, 3072)
                    } else {
                        chooseBestPreviewSize(caps.yuvSizes, 2560, 1920)
                    }
                    val cReader = ImageReader.newInstance(
                        compSize.width,
                        compSize.height,
                        ImageFormat.YUV_420_888,
                        3
                    ).apply {
                        setOnImageAvailableListener({ reader ->
                            synchronized(readerLock) {
                                if (compYuvReader !== reader) return@synchronized
                                var img: Image? = null
                                try {
                                    img = reader.acquireNextImage() ?: return@synchronized
                                    val rot = photoEngine.computeJpegOrientation(caps, lastSensorRotationDeg)
                                    val maxDim = if (currentSettings.focusPowerMode) 4096 else 2560
                                    val bmp = YuvUtils.yuv420ToBitmap(img, rot, maxDim = maxDim)
                                    compYuvQueue.offer(bmp)
                                } catch (_: IllegalStateException) {
                                } finally {
                                    runCatching { img?.close() }
                                }
                            }
                        }, handler)
                    }
                    compYuvReader = cReader
                    surfaces.add(cReader.surface)
                }
            }
        }

            if (surfaces.any { !it.isValid } || !previewTextureValid || isTextureReleased(tex)) {
                return
            }

            val sessionRes = sessionManager.createSession(surfaces, highSpeedMode = highSpeedConfig != null)
            if (sessionRes.isFailure) {
                _captureFeedback.value = CaptureFeedback(sessionRes.exceptionOrNull()?.message, isWarning = true)
                return
            }
            updateRepeatingRequest(videoInputSurface, highSpeedConfig)
        }
    }

    fun updateRepeatingRequest(
        videoInputSurface: Surface? = null,
        highSpeedConfig: HighSpeedConfig? = null
    ) {
        val device = sessionManager.cameraDevice ?: return
        val caps = _activeCamera.value ?: return
        val prev = previewSurface ?: return
        if (!prev.isValid || !previewTextureValid || isTextureReleased(previewSurfaceTexture)) return

        val template = when {
            videoInputSurface != null -> CameraDevice.TEMPLATE_RECORD
            currentMode == AppDestination.VIDEO || currentMode == AppDestination.SLOW_MOTION ->
                CameraDevice.TEMPLATE_RECORD
            else -> CameraDevice.TEMPLATE_PREVIEW
        }
        val builder = device.createCaptureRequest(template)
        builder.addTarget(prev)
        if (videoInputSurface != null) builder.addTarget(videoInputSurface)
        if (highSpeedConfig == null) {
            analysisYuvReader?.surface?.let { builder.addTarget(it) }
        }

        val activeAi = if (currentSettings.aiAssistantEnabled || currentSettings.focusPowerMode) {
            _aiRecommendation.value
        } else null
        val autoWb = if (currentSettings.aiAssistantEnabled && currentSettings.aiAutoAdjustEnabled) {
            activeAi?.recommendedWb
        } else null

        manualController.applyToBuilder(
            builder = builder,
            caps = caps,
            controls = currentControls,
            isVideoMode = template == CameraDevice.TEMPLATE_RECORD,
            eisEnabled = currentEisEnabled,
            aiProfile = activeAi?.profile,
            focusPowerMode = currentSettings.focusPowerMode,
            aiAutoWb = autoWb
        )

        val fpsRange = highSpeedConfig?.let { Range(it.minFps, it.maxFps) }
            ?: currentVideoConfig?.fps?.let { fps ->
                caps.aeTargetFpsRanges.firstOrNull { it.last >= fps && it.first <= fps }
                    ?.let { Range(it.first, it.last) }
                    ?: Range(fps, fps)
            }

        sessionManager.startRepeating(builder, fpsRange, repeatingCallback)
    }

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val caps = _activeCamera.value ?: return
            val iso = result.get(TotalCaptureResult.SENSOR_SENSITIVITY) ?: _telemetry.value.iso
            val exp = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME) ?: _telemetry.value.exposureTimeNs
            val focus = result.get(TotalCaptureResult.LENS_FOCUS_DISTANCE) ?: _telemetry.value.focusDistanceDiopters
            val aperture = result.get(TotalCaptureResult.LENS_APERTURE) ?: caps.apertures.firstOrNull() ?: 1.8f
            val aeState = result.get(TotalCaptureResult.CONTROL_AE_STATE) ?: 0
            val afState = result.get(TotalCaptureResult.CONTROL_AF_STATE) ?: 0
            val awbState = result.get(TotalCaptureResult.CONTROL_AWB_STATE) ?: 0
            val ts = result.get(TotalCaptureResult.SENSOR_TIMESTAMP) ?: 0L

            val faces = result.get(TotalCaptureResult.STATISTICS_FACES)
            val active = caps.activeArraySize
            val normFaces = if (faces != null && active != null && active.width() > 0 && active.height() > 0) {
                faces.map { face ->
                    val b = face.bounds
                    val l = (b.left - active.left).toFloat() / active.width()
                    val t = (b.top - active.top).toFloat() / active.height()
                    val r = (b.right - active.left).toFloat() / active.width()
                    val bot = (b.bottom - active.top).toFloat() / active.height()
                    mapSensorRectToDisplay(RectF(l, t, r, bot), caps)
                }
            } else emptyList()

            _telemetry.value = _telemetry.value.copy(
                iso = iso,
                exposureTimeNs = exp,
                focusDistanceDiopters = focus,
                lensAperture = aperture,
                aeState = aeState,
                afState = afState,
                awbState = awbState,
                sensorTimestampNs = ts,
                faceCount = normFaces.size,
                faceBoundsNormalized = normFaces
            )
        }
    }

    private fun processLiveAnalysisFrame(image: Image, caps: CameraDeviceCapabilities) {
        val now = System.currentTimeMillis()
        val minInterval = thermalMonitor.healthState.value.previewAnalysisIntervalMs
        if (now - lastAnalysisTimeMs < minInterval) return
        lastAnalysisTimeMs = now

        val displayRot = photoEngine.computeJpegOrientation(caps, 0)
        val isFront = caps.facing == CameraCharacteristics.LENS_FACING_FRONT

        val analysis = YuvUtils.analyzePreviewYuv(
            image = image,
            computePeaking = currentSettings.focusPeakingEnabled,
            computeZebra = currentSettings.zebraWarningsEnabled,
            peakingColorArgb = currentSettings.focusPeakingColor.argb,
            peakingThreshold = currentSettings.focusPeakingSensitivity.threshold,
            displayRotationDeg = displayRot,
            mirrorHorizontal = isFront
        )

        _peakingOverlay.value = analysis.focusPeakingBitmap
        _zebraOverlay.value = analysis.zebraBitmap
        _telemetry.value = _telemetry.value.copy(
            lumaHistogram = analysis.lumaHistogram,
            redHistogram = analysis.redHistogram,
            greenHistogram = analysis.greenHistogram,
            blueHistogram = analysis.blueHistogram,
            highlightClippingPercent = analysis.highlightClipPercent,
            shadowClippingPercent = analysis.shadowClipPercent,
            redHighlightClipPercent = analysis.redHighlightClipPercent,
            greenHighlightClipPercent = analysis.greenHighlightClipPercent,
            blueHighlightClipPercent = analysis.blueHighlightClipPercent,
            focusSharpnessPercent = analysis.focusSharpnessPercent
        )

        if (currentSettings.aiAssistantEnabled || currentSettings.focusPowerMode) {
            // Asynchronous ML Kit Image Labeling classification every ~480ms without blocking camera thread
            if (now - lastMlKitTimeMs > 480L && !_isCapturing.value) {
                lastMlKitTimeMs = now
                val uprightRot = photoEngine.computeJpegOrientation(caps, lastSensorRotationDeg)
                val mlBmp = runCatching { YuvUtils.yuv420ToBitmap(image, uprightRot, maxDim = 320) }.getOrNull()
                if (mlBmp != null) {
                    scopeRef?.launch(Dispatchers.Default) {
                        try {
                            val labels = mlKitClassifier.classifyBitmap(mlBmp)
                            latestMlKitLabels = labels
                            latestMlKitTimestampMs = System.currentTimeMillis()
                        } finally {
                            if (!mlBmp.isRecycled) mlBmp.recycle()
                        }
                    }
                }
            }

            val freshLabels = if (now - latestMlKitTimestampMs < 2600L) latestMlKitLabels else emptyList()
            val t = _telemetry.value
            val prevRec = _aiRecommendation.value
            val prevProfile = prevRec?.profile
            val nextRec = aiEngine.classifyScene(
                analysis = analysis,
                currentIso = t.iso,
                currentExposureNs = t.exposureTimeNs,
                faceCount = t.faceCount,
                focusDistanceDiopters = t.focusDistanceDiopters,
                isoRange = caps.isoRange,
                exposureRangeNs = caps.exposureTimeRangeNs,
                aeCompRange = caps.aeCompensationRange,
                fullPower = currentSettings.focusPowerMode,
                mlKitLabels = freshLabels
            )
            _aiRecommendation.value = nextRec

            val ispChanged = prevProfile == null ||
                prevProfile.ispNrMode != nextRec.profile.ispNrMode ||
                prevProfile.ispEdgeMode != nextRec.profile.ispEdgeMode ||
                (currentSettings.aiAutoAdjustEnabled && prevRec.recommendedWb != nextRec.recommendedWb)
            if (ispChanged && !_isCapturing.value && now - lastIspSyncMs > 1200L) {
                lastIspSyncMs = now
                if (!videoEngine.uiState.value.isRecording) {
                    updateRepeatingRequest()
                }
            }
        } else {
            _aiRecommendation.value = null
            latestMlKitLabels = emptyList()
        }

        if (currentToolMode == PhotoToolMode.QR_SCANNER && now - lastQrTimeMs > 320L) {
            lastQrTimeMs = now
            val rot = photoEngine.computeJpegOrientation(caps, lastSensorRotationDeg)
            val qrBmp = YuvUtils.yuv420ToBitmap(image, rot, maxDim = 640)
            scopeRef?.launch(Dispatchers.Default) {
                val res = qrEngine.scanBitmap(qrBmp)
                qrBmp.recycle()
                if (res != null) _qrResult.value = res
            }
        }
    }

    fun triggerTapToFocus(normX: Float, normY: Float, lockAeAf: Boolean) {
        val device = sessionManager.cameraDevice ?: return
        val session = sessionManager.captureSession ?: return
        val caps = _activeCamera.value ?: return
        val prev = previewSurface ?: return

        _focusPointNormalized.value = normX to normY
        val regions = manualController.computeMeteringRectangles(
            normX, normY, caps, currentControls.digitalZoomRatio
        ) ?: return

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(prev)
        analysisYuvReader?.surface?.let { builder.addTarget(it) }

        val updatedControls = currentControls.copy(
            aeLocked = lockAeAf && caps.supportsAeLock,
            afLocked = lockAeAf && caps.supportsManualFocus
        )
        currentControls = updatedControls
        manualController.applyToBuilder(
            builder = builder,
            caps = caps,
            controls = updatedControls,
            isVideoMode = false,
            eisEnabled = currentEisEnabled,
            aiProfile = if (currentSettings.aiAssistantEnabled) _aiRecommendation.value?.profile else null,
            focusPowerMode = currentSettings.focusPowerMode,
            aiAutoWb = if (currentSettings.aiAssistantEnabled && currentSettings.aiAutoAdjustEnabled) {
                _aiRecommendation.value?.recommendedWb
            } else null
        )

        builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
        if (caps.supportsManualFocus && caps.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        }
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)

        runCatching {
            session.capture(builder.build(), repeatingCallback, sessionManager.getHandler())
            updateRepeatingRequest()
        }
    }

    fun clearQrResult() {
        _qrResult.value = null
    }

    fun clearFeedback() {
        _captureFeedback.value = null
    }

    suspend fun triggerPhotoCapture(
        timerSeconds: Int,
        intervalSec: Int = 3,
        intervalCount: Int = 5,
        onCaptureSaved: () -> Unit
    ) {
        if (_isCapturing.value) return
        val caps = _activeCamera.value ?: return

        if (storageManager.isCriticallyLowStorage()) {
            _captureFeedback.value = CaptureFeedback("Critically low storage (<150MB)", isWarning = true)
            return
        }

        if (timerSeconds > 0) {
            for (s in timerSeconds downTo 1) {
                _countdownSec.value = s
                delay(1000L)
            }
            _countdownSec.value = 0
        }

        if (currentToolMode == PhotoToolMode.INTERVALOMETER) {
            startIntervalometer(intervalSec, intervalCount, onCaptureSaved)
            return
        }

        if (currentToolMode == PhotoToolMode.PANORAMA) {
            capturePanoramaStep(onCaptureSaved)
            return
        }

        _isCapturing.value = true
        try {
            val outcome = executeSingleCaptureFlow(caps)
            handleCaptureOutcome(outcome, onCaptureSaved)
        } finally {
            _isCapturing.value = false
            _processingStage.value = null
        }
    }

    private fun startIntervalometer(intervalSec: Int, count: Int, onCaptureSaved: () -> Unit) {
        intervalJob?.cancel()
        val caps = _activeCamera.value ?: return
        intervalJob = scopeRef?.launch {
            for (i in 1..count) {
                if (!isActive) break
                _intervalProgress.value = i to count
                _isCapturing.value = true
                val outcome = executeSingleCaptureFlow(caps)
                handleCaptureOutcome(outcome, onCaptureSaved)
                _isCapturing.value = false
                if (i < count) delay(intervalSec * 1000L)
            }
            _intervalProgress.value = null
        }
    }

    fun stopIntervalometer() {
        intervalJob?.cancel()
        intervalJob = null
        _intervalProgress.value = null
        _isCapturing.value = false
    }

    fun startHoldActionBurst(onCaptureSaved: () -> Unit) {
        if (_isCapturing.value || holdBurstActive) return
        val caps = _activeCamera.value ?: return
        val jReader = stillJpegReader ?: return
        if (storageManager.isCriticallyLowStorage()) {
            _captureFeedback.value = CaptureFeedback("Critically low storage (<150MB)", isWarning = true)
            return
        }
        holdBurstActive = true
        holdBurstJob = scopeRef?.launch {
            _isCapturing.value = true
            try {
                val turbo = currentSettings.focusPowerMode
                val health = thermalMonitor.sampleNow(currentSettings.thermalProtectionEnabled, turbo)
                val maxFrames = if (currentMode == AppDestination.PRO) {
                    currentControls.burstFrameCount.coerceIn(3, 12)
                } else {
                    health.recommendedMaxBurstFrames.coerceIn(4, if (turbo) 12 else 8)
                }
                val location = if (currentSettings.gpsExifEnabled) storageManager.getLastKnownCoarseLocation() else null
                val activeRec = if (currentSettings.aiAssistantEnabled || turbo) _aiRecommendation.value else null
                val activeProfile = activeRec?.profile
                val autoWb = if (currentSettings.aiAssistantEnabled && currentSettings.aiAutoAdjustEnabled) {
                    activeRec?.recommendedWb
                } else null
                val outcome = photoEngine.captureContinuousHoldBurst(
                    caps = caps,
                    controls = currentControls,
                    jpegReader = jReader,
                    previewSurface = previewSurface,
                    jpegQueue = jpegQueue,
                    deviceRotationDeg = lastSensorRotationDeg,
                    maxFrames = maxFrames,
                    settings = currentSettings,
                    location = location,
                    aiProfile = activeProfile,
                    focusPowerMode = turbo,
                    liveShadowBias = _liveShadowBias.value,
                    liveHighlightBias = _liveHighlightBias.value,
                    aiSceneName = _aiRecommendation.value?.scene?.name,
                    tapMetered = _focusPointNormalized.value != null,
                    aiAutoWb = autoWb,
                    isHoldActive = { holdBurstActive },
                    onProgress = { prog -> _burstProgress.value = prog }
                )
                handleCaptureOutcome(outcome, onCaptureSaved)
            } finally {
                holdBurstActive = false
                _burstProgress.value = null
                _isCapturing.value = false
            }
        }
    }

    fun stopHoldActionBurst() {
        holdBurstActive = false
    }

    private suspend fun capturePanoramaStep(onCaptureSaved: () -> Unit) {
        val caps = _activeCamera.value ?: return
        val jReader = stillJpegReader ?: return
        _isCapturing.value = true
        try {
            val device = sessionManager.cameraDevice ?: return
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(jReader.surface)
            manualController.applyToBuilder(
                builder = builder,
                caps = caps,
                controls = currentControls,
                isVideoMode = false,
                eisEnabled = false,
                aiProfile = if (currentSettings.aiAssistantEnabled) _aiRecommendation.value?.profile else null,
                focusPowerMode = currentSettings.focusPowerMode
            )
            builder.set(CaptureRequest.JPEG_ORIENTATION, photoEngine.computeJpegOrientation(caps, lastSensorRotationDeg))
            jpegQueue.clear()
            sessionManager.captureSingle(builder).getOrNull() ?: return
            val bytes = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                while (jpegQueue.isEmpty()) delay(20L)
                jpegQueue.poll()?.second
            } ?: return
            val bmp = YuvUtils.decodeJpegDownsampled(bytes, maxDim = 1280)
            panoramaFrames.add(bmp)
            _panoramaFramesCount.value = panoramaFrames.size
            if (panoramaFrames.size >= 5) {
                finishPanorama(onCaptureSaved)
            }
        } finally {
            _isCapturing.value = false
        }
    }

    suspend fun finishPanorama(onCaptureSaved: () -> Unit) {
        if (panoramaFrames.isEmpty()) return
        _isCapturing.value = true
        _processingStage.value = "STITCHING"
        try {
            val location = if (currentSettings.gpsExifEnabled) storageManager.getLastKnownCoarseLocation() else null
            val outcome = photoEngine.stitchAndSavePanorama(
                frames = panoramaFrames.toList(),
                caps = _activeCamera.value,
                controls = currentControls,
                jpegQuality = currentControls.jpegQuality,
                settings = currentSettings,
                location = location,
                tapMetered = _focusPointNormalized.value != null
            )
            panoramaFrames.forEach { if (!it.isRecycled) it.recycle() }
            panoramaFrames.clear()
            _panoramaFramesCount.value = 0
            handleCaptureOutcome(outcome, onCaptureSaved)
        } finally {
            _isCapturing.value = false
            _processingStage.value = null
        }
    }

    private suspend fun executeSingleCaptureFlow(caps: CameraDeviceCapabilities): PhotoCaptureOutcome {
        val location = if (currentSettings.gpsExifEnabled) storageManager.getLastKnownCoarseLocation() else null
        val turbo = currentSettings.focusPowerMode
        val health = thermalMonitor.sampleNow(currentSettings.thermalProtectionEnabled, turbo)
        val activeRec = if (currentSettings.aiAssistantEnabled || turbo) {
            _aiRecommendation.value
        } else null
        val activeProfile = activeRec?.profile
        val autoWb = if (currentSettings.aiAssistantEnabled && currentSettings.aiAutoAdjustEnabled) {
            activeRec?.recommendedWb
        } else null

        val shBias = _liveShadowBias.value
        val hiBias = _liveHighlightBias.value
        val aiSceneName = _aiRecommendation.value?.scene?.name
        val tapMetered = _focusPointNormalized.value != null

        // 1. Exposure Bracketing Tool (saves 3 separate frames -EV, 0, +EV)
        if (currentToolMode == PhotoToolMode.BRACKETING && stillJpegReader != null) {
            val jReader = stillJpegReader!!
            val span = maxOf(2, minOf(6, (caps.aeCompensationRange.last - caps.aeCompensationRange.first) / 2))
            val offsets = listOf(
                (-span).coerceAtLeast(caps.aeCompensationRange.first),
                0,
                span.coerceAtMost(caps.aeCompensationRange.last)
            )
            val savedAll = mutableListOf<com.example.system.SavedMediaResult>()
            for ((idx, ev) in offsets.withIndex()) {
                val res = photoEngine.captureSingleJpeg(
                    caps = caps,
                    controls = currentControls.copy(exposureCompensationSteps = ev),
                    jpegReader = jReader,
                    jpegQueue = jpegQueue,
                    deviceRotationDeg = lastSensorRotationDeg,
                    settings = currentSettings,
                    location = location,
                    modeTag = "AEB${idx + 1}",
                    aiProfile = activeProfile,
                    focusPowerMode = turbo,
                    liveShadowBias = shBias,
                    liveHighlightBias = hiBias,
                    aiSceneName = aiSceneName,
                    tapMetered = tapMetered,
                    aiAutoWb = autoWb
                )
                savedAll.addAll(res.savedResults)
            }
            return PhotoCaptureOutcome(savedAll, detailInfo = "AEB 3 frames saved")
        }

        // 2. High-Speed Camera2 Continuous Burst (Pro Burst Drive OR Camera Burst Tool)
        val useProBurst = currentMode == AppDestination.PRO && currentControls.burstEnabledInPro
        if ((currentToolMode == PhotoToolMode.BURST || useProBurst) && stillJpegReader != null) {
            val jReader = stillJpegReader!!
            val requestedCount = if (useProBurst) {
                currentControls.burstFrameCount.coerceIn(3, 12)
            } else {
                val maxBurst = if (turbo) 10 else 6
                health.recommendedMaxBurstFrames.coerceIn(3, maxBurst)
            }
            return try {
                photoEngine.captureHighSpeedBurst(
                    caps = caps,
                    controls = currentControls,
                    jpegReader = jReader,
                    previewSurface = previewSurface,
                    jpegQueue = jpegQueue,
                    deviceRotationDeg = lastSensorRotationDeg,
                    frameCount = requestedCount,
                    settings = currentSettings,
                    location = location,
                    aiProfile = activeProfile,
                    focusPowerMode = turbo,
                    liveShadowBias = shBias,
                    liveHighlightBias = hiBias,
                    aiSceneName = aiSceneName,
                    tapMetered = tapMetered,
                    aiAutoWb = autoWb,
                    onProgress = { prog -> _burstProgress.value = prog }
                )
            } finally {
                _burstProgress.value = null
            }
        }

        // 3. RAW or RAW+JPEG in Pro mode
        if ((currentMode == AppDestination.PRO || currentMode == AppDestination.CAMERA) &&
            (currentControls.captureFormat == CaptureFormat.RAW_DNG ||
                currentControls.captureFormat == CaptureFormat.RAW_PLUS_JPEG) &&
            stillRawReader != null
        ) {
            return photoEngine.captureRawOrDual(
                caps = caps,
                controls = currentControls,
                jpegReader = stillJpegReader,
                rawReader = stillRawReader!!,
                jpegQueue = jpegQueue,
                rawQueue = rawQueue,
                deviceRotationDeg = lastSensorRotationDeg,
                settings = currentSettings,
                location = location,
                alsoSaveJpeg = currentControls.captureFormat == CaptureFormat.RAW_PLUS_JPEG,
                aiSceneName = aiSceneName,
                focusPowerMode = turbo,
                tapMetered = tapMetered
            )
        }

        // 4. Computational Multi-frame (HDR / Night / Astro / Super Resolution / Focus Turbo 100%)
        val isAstro = currentMode == AppDestination.CAMERA && currentToolMode == PhotoToolMode.ASTRO
        val effectiveSuperRes = (currentSettings.superResolutionEnabled || turbo) && health.allowSuperResolution && !isAstro
        val useCompYuv = compYuvReader != null && previewSurface != null &&
            (currentMode == AppDestination.HDR ||
                currentMode == AppDestination.NIGHT ||
                isAstro ||
                (currentMode == AppDestination.CAMERA &&
                    currentToolMode == PhotoToolMode.STANDARD &&
                    effectiveSuperRes))

        if (useCompYuv) {
            val frameCount = when {
                isAstro -> (health.recommendedNightFrames + 2).coerceIn(8, 12)
                currentMode == AppDestination.HDR -> health.recommendedHdrFrames
                currentMode == AppDestination.NIGHT -> health.recommendedNightFrames
                else -> if (turbo) health.recommendedMaxBurstFrames.coerceIn(5, 6)
                else health.recommendedMaxBurstFrames.coerceIn(3, 5)
            }
            return photoEngine.captureMultiFrameComputational(
                caps = caps,
                controls = currentControls,
                yuvCaptureSurface = compYuvReader!!.surface,
                previewSurface = previewSurface!!,
                yuvFrameQueue = compYuvQueue,
                frameCount = frameCount,
                mode = currentMode,
                superResEnabled = effectiveSuperRes,
                astroMode = isAstro,
                settings = currentSettings,
                location = location,
                aiProfile = activeProfile,
                focusPowerMode = turbo,
                liveShadowBias = shBias,
                liveHighlightBias = hiBias,
                aiSceneName = aiSceneName,
                tapMetered = tapMetered,
                aiAutoWb = autoWb,
                onStageUpdate = { stage -> _processingStage.value = stage }
            )
        }

        // 5. Standard / Portrait / Document / Pro Single JPEG Capture
        val jReader = stillJpegReader ?: return PhotoCaptureOutcome(emptyList(), "JPEG surface unavailable")
        val tag = when (currentToolMode) {
            PhotoToolMode.PORTRAIT -> "PORTRAIT"
            PhotoToolMode.DOCUMENT -> "DOC"
            else -> if (currentMode == AppDestination.PRO) "PRO" else "PHOTO"
        }
        return photoEngine.captureSingleJpeg(
            caps = caps,
            controls = currentControls,
            jpegReader = jReader,
            jpegQueue = jpegQueue,
            deviceRotationDeg = lastSensorRotationDeg,
            settings = currentSettings,
            location = location,
            modeTag = tag,
            applyPortraitBokeh = currentToolMode == PhotoToolMode.PORTRAIT,
            detectedFaces = _telemetry.value.faceBoundsNormalized,
            applyDocScanner = currentToolMode == PhotoToolMode.DOCUMENT,
            aiProfile = activeProfile,
            focusPowerMode = turbo,
            liveShadowBias = shBias,
            liveHighlightBias = hiBias,
            aiSceneName = aiSceneName,
            tapMetered = tapMetered,
            aiAutoWb = autoWb
        )
    }

    private fun handleCaptureOutcome(outcome: PhotoCaptureOutcome, onCaptureSaved: () -> Unit) {
        if (outcome.savedResults.isNotEmpty()) {
            val first = outcome.savedResults.first()
            val msg = outcome.detailInfo ?: first.displayName
            _captureFeedback.value = CaptureFeedback(
                message = msg,
                isWarning = outcome.statusWarning != null,
                savedUri = first.uri
            )
            onCaptureSaved()
        } else if (outcome.statusWarning != null) {
            _captureFeedback.value = CaptureFeedback(outcome.statusWarning, isWarning = true)
        }
    }

    suspend fun startVideoRecording(config: VideoRecordingConfig, highSpeedConfig: HighSpeedConfig?): Boolean {
        if (storageManager.isCriticallyLowStorage()) {
            _captureFeedback.value = CaptureFeedback("Low storage (<150MB)", isWarning = true)
            return false
        }
        currentVideoConfig = config
        val tag = when {
            config.slowMotionFactor > 1 -> "SLOMO"
            config.timeLapseSpeedFactor > 1 -> "TLAPSE"
            else -> "VID"
        }
        val filename = storageManager.buildUniqueFilename(currentSettings.filenamePrefix, tag, "mp4")
        val tempFile = storageManager.createTempVideoFile(filename)
        val prep = videoEngine.prepare(config, tempFile, filename)
        if (prep.isFailure) {
            _captureFeedback.value = CaptureFeedback(prep.exceptionOrNull()?.message, isWarning = true)
            return false
        }
        val recSurface = prep.getOrThrow()
        openAndConfigureSession(videoInputSurface = recSurface, highSpeedConfig = highSpeedConfig)
        val scope = scopeRef ?: return false
        videoEngine.start(scope)
        return true
    }

    suspend fun stopVideoRecording(onCaptureSaved: () -> Unit) {
        val saved = videoEngine.stopAndPublish(currentSettings)
        openAndConfigureSession(videoInputSurface = null, highSpeedConfig = null)
        if (saved != null) {
            _captureFeedback.value = CaptureFeedback(saved.displayName, isWarning = false, savedUri = saved.uri)
            onCaptureSaved()
        }
    }

    private fun chooseBestPreviewSize(sizes: List<Size>, maxW: Int, maxH: Int): Size {
        val bounded = sizes.filter { it.width <= maxW && it.height <= maxH }
        return bounded.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: sizes.lastOrNull()
            ?: Size(1280, 720)
    }

    private fun mapSensorRectToDisplay(r: RectF, caps: CameraDeviceCapabilities): RectF {
        val isFront = caps.facing == CameraCharacteristics.LENS_FACING_FRONT
        return when (caps.sensorOrientation) {
            90 -> {
                val nl = 1f - r.bottom
                val nr = 1f - r.top
                val nt = r.left
                val nb = r.right
                if (isFront) RectF(1f - nr, nt, 1f - nl, nb) else RectF(nl, nt, nr, nb)
            }
            270 -> {
                val nl = r.top
                val nr = r.bottom
                val nt = 1f - r.right
                val nb = 1f - r.left
                if (isFront) RectF(1f - nr, nt, 1f - nl, nb) else RectF(nl, nt, nr, nb)
            }
            else -> r
        }
    }

    private fun closeReaders() {
        synchronized(readerLock) {
            val readers = listOf(analysisYuvReader, stillJpegReader, stillRawReader, compYuvReader)
            analysisYuvReader = null
            stillJpegReader = null
            stillRawReader = null
            compYuvReader = null
            readers.forEach { r ->
                runCatching {
                    r?.setOnImageAvailableListener(null, null)
                    r?.close()
                }
            }
            while (true) {
                val img = rawQueue.poll() ?: break
                runCatching { img.close() }
            }
            while (true) {
                val bmp = compYuvQueue.poll() ?: break
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    fun pauseCamera() {
        stopSensors()
        stopIntervalometer()
        sessionManager.closeDeviceOnly()
        closeReaders()
        runCatching { previewSurface?.release() }
        previewSurface = null
    }

    fun releaseAll() {
        pauseCamera()
        videoEngine.releaseInternal()
        qrEngine.close()
        mlKitClassifier.close()
        sessionManager.shutdown()
    }
}
