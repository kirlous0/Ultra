package com.example.camera

import android.util.Range
import android.util.Size
import androidx.compose.runtime.Immutable

@Immutable
enum class AppDestination {
    CAMERA,
    PRO,
    NIGHT,
    HDR,
    VIDEO,
    SLOW_MOTION,
    GALLERY,
    STUDIO,
    DIAGNOSTICS,
    SETTINGS
}

@Immutable
enum class LensRole {
    MAIN,
    MACRO,
    FRONT,
    AUXILIARY
}

@Immutable
enum class CaptureFormat {
    JPEG,
    RAW_DNG,
    RAW_PLUS_JPEG
}

@Immutable
enum class PhotoToolMode {
    STANDARD,
    BURST,
    BRACKETING,
    INTERVALOMETER,
    PORTRAIT,
    ASTRO,
    PANORAMA,
    DOCUMENT,
    QR_SCANNER
}

@Immutable
enum class WatermarkStyle {
    TEXT_CORNER,
    PRO_FILM_FRAME
}

@Immutable
enum class GridOverlayType {
    NONE,
    RULE_OF_THIRDS,
    GOLDEN_RATIO,
    SQUARE,
    CROSSHAIR
}

@Immutable
enum class AntiBandingOption(val camera2Value: Int) {
    HZ_50(android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ),
    HZ_60(android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ),
    AUTO(android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
}

@Immutable
enum class VideoCodecType(val mimeType: String, val label: String) {
    H264("video/avc", "H.264 / AVC"),
    HEVC("video/hevc", "H.265 / HEVC")
}

@Immutable
enum class AudioSourceOption(val mediaRecorderSource: Int) {
    MIC(android.media.MediaRecorder.AudioSource.MIC),
    CAMCORDER(android.media.MediaRecorder.AudioSource.CAMCORDER),
    UNPROCESSED(android.media.MediaRecorder.AudioSource.UNPROCESSED)
}

@Immutable
enum class FlashMode {
    OFF,
    AUTO,
    ON,
    TORCH
}

@Immutable
enum class FocusPeakingColor(val labelEn: String, val labelAr: String, val argb: Int) {
    CYAN("Cyan", "سماوي", 0xFF00F0FF.toInt()),
    RED("Red", "أحمر", 0xFFFF2D55.toInt()),
    GREEN("Green", "أخضر", 0xFF22FF66.toInt()),
    YELLOW("Yellow", "أصفر", 0xFFFFD60A.toInt())
}

@Immutable
enum class FocusPeakingSensitivity(val labelEn: String, val labelAr: String, val threshold: Int) {
    LOW("Low", "منخفض", 92),
    MEDIUM("Med", "متوسط", 62),
    HIGH("High", "عالي", 40)
}

@Immutable
enum class WhiteBalancePreset(val labelEn: String, val labelAr: String, val awbMode: Int, val kelvinApprox: Int) {
    AUTO("Auto", "تلقائي", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO, 5500),
    DAYLIGHT("Daylight", "ضوء النهار", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, 5500),
    CLOUDY("Cloudy", "غائم", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, 6500),
    SHADE("Shade", "ظل", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_SHADE, 7500),
    TUNGSTEN("Tungsten", "تنجستن", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, 3200),
    FLUORESCENT("Fluorescent", "فلورسنت", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, 4200),
    MANUAL_KELVIN("Kelvin", "كلفن يدوي", android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_OFF, 5200)
}

@Immutable
data class HighSpeedConfig(
    val width: Int,
    val height: Int,
    val minFps: Int,
    val maxFps: Int
) {
    val size: Size get() = Size(width, height)
}

@Immutable
data class FormatResolutionList(
    val formatCode: Int,
    val formatName: String,
    val resolutions: List<Size>
)

@Immutable
data class VideoEncoderCapabilities(
    val codecType: VideoCodecType,
    val supported: Boolean,
    val codecName: String?,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxBitrateBps: Int,
    val supportedFrameRates: Range<Int>?
)

@Immutable
data class CameraDeviceCapabilities(
    val cameraId: String,
    val facing: Int,
    val facingLabel: String,
    val lensRole: LensRole,
    val isLogicalMultiCamera: Boolean,
    val physicalCameraIds: List<String>,
    val hardwareLevel: Int,
    val hardwareLevelName: String,
    val capabilities: List<String>,
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsBurstCapture: Boolean,
    val supportsYuvReprocessing: Boolean,
    val supportsPrivateReprocessing: Boolean,
    val supportsConstrainedHighSpeed: Boolean,
    val supportsFaceDetection: Boolean,
    val maxFaceCount: Int,
    val afModes: List<Int>,
    val afModeNames: List<String>,
    val supportsManualFocus: Boolean,
    val minimumFocusDistance: Float,
    val hyperfocalDistance: Float,
    val focusDistanceCalibration: String,
    val exposureTimeRangeNs: LongRange?,
    val isoRange: IntRange?,
    val aeCompensationRange: IntRange,
    val aeCompensationStep: Float,
    val awbModes: List<Int>,
    val awbModeNames: List<String>,
    val hasFlash: Boolean,
    val sensorOrientation: Int,
    val pixelArraySize: Size?,
    val activeArraySize: android.graphics.Rect?,
    val physicalSizeMm: String?,
    val apertures: List<Float>,
    val focalLengthsMm: List<Float>,
    val maxDigitalZoom: Float,
    val outputFormats: List<FormatResolutionList>,
    val jpegSizes: List<Size>,
    val rawSizes: List<Size>,
    val yuvSizes: List<Size>,
    val videoSizes: List<Size>,
    val videoFpsBySize: Map<String, List<IntRange>>,
    val aeTargetFpsRanges: List<IntRange>,
    val highSpeedConfigs: List<HighSpeedConfig>,
    val supportsEis: Boolean,
    val supportsPreviewStabilization: Boolean,
    val supportsOis: Boolean,
    val supportsAeLock: Boolean = true,
    val supportsAwbLock: Boolean = true,
    val colorFilterArrangement: String = "UNKNOWN",
    val noiseReductionModeIds: List<Int> = emptyList(),
    val noiseReductionModes: List<String>,
    val edgeModeIds: List<Int> = emptyList(),
    val edgeModes: List<String>,
    val tonemapModes: List<String> = emptyList(),
    val shadingModes: List<String> = emptyList(),
    val hotPixelModes: List<String> = emptyList(),
    val antiBandingModes: List<String>,
    val supportedExtensions: List<String>,
    val concurrentCameraSets: List<List<String>>,
    val isUsableInApp: Boolean,
    val usabilityNote: String
) {
    val megapixelEstimate: Double
        get() {
            val maxJpeg = jpegSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            val px = pixelArraySize
            val best = if (maxJpeg != null && (px == null || maxJpeg.width * maxJpeg.height > px.width * px.height)) {
                maxJpeg
            } else px
            return if (best != null) (best.width.toLong() * best.height.toLong()) / 1_000_000.0 else 0.0
        }
}

@Immutable
data class HardwareInventory(
    val deviceManufacturer: String,
    val deviceModel: String,
    val deviceHardware: String,
    val socModel: String,
    val androidVersion: String,
    val apiLevel: Int,
    val totalRamMb: Long,
    val cameras: List<CameraDeviceCapabilities>,
    val videoEncoders: List<VideoEncoderCapabilities>,
    val scanTimestampMs: Long
) {
    val mainRearCamera: CameraDeviceCapabilities?
        get() = cameras.firstOrNull { it.lensRole == LensRole.MAIN && it.isUsableInApp }
            ?: cameras.firstOrNull { it.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK && it.isUsableInApp }

    val macroCamera: CameraDeviceCapabilities?
        get() = cameras.firstOrNull { it.lensRole == LensRole.MACRO && it.isUsableInApp }

    val frontCamera: CameraDeviceCapabilities?
        get() = cameras.firstOrNull { it.lensRole == LensRole.FRONT && it.isUsableInApp }
            ?: cameras.firstOrNull { it.facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT && it.isUsableInApp }
}

@Immutable
data class ProControlsState(
    val isManualMode: Boolean = false,
    val manualExposureEnabled: Boolean = false,
    val iso: Int = 200,
    val exposureTimeNs: Long = 16_666_666L, // 1/60s
    val exposureCompensationSteps: Int = 0,
    val manualFocusEnabled: Boolean = false,
    val focusDistanceDiopters: Float = 0f,
    val whiteBalancePreset: WhiteBalancePreset = WhiteBalancePreset.AUTO,
    val manualKelvin: Int = 5200,
    val aeLocked: Boolean = false,
    val afLocked: Boolean = false,
    val flashMode: FlashMode = FlashMode.OFF,
    val captureFormat: CaptureFormat = CaptureFormat.JPEG,
    val selectedResolution: Size? = null,
    val jpegQuality: Int = 95,
    val noiseReductionMode: Int = android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
    val edgeEnhancementMode: Int = android.hardware.camera2.CaptureRequest.EDGE_MODE_HIGH_QUALITY,
    val digitalZoomRatio: Float = 1.0f,
    val antiBanding: AntiBandingOption = AntiBandingOption.HZ_50,
    val burstEnabledInPro: Boolean = false,
    val burstFrameCount: Int = 5
)

@Immutable
data class BurstCaptureProgress(
    val capturedCount: Int = 0,
    val targetCount: Int = 0,
    val achievedFps: Float = 0f,
    val isContinuousHold: Boolean = false
)

@Immutable
data class LiveTelemetry(
    val iso: Int = 0,
    val exposureTimeNs: Long = 0L,
    val focusDistanceDiopters: Float = 0f,
    val lensAperture: Float = 0f,
    val aeState: Int = 0,
    val afState: Int = 0,
    val awbState: Int = 0,
    val sensorTimestampNs: Long = 0L,
    val faceCount: Int = 0,
    val faceBoundsNormalized: List<android.graphics.RectF> = emptyList(),
    val lumaHistogram: IntArray = IntArray(64),
    val redHistogram: IntArray = IntArray(64),
    val greenHistogram: IntArray = IntArray(64),
    val blueHistogram: IntArray = IntArray(64),
    val highlightClippingPercent: Float = 0f,
    val shadowClippingPercent: Float = 0f,
    val redHighlightClipPercent: Float = 0f,
    val greenHighlightClipPercent: Float = 0f,
    val blueHighlightClipPercent: Float = 0f,
    val rollDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val isLevel: Boolean = false,
    val focusSharpnessPercent: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiveTelemetry) return false
        return iso == other.iso &&
            exposureTimeNs == other.exposureTimeNs &&
            focusDistanceDiopters == other.focusDistanceDiopters &&
            faceCount == other.faceCount &&
            highlightClippingPercent == other.highlightClippingPercent &&
            shadowClippingPercent == other.shadowClippingPercent &&
            rollDegrees == other.rollDegrees &&
            isLevel == other.isLevel &&
            focusSharpnessPercent == other.focusSharpnessPercent
    }

    override fun hashCode(): Int = iso * 31 + exposureTimeNs.hashCode() + focusSharpnessPercent
}

