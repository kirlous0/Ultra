package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.AppDestination
import com.example.camera.AudioSourceOption
import com.example.camera.CameraController
import com.example.camera.CameraDeviceCapabilities
import com.example.camera.CameraHardwareScanner
import com.example.camera.CameraSessionManager
import com.example.camera.CaptureFormat
import com.example.camera.FlashMode
import com.example.camera.GridOverlayType
import com.example.camera.HardwareInventory
import com.example.camera.HighSpeedConfig
import com.example.camera.LensRole
import com.example.camera.ManualCameraController
import com.example.camera.PhotoToolMode
import com.example.camera.ProControlsState
import com.example.camera.RawCaptureEngine
import com.example.camera.VideoCodecType
import com.example.camera.WhiteBalancePreset
import com.example.camera.clampProControlsToCamera
import com.example.camera.computeIsoPriorityShutterNs
import com.example.processing.AIPhotographyEngine
import com.example.processing.ComputationalPhotographyEngine
import com.example.processing.CropAspectOption
import com.example.processing.ImageEditorEngine
import com.example.processing.QrScannerEngine
import com.example.processing.StudioEditParams
import com.example.system.AppLanguage
import com.example.system.DiagnosticsManager
import com.example.system.ExifMetadata
import com.example.system.GalleryItem
import com.example.system.GalleryManager
import com.example.system.SettingsManager
import com.example.system.StorageManager
import com.example.system.SystemHealthState
import com.example.system.ThermalMonitor
import com.example.system.UltraCamSettings
import com.example.video.VideoRecordingConfig
import com.example.video.VideoRecordingEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VideoSettingsUiState(
    val selectedSize: Size = Size(1920, 1080),
    val selectedFps: Int = 30,
    val bitrateMbps: Int = 20,
    val codec: VideoCodecType = VideoCodecType.H264,
    val recordAudio: Boolean = true,
    val audioSource: AudioSourceOption = AudioSourceOption.MIC,
    val eisEnabled: Boolean = false,
    val timeLapseFactor: Int = 1,
    val selectedHighSpeed: HighSpeedConfig? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context = application.applicationContext
    val settingsManager = SettingsManager(ctx)
    val storageManager = StorageManager(ctx)
    val galleryManager = GalleryManager(ctx)
    val diagnosticsManager = DiagnosticsManager()
    val thermalMonitor = ThermalMonitor(ctx)
    val imageEditorEngine = ImageEditorEngine(ctx)

    private val sessionManager = CameraSessionManager(ctx)
    private val hardwareScanner = CameraHardwareScanner(ctx)
    private val manualController = ManualCameraController()
    private val rawCaptureEngine = RawCaptureEngine(ctx, storageManager)
    private val compEngine = ComputationalPhotographyEngine()
    private val aiEngine = AIPhotographyEngine()
    private val qrEngine = QrScannerEngine()
    private val videoEngine = VideoRecordingEngine(storageManager)

    val cameraController = CameraController(
        context = ctx,
        hardwareScanner = hardwareScanner,
        sessionManager = sessionManager,
        manualController = manualController,
        rawCaptureEngine = rawCaptureEngine,
        compEngine = compEngine,
        aiEngine = aiEngine,
        qrEngine = qrEngine,
        videoEngine = videoEngine,
        storageManager = storageManager,
        thermalMonitor = thermalMonitor
    )

    val settings: StateFlow<UltraCamSettings> = settingsManager.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UltraCamSettings())

    val healthState: StateFlow<SystemHealthState> = thermalMonitor.healthState
    val inventory: StateFlow<HardwareInventory?> = cameraController.inventory
    val activeCamera: StateFlow<CameraDeviceCapabilities?> = cameraController.activeCamera
    val processingJobs = cameraController.processingJobs
    val videoRecordingState = videoEngine.uiState

    private val _currentDestination = MutableStateFlow(AppDestination.CAMERA)
    val currentDestination: StateFlow<AppDestination> = _currentDestination.asStateFlow()

    private val _proControls = MutableStateFlow(ProControlsState())
    val proControls: StateFlow<ProControlsState> = _proControls.asStateFlow()

    private val _photoToolMode = MutableStateFlow(PhotoToolMode.STANDARD)
    val photoToolMode: StateFlow<PhotoToolMode> = _photoToolMode.asStateFlow()

    private val _timerSeconds = MutableStateFlow(0)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()

    private val _videoSettings = MutableStateFlow(VideoSettingsUiState())
    val videoSettings: StateFlow<VideoSettingsUiState> = _videoSettings.asStateFlow()

    private val _galleryItems = MutableStateFlow<List<GalleryItem>>(emptyList())
    val galleryItems: StateFlow<List<GalleryItem>> = _galleryItems.asStateFlow()

    private val _selectedGalleryItem = MutableStateFlow<GalleryItem?>(null)
    val selectedGalleryItem: StateFlow<GalleryItem?> = _selectedGalleryItem.asStateFlow()

    private val _selectedExif = MutableStateFlow<ExifMetadata?>(null)
    val selectedExif: StateFlow<ExifMetadata?> = _selectedExif.asStateFlow()

    private val _studioSourceUri = MutableStateFlow<Uri?>(null)
    val studioSourceUri: StateFlow<Uri?> = _studioSourceUri.asStateFlow()

    private val _studioOriginalBitmap = MutableStateFlow<Bitmap?>(null)
    val studioOriginalBitmap: StateFlow<Bitmap?> = _studioOriginalBitmap.asStateFlow()

    private val _studioRenderedBitmap = MutableStateFlow<Bitmap?>(null)
    val studioRenderedBitmap: StateFlow<Bitmap?> = _studioRenderedBitmap.asStateFlow()

    private val _studioParams = MutableStateFlow(StudioEditParams())
    val studioParams: StateFlow<StudioEditParams> = _studioParams.asStateFlow()

    private val _studioBusy = MutableStateFlow(false)
    val studioBusy: StateFlow<Boolean> = _studioBusy.asStateFlow()
    private var studioRenderJob: kotlinx.coroutines.Job? = null

    private val _diagnosticsJson = MutableStateFlow("")
    val diagnosticsJson: StateFlow<String> = _diagnosticsJson.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    init {
        var lastFocusPower = false
        var lastThermalProt = true
        viewModelScope.launch {
            settings.collect { s ->
                _proControls.value = _proControls.value.copy(
                    jpegQuality = s.defaultJpegQuality,
                    antiBanding = s.antiBanding
                )
                if (s.focusPowerMode != lastFocusPower || s.thermalProtectionEnabled != lastThermalProt) {
                    lastFocusPower = s.focusPowerMode
                    lastThermalProt = s.thermalProtectionEnabled
                    thermalMonitor.sampleNow(s.thermalProtectionEnabled, s.focusPowerMode)
                    thermalMonitor.startMonitoring(viewModelScope, s.thermalProtectionEnabled, s.focusPowerMode)
                }
                cameraController.reconfigureForState(
                    mode = _currentDestination.value,
                    controls = _proControls.value,
                    settings = s,
                    toolMode = _photoToolMode.value,
                    eisEnabled = _videoSettings.value.eisEnabled,
                    selectedVideoSize = _videoSettings.value.selectedSize,
                    selectedVideoFps = _videoSettings.value.selectedFps
                )
            }
        }
        // Enumerate all Camera2 & MediaCodec capabilities immediately on first launch (no permission needed)
        viewModelScope.launch {
            val inv = cameraController.scanHardwareAndSelectDefault()
            syncDefaultsToActiveCamera(inv)
            refreshDiagnosticsReport()
        }
        refreshGallery()
    }

    fun onAppResumed(hasCameraPermission: Boolean) {
        val curSettings = settings.value
        thermalMonitor.startMonitoring(
            scope = viewModelScope,
            thermalProtectionEnabled = curSettings.thermalProtectionEnabled,
            focusPowerMode = curSettings.focusPowerMode
        )
        viewModelScope.launch {
            val inv = cameraController.inventory.value ?: cameraController.scanHardwareAndSelectDefault()
            syncDefaultsToActiveCamera(inv)
            refreshDiagnosticsReport()
            if (hasCameraPermission) {
                cameraController.startSensors()
                cameraController.ensureSessionIfReady()
            }
        }
    }

    fun onAppPaused() {
        thermalMonitor.stopMonitoring()
        if (videoRecordingState.value.isRecording) {
            viewModelScope.launch {
                cameraController.stopVideoRecording { refreshGallery() }
            }
        }
        cameraController.pauseCamera()
    }

    fun rescanHardware() {
        viewModelScope.launch {
            val inv = cameraController.scanHardwareAndSelectDefault()
            syncDefaultsToActiveCamera(inv)
            refreshDiagnosticsReport()
        }
    }

    private fun syncDefaultsToActiveCamera(inv: HardwareInventory) {
        val cam = activeCamera.value ?: return
        val cur = _proControls.value
        val clamped = clampProControlsToCamera(cur, cam)
        _proControls.value = clamped

        // Sync video defaults strictly from discovered resolutions & encoders
        val hevcSupported = inv.videoEncoders.any { it.codecType == VideoCodecType.HEVC && it.supported }
        val chosenCodec = if (_videoSettings.value.codec == VideoCodecType.HEVC && !hevcSupported) {
            VideoCodecType.H264
        } else _videoSettings.value.codec

        val bestVideoSize = cam.videoSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: cam.videoSizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: cam.videoSizes.firstOrNull()
            ?: Size(1920, 1080)

        val validFps = clampVideoFpsForSize(cam, bestVideoSize, _videoSettings.value.selectedFps)
        val hs = cam.highSpeedConfigs.firstOrNull { it.maxFps >= 120 }
            ?: cam.highSpeedConfigs.firstOrNull()

        val updatedVideo = _videoSettings.value.copy(
            selectedSize = bestVideoSize,
            selectedFps = validFps,
            codec = chosenCodec,
            eisEnabled = _videoSettings.value.eisEnabled && cam.supportsEis,
            selectedHighSpeed = hs
        )
        _videoSettings.value = updatedVideo
        viewModelScope.launch {
            cameraController.reconfigureForState(
                mode = _currentDestination.value,
                controls = clamped,
                settings = settings.value,
                toolMode = _photoToolMode.value,
                eisEnabled = updatedVideo.eisEnabled,
                selectedVideoSize = updatedVideo.selectedSize,
                selectedVideoFps = updatedVideo.selectedFps
            )
        }
    }

    private fun clampVideoFpsForSize(cam: CameraDeviceCapabilities, size: Size, desiredFps: Int): Int {
        val key = "${size.width}x${size.height}"
        val supported = cam.videoFpsBySize[key]
            ?.map { it.last }
            ?.filter { it >= 24 }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        if (supported.isEmpty()) return desiredFps.coerceIn(24, 60)
        if (supported.contains(desiredFps)) return desiredFps
        return if (supported.contains(30)) 30 else supported.last()
    }

    fun navigateTo(destination: AppDestination) {
        val prev = _currentDestination.value
        _currentDestination.value = destination
        if (destination == AppDestination.GALLERY) refreshGallery()
        if (destination == AppDestination.DIAGNOSTICS) refreshDiagnosticsReport()

        val isCameraDest = destination in listOf(
            AppDestination.CAMERA,
            AppDestination.PRO,
            AppDestination.NIGHT,
            AppDestination.HDR,
            AppDestination.VIDEO,
            AppDestination.SLOW_MOTION
        )
        if (isCameraDest && prev != destination) {
            val updatedControls = _proControls.value.copy(
                isManualMode = destination == AppDestination.PRO
            )
            _proControls.value = updatedControls
            viewModelScope.launch {
                cameraController.reconfigureForState(
                    mode = destination,
                    controls = updatedControls,
                    settings = settings.value,
                    toolMode = _photoToolMode.value,
                    eisEnabled = _videoSettings.value.eisEnabled,
                    selectedVideoSize = _videoSettings.value.selectedSize,
                    selectedVideoFps = _videoSettings.value.selectedFps
                )
            }
        }
    }

    fun attachPreviewTexture(texture: SurfaceTexture) {
        cameraController.attachPreviewTexture(
            texture = texture,
            scope = viewModelScope,
            mode = _currentDestination.value,
            controls = _proControls.value,
            settings = settings.value,
            toolMode = _photoToolMode.value,
            eisEnabled = _videoSettings.value.eisEnabled
        )
    }

    fun detachPreviewTexture(texture: SurfaceTexture) {
        cameraController.detachPreviewTexture(texture)
    }

    fun switchLens(role: LensRole) {
        viewModelScope.launch {
            cameraController.switchLens(role)
            inventory.value?.let { syncDefaultsToActiveCamera(it) }
        }
    }

    fun updateProControls(transform: (ProControlsState) -> ProControlsState) {
        val cam = activeCamera.value
        var next = transform(_proControls.value)
        if (cam != null) {
            next = clampProControlsToCamera(next, cam)
        }
        val prev = _proControls.value
        _proControls.value = next
        val needSessionRecreate = prev.captureFormat != next.captureFormat ||
            prev.selectedResolution != next.selectedResolution
        viewModelScope.launch {
            cameraController.reconfigureForState(
                mode = _currentDestination.value,
                controls = next,
                settings = settings.value,
                toolMode = _photoToolMode.value,
                eisEnabled = _videoSettings.value.eisEnabled,
                selectedVideoSize = _videoSettings.value.selectedSize,
                selectedVideoFps = _videoSettings.value.selectedFps,
                recreateSession = needSessionRecreate
            )
        }
    }

    fun applyAiRecommendation() {
        val rec = cameraController.aiRecommendation.value ?: return
        updateProControls { cur ->
            cur.copy(
                isManualMode = true,
                manualExposureEnabled = true,
                iso = rec.recommendedIso,
                exposureTimeNs = rec.recommendedShutterNs,
                exposureCompensationSteps = rec.recommendedEvSteps,
                whiteBalancePreset = rec.recommendedWb
            )
        }
        if (rec.recommendedMode != _currentDestination.value &&
            rec.recommendedMode in listOf(AppDestination.HDR, AppDestination.NIGHT, AppDestination.CAMERA)
        ) {
            navigateTo(rec.recommendedMode)
        }
    }

    fun setPhotoToolMode(tool: PhotoToolMode) {
        _photoToolMode.value = tool
        viewModelScope.launch {
            cameraController.reconfigureForState(
                mode = _currentDestination.value,
                controls = _proControls.value,
                settings = settings.value,
                toolMode = tool,
                eisEnabled = _videoSettings.value.eisEnabled,
                selectedVideoSize = _videoSettings.value.selectedSize,
                selectedVideoFps = _videoSettings.value.selectedFps
            )
        }
    }

    fun cycleTimer() {
        _timerSeconds.value = when (_timerSeconds.value) {
            0 -> 3
            3 -> 5
            5 -> 10
            else -> 0
        }
    }

    fun cycleFlash() {
        val next = when (_proControls.value.flashMode) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
        }
        updateProControls { it.copy(flashMode = next) }
    }

    fun toggleFocusPowerMode() {
        val next = !settings.value.focusPowerMode
        viewModelScope.launch {
            settingsManager.setFocusPowerMode(next)
            thermalMonitor.sampleNow(settings.value.thermalProtectionEnabled, next)
        }
    }

    fun setColorProfile(profile: com.example.processing.CameraColorProfile) {
        viewModelScope.launch { settingsManager.setColorProfile(profile) }
    }

    fun updateLibPatcher(transform: (com.example.processing.LibPatcherConfig) -> com.example.processing.LibPatcherConfig) {
        viewModelScope.launch { settingsManager.updateLibPatcher(transform) }
    }

    fun applyLibPreset(preset: com.example.processing.LibPatcherConfig) {
        viewModelScope.launch { settingsManager.applyLibPreset(preset) }
    }

    fun saveCustomSlot(slot: Int) {
        viewModelScope.launch { settingsManager.saveCustomSlot(slot) }
    }

    fun loadCustomSlot(slot: Int) {
        viewModelScope.launch { settingsManager.loadCustomSlot(slot) }
    }

    fun setLiveToneBiases(shadowBias: Float, highlightBias: Float) {
        cameraController.setLiveToneBiases(shadowBias, highlightBias)
    }

    /**
     * Adjusts manual ISO for low-light or Pro shooting. Passing null returns cleanly to Auto AE.
     * When shutter has not been pinned manually, scales exposure time using live telemetry so
     * preview & capture retain proper EV balance without handheld blur.
     */
    fun applyIsoAdjustment(targetIso: Int?) {
        if (targetIso == null) {
            updateProControls {
                it.copy(
                    manualExposureEnabled = false,
                    manualShutterPinned = false
                )
            }
            return
        }
        val cam = activeCamera.value
        val telemetry = cameraController.telemetry.value
        val isNight = _currentDestination.value == AppDestination.NIGHT
        val clampedIso = cam?.isoRange?.let { targetIso.coerceIn(it.first, it.last) } ?: targetIso
        updateProControls { cur ->
            val nextExp = if (cur.manualShutterPinned) {
                cur.exposureTimeNs
            } else {
                computeIsoPriorityShutterNs(
                    targetIso = clampedIso,
                    liveIso = telemetry.iso,
                    liveExposureNs = telemetry.exposureTimeNs,
                    evSteps = cur.exposureCompensationSteps,
                    evStepSize = cam?.aeCompensationStep?.takeIf { it > 0f } ?: 0.1f,
                    exposureRange = cam?.exposureTimeRangeNs,
                    maxHandheldNs = if (isNight) 125_000_000L else 83_333_333L
                )
            }
            cur.copy(
                manualExposureEnabled = true,
                iso = clampedIso,
                exposureTimeNs = nextExp
            )
        }
    }

    fun setEvCompensationSteps(steps: Int) {
        updateProControls {
            it.copy(
                manualExposureEnabled = false,
                manualShutterPinned = false,
                exposureCompensationSteps = steps
            )
        }
    }

    fun toggle3x3GridQuick() {
        val current = settings.value.gridOverlay
        val next = if (current == GridOverlayType.RULE_OF_THIRDS) GridOverlayType.NONE
        else GridOverlayType.RULE_OF_THIRDS
        viewModelScope.launch {
            settingsManager.setGridOverlay(next)
        }
    }

    fun triggerCaptureOrRecord() {
        val dest = _currentDestination.value
        if (dest == AppDestination.VIDEO || dest == AppDestination.SLOW_MOTION) {
            viewModelScope.launch {
                if (videoRecordingState.value.isRecording) {
                    cameraController.stopVideoRecording { refreshGallery() }
                } else {
                    val vs = _videoSettings.value
                    val s = settings.value
                    val isSlowMo = dest == AppDestination.SLOW_MOTION
                    val hs = if (isSlowMo) vs.selectedHighSpeed else null
                    if (isSlowMo && hs == null) {
                        _statusBanner.value = "High-speed slow motion unavailable on this lens"
                        return@launch
                    }
                    val encoderMax = inventory.value?.videoEncoders
                        ?.firstOrNull { it.codecType == vs.codec && it.supported }
                        ?.maxBitrateBps ?: 60_000_000
                    val aiMultiplier = if (s.aiAssistantEnabled || s.focusPowerMode) {
                        cameraController.aiRecommendation.value?.profile?.videoBitrateMultiplier ?: 1.15f
                    } else 1.0f

                    val config = VideoRecordingConfig(
                        size = hs?.size ?: vs.selectedSize,
                        fps = hs?.maxFps ?: vs.selectedFps,
                        bitrateBps = vs.bitrateMbps * 1_000_000,
                        codec = vs.codec,
                        recordAudio = vs.recordAudio && !isSlowMo && vs.timeLapseFactor == 1,
                        audioSource = vs.audioSource,
                        eisEnabled = vs.eisEnabled,
                        slowMotionFactor = if (isSlowMo && hs != null) (hs.maxFps / 30).coerceAtLeast(2) else 1,
                        timeLapseSpeedFactor = if (!isSlowMo) vs.timeLapseFactor else 1,
                        focusPowerMode = s.focusPowerMode,
                        aiBitrateMultiplier = aiMultiplier,
                        encoderMaxBitrateBps = encoderMax
                    )
                    cameraController.startVideoRecording(config, hs)
                }
            }
        } else {
            viewModelScope.launch {
                cameraController.triggerPhotoCapture(
                    timerSeconds = _timerSeconds.value,
                    onCaptureSaved = { refreshGallery() }
                )
            }
        }
    }

    fun finishPanorama() {
        viewModelScope.launch {
            cameraController.finishPanorama { refreshGallery() }
        }
    }

    fun startActionBurst() {
        val dest = _currentDestination.value
        if (dest == AppDestination.VIDEO || dest == AppDestination.SLOW_MOTION) return
        cameraController.startHoldActionBurst { refreshGallery() }
    }

    fun stopActionBurst() {
        cameraController.stopHoldActionBurst()
    }

    fun updateVideoSettings(transform: (VideoSettingsUiState) -> VideoSettingsUiState) {
        val prev = _videoSettings.value
        val rawNext = transform(prev)
        val cam = activeCamera.value
        val clampedNext = if (cam != null) {
            rawNext.copy(
                selectedFps = clampVideoFpsForSize(cam, rawNext.selectedSize, rawNext.selectedFps),
                eisEnabled = rawNext.eisEnabled && cam.supportsEis
            )
        } else rawNext
        _videoSettings.value = clampedNext
        val isVideoMode = _currentDestination.value == AppDestination.VIDEO ||
            _currentDestination.value == AppDestination.SLOW_MOTION
        viewModelScope.launch {
            cameraController.reconfigureForState(
                mode = _currentDestination.value,
                controls = _proControls.value,
                settings = settings.value,
                toolMode = _photoToolMode.value,
                eisEnabled = clampedNext.eisEnabled,
                selectedVideoSize = clampedNext.selectedSize,
                selectedVideoFps = clampedNext.selectedFps,
                recreateSession = isVideoMode && prev.selectedSize != clampedNext.selectedSize
            )
        }
    }

    fun estimateVideoMinutesRemaining(): Long {
        val s = settings.value
        val baseBps = _videoSettings.value.bitrateMbps * 1_000_000
        val aiMult = if (s.aiAssistantEnabled || s.focusPowerMode) {
            cameraController.aiRecommendation.value?.profile?.videoBitrateMultiplier ?: 1.15f
        } else 1.0f
        val turboMult = if (s.focusPowerMode) 1.25f else 1.0f
        val encMax = inventory.value?.videoEncoders
            ?.firstOrNull { it.codecType == _videoSettings.value.codec && it.supported }
            ?.maxBitrateBps ?: 60_000_000
        val effectiveBps = (baseBps.toFloat() * aiMult * turboMult)
            .toInt()
            .coerceIn(baseBps, encMax.coerceAtLeast(baseBps))
        return storageManager.estimateVideoSecondsRemaining(effectiveBps) / 60L
    }

    // Gallery & Studio
    fun refreshGallery() {
        viewModelScope.launch {
            _galleryItems.value = galleryManager.loadCaptures(settings.value.customSafTreeUri)
        }
    }

    fun selectGalleryItem(item: GalleryItem?) {
        _selectedGalleryItem.value = item
        if (item == null) {
            _selectedExif.value = null
            return
        }
        viewModelScope.launch {
            _selectedExif.value = galleryManager.readExifMetadata(item)
        }
    }

    fun deleteGalleryItem(item: GalleryItem) {
        viewModelScope.launch {
            galleryManager.deleteItem(item)
            if (_selectedGalleryItem.value?.uri == item.uri) {
                _selectedGalleryItem.value = null
            }
            refreshGallery()
        }
    }

    fun openUriInStudio(uri: Uri) {
        _studioSourceUri.value = uri
        _currentDestination.value = AppDestination.STUDIO
        viewModelScope.launch {
            _studioBusy.value = true
            val bmp = imageEditorEngine.loadEditableBitmap(uri, maxDim = 1440)
            val savedParams = imageEditorEngine.loadNonDestructiveRecipe(storageManager, uri)
            _studioOriginalBitmap.value = bmp
            _studioParams.value = savedParams
            if (bmp != null) {
                _studioRenderedBitmap.value = imageEditorEngine.renderPreview(bmp, savedParams)
            }
            _studioBusy.value = false
        }
    }

    fun updateStudioParams(transform: (StudioEditParams) -> StudioEditParams) {
        val next = transform(_studioParams.value)
        _studioParams.value = next
        val orig = _studioOriginalBitmap.value ?: return
        studioRenderJob?.cancel()
        studioRenderJob = viewModelScope.launch {
            _studioRenderedBitmap.value = imageEditorEngine.renderPreview(orig, next)
        }
    }

    fun resetStudioEdits() {
        _studioParams.value = StudioEditParams()
        _studioRenderedBitmap.value = _studioOriginalBitmap.value
    }

    fun exportStudioImage(exportAsPng: Boolean, quality: Int, scaleFactor: Float) {
        val uri = _studioSourceUri.value ?: return
        viewModelScope.launch {
            _studioBusy.value = true
            val res = imageEditorEngine.exportEditedImage(
                storageManager = storageManager,
                sourceUri = uri,
                params = _studioParams.value,
                exportAsPng = exportAsPng,
                jpegQuality = quality,
                scaleFactor = scaleFactor,
                settings = settings.value
            )
            _studioBusy.value = false
            if (res != null) {
                _statusBanner.value = "Exported: ${res.displayName}"
                refreshGallery()
            }
        }
    }

    // Diagnostics
    fun refreshDiagnosticsReport() {
        val inv = inventory.value ?: return
        _diagnosticsJson.value = diagnosticsManager.buildJsonReport(inv, healthState.value)
    }

    fun exportDiagnosticsJson(onExported: (Uri?) -> Unit) {
        val json = _diagnosticsJson.value
        if (json.isBlank()) return
        viewModelScope.launch {
            val name = storageManager.buildUniqueFilename("ULTRACAM_DIAG", "HW", "json")
            val uri = storageManager.saveJsonDocument(name, json, settings.value)
            _statusBanner.value = if (uri != null) "Saved JSON: $name" else "Export failed"
            onExported(uri)
        }
    }

    fun updateSettings(block: suspend SettingsManager.() -> Unit) {
        viewModelScope.launch {
            settingsManager.block()
        }
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.releaseAll()
    }
}
