package com.example.camera

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class CameraHardwareScanner(private val context: Context) {

    suspend fun scanAllHardware(): HardwareInventory = withContext(Dispatchers.Default) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024L * 1024L)

        val concurrentSets: List<List<String>> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                cameraManager.concurrentCameraIds.map { it.toList() }
            }.getOrDefault(emptyList())
        } else emptyList()

        val allIds = LinkedHashSet<String>()
        val logicalIds = runCatching { cameraManager.cameraIdList.toList() }.getOrDefault(emptyList())
        allIds.addAll(logicalIds)

        // Also discover physical sub-cameras if exposed by logical cameras
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (id in logicalIds) {
                runCatching {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    chars.physicalCameraIds.forEach { physId -> allIds.add(physId) }
                }
            }
        }

        val rawProfiles = allIds.mapNotNull { id ->
            runCatching { inspectCamera(cameraManager, id, concurrentSets) }.getOrNull()
        }

        // Dynamically classify lenses without hardcoding camera IDs
        val classified = classifyLenses(rawProfiles)
        val encoders = scanVideoEncoders()

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
        } else Build.HARDWARE

        HardwareInventory(
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            deviceHardware = Build.HARDWARE,
            socModel = socModel,
            androidVersion = Build.VERSION.RELEASE ?: "Unknown",
            apiLevel = Build.VERSION.SDK_INT,
            totalRamMb = totalRamMb,
            cameras = classified,
            videoEncoders = encoders,
            scanTimestampMs = System.currentTimeMillis()
        )
    }

    private fun inspectCamera(
        cameraManager: CameraManager,
        cameraId: String,
        concurrentSets: List<List<String>>
    ): CameraDeviceCapabilities {
        val chars = cameraManager.getCameraCharacteristics(cameraId)

        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val facingLabel = when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN($facing)"
        }

        val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        val hwLevelName = when (hwLevel) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN($hwLevel)"
        }

        val capInts = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        val capNames = capInts.map { capName(it) }

        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            chars.physicalCameraIds.toList()
        } else emptyList()

        val isLogicalMultiCam = capInts.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) ||
            physicalIds.isNotEmpty()

        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        val afNames = afModes.map { afModeName(it) }
        val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val hyperfocal = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
        val focusCal = when (chars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
            else -> "UNCALIBRATED"
        }
        val supportsManualFocus = minFocusDist > 0f &&
            afModes.any { it != CameraMetadata.CONTROL_AF_MODE_OFF }

        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
            it.lower..it.upper
        }
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
            it.lower..it.upper
        }
        val aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
            it.lower..it.upper
        } ?: (0..0)
        val aeCompStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

        val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList()
        val awbNames = awbModes.map { awbModeName(it) }
        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val pixelArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val activeArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val physSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let {
            String.format(Locale.US, "%.2fx%.2f mm", it.width, it.height)
        }
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList()
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

        val streamMap: StreamConfigurationMap? =
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val outputFormats = mutableListOf<FormatResolutionList>()
        var jpegSizes: List<Size> = emptyList()
        var rawSizes: List<Size> = emptyList()
        var yuvSizes: List<Size> = emptyList()
        var videoSizes: List<Size> = emptyList()
        val videoFpsBySize = linkedMapOf<String, List<IntRange>>()
        val highSpeedConfigs = mutableListOf<HighSpeedConfig>()

        if (streamMap != null) {
            val formats = streamMap.outputFormats ?: intArrayOf()
            for (fmt in formats) {
                val sizes = streamMap.getOutputSizes(fmt)?.sortedByDescending { it.width * it.height }
                    ?: emptyList()
                outputFormats.add(FormatResolutionList(fmt, formatName(fmt), sizes))
                when (fmt) {
                    ImageFormat.JPEG -> jpegSizes = sizes
                    ImageFormat.RAW_SENSOR -> rawSizes = sizes
                    ImageFormat.YUV_420_888 -> yuvSizes = sizes
                }
            }

            videoSizes = streamMap.getOutputSizes(MediaRecorder::class.java)
                ?.sortedByDescending { it.width * it.height }
                ?: emptyList()

            val aeFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { it.lower..it.upper } ?: emptyList()

            for (vSize in videoSizes) {
                val minDurationNs = runCatching {
                    streamMap.getOutputMinFrameDuration(MediaRecorder::class.java, vSize)
                }.getOrDefault(0L)
                val maxAchievableFps = if (minDurationNs > 0L) {
                    (1_000_000_000.0 / minDurationNs).toInt()
                } else 30
                val matching = aeFpsRanges.filter { it.last <= maxAchievableFps + 1 }
                videoFpsBySize["${vSize.width}x${vSize.height}"] =
                    if (matching.isNotEmpty()) matching else listOf(15..maxAchievableFps.coerceAtLeast(15))
            }

            // High-speed video discovery
            val hsSizes = runCatching { streamMap.highSpeedVideoSizes }.getOrNull() ?: emptyArray()
            for (hsSize in hsSizes) {
                val ranges = runCatching { streamMap.getHighSpeedVideoFpsRangesFor(hsSize) }.getOrNull()
                    ?: emptyArray()
                for (r in ranges) {
                    highSpeedConfigs.add(
                        HighSpeedConfig(hsSize.width, hsSize.height, r.lower, r.upper)
                    )
                }
            }
        }

        val aeTargetFps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower..it.upper } ?: emptyList()

        val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.toList() ?: emptyList()
        val supportsEis = eisModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        val supportsPreviewStab = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            eisModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
        } else false

        val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.toList() ?: emptyList()
        val supportsOis = oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

        val nrModeIds = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?.toList() ?: emptyList()
        val nrModes = nrModeIds.map { nrModeName(it) }
        val edgeModeIds = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            ?.toList() ?: emptyList()
        val edgeModes = edgeModeIds.map { edgeModeName(it) }
        val abModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
            ?.map { abModeName(it) } ?: emptyList()
        val tonemapModes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
            ?.map { tonemapModeName(it) } ?: emptyList()
        val shadingModes = chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
            ?.map { shadingModeName(it) } ?: emptyList()
        val hotPixelModes = chars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
            ?.map { hotPixelModeName(it) } ?: emptyList()
        val cfaArrangement = cfaName(chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT))
        val supportsAeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false
        val supportsAwbLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false

        val faceDetectModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
            ?.toList() ?: emptyList()
        val maxFaceCount = chars.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0
        val supportsFaceDetect = faceDetectModes.any { it != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF } && maxFaceCount > 0

        val extensions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val extChars = cameraManager.getCameraExtensionCharacteristics(cameraId)
                extChars.supportedExtensions.map { extensionName(it) }
            }.getOrDefault(emptyList())
        } else emptyList()

        val myConcurrent = concurrentSets.filter { it.contains(cameraId) }
        val usable = jpegSizes.isNotEmpty() && yuvSizes.isNotEmpty()
        val usabilityNote = when {
            !usable && outputFormats.any { it.formatCode == ImageFormat.DEPTH16 || it.formatCode == ImageFormat.DEPTH_POINT_CLOUD } ->
                "Depth-only / auxiliary sensor"
            !usable -> "No standard JPEG/YUV output stream"
            else -> "Usable camera stream"
        }

        return CameraDeviceCapabilities(
            cameraId = cameraId,
            facing = facing,
            facingLabel = facingLabel,
            lensRole = LensRole.AUXILIARY,
            isLogicalMultiCamera = isLogicalMultiCam,
            physicalCameraIds = physicalIds,
            hardwareLevel = hwLevel,
            hardwareLevelName = hwLevelName,
            capabilities = capNames,
            supportsRaw = capInts.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) && rawSizes.isNotEmpty(),
            supportsManualSensor = capInts.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            supportsManualPostProcessing = capInts.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
            supportsBurstCapture = capInts.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE),
            supportsYuvReprocessing = capInts.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING),
            supportsPrivateReprocessing = capInts.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING),
            supportsConstrainedHighSpeed = capInts.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) &&
                highSpeedConfigs.isNotEmpty(),
            supportsFaceDetection = supportsFaceDetect,
            maxFaceCount = maxFaceCount,
            afModes = afModes,
            afModeNames = afNames,
            supportsManualFocus = supportsManualFocus,
            minimumFocusDistance = minFocusDist,
            hyperfocalDistance = hyperfocal,
            focusDistanceCalibration = focusCal,
            exposureTimeRangeNs = expRange,
            isoRange = isoRange,
            aeCompensationRange = aeCompRange,
            aeCompensationStep = aeCompStep,
            awbModes = awbModes,
            awbModeNames = awbNames,
            hasFlash = hasFlash,
            sensorOrientation = sensorOrientation,
            pixelArraySize = pixelArraySize,
            activeArraySize = activeArraySize,
            physicalSizeMm = physSize,
            apertures = apertures,
            focalLengthsMm = focalLengths,
            maxDigitalZoom = maxZoom,
            outputFormats = outputFormats,
            jpegSizes = jpegSizes,
            rawSizes = rawSizes,
            yuvSizes = yuvSizes,
            videoSizes = videoSizes,
            videoFpsBySize = videoFpsBySize,
            aeTargetFpsRanges = aeTargetFps,
            highSpeedConfigs = highSpeedConfigs.distinct(),
            supportsEis = supportsEis,
            supportsPreviewStabilization = supportsPreviewStab,
            supportsOis = supportsOis,
            supportsAeLock = supportsAeLock,
            supportsAwbLock = supportsAwbLock,
            colorFilterArrangement = cfaArrangement,
            noiseReductionModeIds = nrModeIds,
            noiseReductionModes = nrModes,
            edgeModeIds = edgeModeIds,
            edgeModes = edgeModes,
            tonemapModes = tonemapModes,
            shadingModes = shadingModes,
            hotPixelModes = hotPixelModes,
            antiBandingModes = abModes,
            supportedExtensions = extensions,
            concurrentCameraSets = myConcurrent,
            isUsableInApp = usable,
            usabilityNote = usabilityNote
        )
    }

    private fun classifyLenses(profiles: List<CameraDeviceCapabilities>): List<CameraDeviceCapabilities> {
        val backUsable = profiles.filter {
            it.facing == CameraCharacteristics.LENS_FACING_BACK && it.isUsableInApp
        }
        val mainBackId = backUsable.maxByOrNull { cam ->
            val maxPixels = cam.jpegSizes.maxOfOrNull { it.width.toLong() * it.height.toLong() } ?: 0L
            val bonus = (if (cam.supportsManualFocus) 10_000_000L else 0L) +
                (if (cam.hasFlash) 5_000_000L else 0L)
            maxPixels + bonus
        }?.cameraId

        val macroBackId = backUsable.firstOrNull { cam ->
            cam.cameraId != mainBackId && cam.megapixelEstimate in 0.8..5.5
        }?.cameraId

        val frontUsableId = profiles.filter {
            it.facing == CameraCharacteristics.LENS_FACING_FRONT && it.isUsableInApp
        }.maxByOrNull { cam ->
            cam.jpegSizes.maxOfOrNull { it.width.toLong() * it.height.toLong() } ?: 0L
        }?.cameraId

        return profiles.map { cam ->
            val role = when (cam.cameraId) {
                mainBackId -> LensRole.MAIN
                macroBackId -> LensRole.MACRO
                frontUsableId -> LensRole.FRONT
                else -> LensRole.AUXILIARY
            }
            cam.copy(lensRole = role)
        }
    }

    private fun scanVideoEncoders(): List<VideoEncoderCapabilities> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return VideoCodecType.entries.map { codecType ->
            val info = list.codecInfos.firstOrNull { ci ->
                ci.isEncoder && ci.supportedTypes.any { it.equals(codecType.mimeType, ignoreCase = true) }
            }
            val caps = info?.getCapabilitiesForType(codecType.mimeType)?.videoCapabilities
            if (info != null && caps != null) {
                val maxW = caps.supportedWidths.upper
                val maxH = caps.supportedHeights.upper
                val maxBitrate = caps.bitrateRange.upper
                val fpsRange = runCatching {
                    caps.getSupportedFrameRatesFor(1920.coerceAtMost(maxW), 1080.coerceAtMost(maxH))
                }.getOrNull()?.let { it.lower.toInt()..it.upper.toInt() }
                    ?.let { android.util.Range(it.first, it.last) }

                VideoEncoderCapabilities(
                    codecType = codecType,
                    supported = true,
                    codecName = info.name,
                    maxWidth = maxW,
                    maxHeight = maxH,
                    maxBitrateBps = maxBitrate,
                    supportedFrameRates = fpsRange
                )
            } else {
                VideoEncoderCapabilities(
                    codecType = codecType,
                    supported = false,
                    codecName = null,
                    maxWidth = 0,
                    maxHeight = 0,
                    maxBitrateBps = 0,
                    supportedFrameRates = null
                )
            }
        }
    }

    private fun capName(cap: Int): String = when (cap) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        else -> "CAP_$cap"
    }

    private fun afModeName(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "AF_$mode"
    }

    private fun awbModeName(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_AWB_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "SHADE"
        else -> "AWB_$mode"
    }

    private fun nrModeName(mode: Int): String = when (mode) {
        CameraMetadata.NOISE_REDUCTION_MODE_OFF -> "OFF"
        CameraMetadata.NOISE_REDUCTION_MODE_FAST -> "FAST"
        CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL -> "MINIMAL"
        CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "ZSL"
        else -> "NR_$mode"
    }

    private fun edgeModeName(mode: Int): String = when (mode) {
        CameraMetadata.EDGE_MODE_OFF -> "OFF"
        CameraMetadata.EDGE_MODE_FAST -> "FAST"
        CameraMetadata.EDGE_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG -> "ZSL"
        else -> "EDGE_$mode"
    }

    private fun abModeName(mode: Int): String = when (mode) {
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ -> "50HZ"
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ -> "60HZ"
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO -> "AUTO"
        else -> "AB_$mode"
    }

    private fun formatName(fmt: Int): String = when (fmt) {
        ImageFormat.JPEG -> "JPEG"
        ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
        ImageFormat.RAW10 -> "RAW10"
        ImageFormat.RAW12 -> "RAW12"
        ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"
        ImageFormat.YUV_420_888 -> "YUV_420_888"
        ImageFormat.NV21 -> "NV21"
        ImageFormat.YV12 -> "YV12"
        ImageFormat.PRIVATE -> "PRIVATE"
        ImageFormat.DEPTH16 -> "DEPTH16"
        ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
        ImageFormat.HEIC -> "HEIC"
        else -> "FMT_0x${fmt.toString(16).uppercase(Locale.US)}"
    }

    private fun extensionName(ext: Int): String = when (ext) {
        1 -> "BOKEH"
        2 -> "HDR"
        3 -> "NIGHT"
        4 -> "FACE_RETOUCH"
        0 -> "AUTOMATIC"
        else -> "EXT_$ext"
    }

    private fun cfaName(cfa: Int?): String = when (cfa) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> "NIR"
        else -> "UNKNOWN"
    }

    private fun tonemapModeName(mode: Int): String = when (mode) {
        CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE -> "CONTRAST_CURVE"
        CameraMetadata.TONEMAP_MODE_FAST -> "FAST"
        CameraMetadata.TONEMAP_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.TONEMAP_MODE_GAMMA_VALUE -> "GAMMA_VALUE"
        CameraMetadata.TONEMAP_MODE_PRESET_CURVE -> "PRESET_CURVE"
        else -> "TONEMAP_$mode"
    }

    private fun shadingModeName(mode: Int): String = when (mode) {
        CameraMetadata.SHADING_MODE_OFF -> "OFF"
        CameraMetadata.SHADING_MODE_FAST -> "FAST"
        CameraMetadata.SHADING_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        else -> "SHADING_$mode"
    }

    private fun hotPixelModeName(mode: Int): String = when (mode) {
        CameraMetadata.HOT_PIXEL_MODE_OFF -> "OFF"
        CameraMetadata.HOT_PIXEL_MODE_FAST -> "FAST"
        CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        else -> "HOTPIXEL_$mode"
    }
}