fun clampProControlsToCamera(
    controls: ProControlsState,
    cam: CameraDeviceCapabilities
): ProControlsState {
    val validRes = controls.selectedResolution?.takeIf { cam.jpegSizes.contains(it) }
        ?: cam.jpegSizes.firstOrNull()
    val validFmt = if (!cam.supportsRaw && controls.captureFormat != CaptureFormat.JPEG) {
        CaptureFormat.JPEG
    } else controls.captureFormat
    val validManualExp = controls.manualExposureEnabled && cam.supportsManualSensor
    val validIso = cam.isoRange?.let { controls.iso.coerceIn(it.first, it.last) } ?: controls.iso
    val validExpNs = cam.exposureTimeRangeNs?.let {
        controls.exposureTimeNs.coerceIn(it.first, it.last)
    } ?: controls.exposureTimeNs
    val validEv = controls.exposureCompensationSteps.coerceIn(
        cam.aeCompensationRange.first,
        cam.aeCompensationRange.last
    )
    val validManualFocus = cam.supportsManualFocus && controls.manualFocusEnabled
    val validFocusDist = if (cam.supportsManualFocus) {
        controls.focusDistanceDiopters.coerceIn(0f, cam.minimumFocusDistance.coerceAtLeast(0f))
    } else 0f
    val validAfLock = cam.supportsManualFocus && controls.afLocked
    val validAeLock = cam.supportsAeLock && controls.aeLocked
    val validFlash = if (cam.hasFlash) controls.flashMode else FlashMode.OFF
    val validWb = when {
        controls.whiteBalancePreset == WhiteBalancePreset.MANUAL_KELVIN && cam.supportsManualPostProcessing ->
            WhiteBalancePreset.MANUAL_KELVIN
        cam.awbModes.isEmpty() || cam.awbModes.contains(controls.whiteBalancePreset.awbMode) ->
            controls.whiteBalancePreset
        else -> WhiteBalancePreset.AUTO
    }
    val validNr = if (cam.noiseReductionModeIds.isEmpty() ||
        cam.noiseReductionModeIds.contains(controls.noiseReductionMode)
    ) controls.noiseReductionMode else cam.noiseReductionModeIds.first()
    val validEdge = if (cam.edgeModeIds.isEmpty() ||
        cam.edgeModeIds.contains(controls.edgeEnhancementMode)
    ) controls.edgeEnhancementMode else cam.edgeModeIds.first()

    return controls.copy(
        manualExposureEnabled = validManualExp,
        iso = validIso,
        exposureTimeNs = validExpNs,
        exposureCompensationSteps = validEv,
        manualFocusEnabled = validManualFocus,
        focusDistanceDiopters = validFocusDist,
        aeLocked = validAeLock,
        afLocked = validAfLock,
        flashMode = validFlash,
        whiteBalancePreset = validWb,
        captureFormat = validFmt,
        selectedResolution = validRes,
        noiseReductionMode = validNr,
        edgeEnhancementMode = validEdge,
        digitalZoomRatio = controls.digitalZoomRatio.coerceIn(1f, cam.maxDigitalZoom.coerceAtLeast(1f)),
        burstEnabledInPro = controls.burstEnabledInPro && validFmt == CaptureFormat.JPEG,
        burstFrameCount = controls.burstFrameCount.coerceIn(3, 12)
    )
}
