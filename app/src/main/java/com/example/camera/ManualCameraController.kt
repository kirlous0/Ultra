package com.example.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.util.Rational
import com.example.processing.AiProcessingProfile
import kotlin.math.pow
import kotlin.math.roundToInt

class ManualCameraController {

    fun applyToBuilder(
        builder: CaptureRequest.Builder,
        caps: CameraDeviceCapabilities,
        controls: ProControlsState,
        isVideoMode: Boolean,
        eisEnabled: Boolean,
        aiProfile: AiProcessingProfile? = null,
        focusPowerMode: Boolean = false,
        aiAutoWb: WhiteBalancePreset? = null
    ) {
        // Anti-banding (50Hz Egypt default when supported)
        val requestedAb = controls.antiBanding.camera2Value
        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, requestedAb)

        // Exposure & AE mode
        if (controls.isManualMode && controls.manualExposureEnabled && caps.supportsManualSensor) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)

            val iso = caps.isoRange?.let { controls.iso.coerceIn(it.first, it.last) } ?: controls.iso
            val expNs = caps.exposureTimeRangeNs?.let {
                controls.exposureTimeNs.coerceIn(it.first, it.last)
            } ?: controls.exposureTimeNs

            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            val aeMode = when {
                !caps.hasFlash -> CameraMetadata.CONTROL_AE_MODE_ON
                controls.flashMode == FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                controls.flashMode == FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            val ev = controls.exposureCompensationSteps.coerceIn(
                caps.aeCompensationRange.first,
                caps.aeCompensationRange.last
            )
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
        }

        // Flash / Torch
        if (caps.hasFlash) {
            val flashMode = when (controls.flashMode) {
                FlashMode.TORCH -> CameraMetadata.FLASH_MODE_TORCH
                FlashMode.ON -> CameraMetadata.FLASH_MODE_SINGLE
                else -> CameraMetadata.FLASH_MODE_OFF
            }
            builder.set(CaptureRequest.FLASH_MODE, flashMode)
        }

        builder.set(CaptureRequest.CONTROL_AE_LOCK, controls.aeLocked && caps.supportsAeLock)

        // Focus mode: strictly never set manual focus on fixed-focus lenses (e.g., front camera)
        if (controls.manualFocusEnabled && caps.supportsManualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            val dist = controls.focusDistanceDiopters.coerceIn(0f, caps.minimumFocusDistance)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, dist)
        } else if (controls.afLocked && caps.supportsManualFocus &&
            caps.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO)
        ) {
            // Hold locked focus without continuous refocus hunting
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        } else if (caps.afModes.isNotEmpty()) {
            val preferredAf = when {
                isVideoMode && caps.afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                caps.lensRole == LensRole.MACRO && caps.afModes.contains(CameraMetadata.CONTROL_AF_MODE_MACRO) ->
                    CameraMetadata.CONTROL_AF_MODE_MACRO
                caps.afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                caps.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ->
                    CameraMetadata.CONTROL_AF_MODE_AUTO
                else -> caps.afModes.first()
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, preferredAf)
        }

        // White Balance
        if (controls.whiteBalancePreset == WhiteBalancePreset.MANUAL_KELVIN &&
            caps.supportsManualPostProcessing
        ) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                kelvinToRggbGains(controls.manualKelvin)
            )
            builder.set(
                CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                ColorSpaceTransform(
                    intArrayOf(
                        1, 1, 0, 1, 0, 1,
                        0, 1, 1, 1, 0, 1,
                        0, 1, 0, 1, 1, 1
                    )
                )
            )
        } else {
            val effectiveWb = if (aiAutoWb != null &&
                controls.whiteBalancePreset == WhiteBalancePreset.AUTO &&
                aiAutoWb != WhiteBalancePreset.MANUAL_KELVIN
            ) {
                aiAutoWb
            } else {
                controls.whiteBalancePreset
            }
            val requestedAwb = effectiveWb.awbMode
            val awb = if (caps.awbModes.contains(requestedAwb)) requestedAwb
            else CameraMetadata.CONTROL_AWB_MODE_AUTO
            builder.set(CaptureRequest.CONTROL_AWB_MODE, awb)
        }

        // Stabilization (strictly only when supported by discovered hardware)
        if (isVideoMode && eisEnabled && caps.supportsEis) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        } else {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }

        if (caps.supportsOis) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        }

        // ISP Noise Reduction & Edge Enhancement (strictly clamped to discovered hardware modes)
        val desiredNr = when {
            focusPowerMode && caps.noiseReductionModeIds.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY) ->
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            !controls.isManualMode && aiProfile != null && caps.noiseReductionModeIds.contains(aiProfile.ispNrMode) ->
                aiProfile.ispNrMode
            caps.noiseReductionModeIds.isEmpty() || caps.noiseReductionModeIds.contains(controls.noiseReductionMode) ->
                controls.noiseReductionMode
            else -> caps.noiseReductionModeIds.firstOrNull {
                it == CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            } ?: caps.noiseReductionModeIds.first()
        }
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, desiredNr)

        val desiredEdge = when {
            focusPowerMode && caps.edgeModeIds.contains(CameraMetadata.EDGE_MODE_HIGH_QUALITY) ->
                CameraMetadata.EDGE_MODE_HIGH_QUALITY
            !controls.isManualMode && aiProfile != null && caps.edgeModeIds.contains(aiProfile.ispEdgeMode) ->
                aiProfile.ispEdgeMode
            caps.edgeModeIds.isEmpty() || caps.edgeModeIds.contains(controls.edgeEnhancementMode) ->
                controls.edgeEnhancementMode
            else -> caps.edgeModeIds.firstOrNull {
                it == CameraMetadata.EDGE_MODE_HIGH_QUALITY
            } ?: caps.edgeModeIds.first()
        }
        builder.set(CaptureRequest.EDGE_MODE, desiredEdge)

        // Additional Camera2 ISP optical/color pipeline tuning when hardware supports HIGH_QUALITY
        if (focusPowerMode || !controls.isManualMode) {
            runCatching {
                builder.set(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
                )
            }
            if (caps.hotPixelModes.contains("HIGH_QUALITY")) {
                runCatching {
                    builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY)
                }
            }
            if (caps.shadingModes.contains("HIGH_QUALITY")) {
                runCatching {
                    builder.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_HIGH_QUALITY)
                }
            }
            if (caps.tonemapModes.contains("HIGH_QUALITY") &&
                controls.whiteBalancePreset != WhiteBalancePreset.MANUAL_KELVIN
            ) {
                runCatching {
                    builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
                }
            }
        }

        // Face Detection
        if (caps.supportsFaceDetection) {
            builder.set(
                CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
            )
        }

        // Digital Zoom crop region (clearly flagged as digital zoom in UI)
        val active = caps.activeArraySize
        if (active != null && controls.digitalZoomRatio > 1.01f) {
            val z = controls.digitalZoomRatio.coerceIn(1f, caps.maxDigitalZoom)
            val cropW = (active.width() / z).roundToInt()
            val cropH = (active.height() / z).roundToInt()
            val left = active.left + (active.width() - cropW) / 2
            val top = active.top + (active.height() - cropH) / 2
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                Rect(left, top, left + cropW, top + cropH)
            )
        } else if (active != null) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, active)
        }
    }

    fun computeMeteringRectangles(
        normX: Float,
        normY: Float,
        caps: CameraDeviceCapabilities,
        zoomRatio: Float
    ): Array<MeteringRectangle>? {
        val active = caps.activeArraySize ?: return null
        val z = zoomRatio.coerceIn(1f, caps.maxDigitalZoom)
        val cropW = (active.width() / z).roundToInt()
        val cropH = (active.height() / z).roundToInt()
        val cropLeft = active.left + (active.width() - cropW) / 2
        val cropTop = active.top + (active.height() - cropH) / 2

        val isFront = caps.facing == CameraCharacteristics.LENS_FACING_FRONT
        val nx = if (isFront) 1f - normX else normX
        val ny = normY

        // Map portrait display coords (0..1) into sensor coords based on sensorOrientation
        val (sx, sy) = when (caps.sensorOrientation) {
            90 -> ny to (1f - nx)
            270 -> (1f - ny) to nx
            180 -> (1f - nx) to (1f - ny)
            else -> nx to ny
        }

        val cx = (cropLeft + sx * cropW).roundToInt()
        val cy = (cropTop + sy * cropH).roundToInt()
        val halfBox = (minOf(cropW, cropH) * 0.1f).roundToInt().coerceAtLeast(40)

        val rect = Rect(
            (cx - halfBox).coerceIn(active.left, active.right - 1),
            (cy - halfBox).coerceIn(active.top, active.bottom - 1),
            (cx + halfBox).coerceIn(active.left + 1, active.right),
            (cy + halfBox).coerceIn(active.top + 1, active.bottom)
        )
        return arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1))
    }

    private fun kelvinToRggbGains(kelvin: Int): RggbChannelVector {
        val temp = (kelvin.coerceIn(2200, 9000)) / 100.0
        val r: Double
        val g: Double
        val b: Double
        if (temp <= 66.0) {
            r = 255.0
            g = 99.4708025861 * kotlin.math.ln(temp) - 161.1195681661
            b = if (temp <= 19.0) 0.0 else 138.5177312231 * kotlin.math.ln(temp - 10.0) - 305.0447927307
        } else {
            r = 329.698727446 * (temp - 60.0).pow(-0.1332047592)
            g = 288.1221695283 * (temp - 60.0).pow(-0.0755148492)
            b = 255.0
        }
        // Invert tint of blackbody illuminant to balance white
        val rGain = (255.0 / r.coerceIn(40.0, 255.0)).toFloat() * 1.4f
        val gGain = (255.0 / g.coerceIn(40.0, 255.0)).toFloat() * 1.0f
        val bGain = (255.0 / b.coerceIn(40.0, 255.0)).toFloat() * 1.4f
        return RggbChannelVector(
            rGain.coerceIn(0.6f, 4.0f),
            gGain.coerceIn(0.6f, 2.0f),
            gGain.coerceIn(0.6f, 2.0f),
            bGain.coerceIn(0.6f, 4.0f)
        )
    }
}
