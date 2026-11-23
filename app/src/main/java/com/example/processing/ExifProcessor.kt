package com.example.processing

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.location.Location
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.exifinterface.media.ExifInterface
import com.example.BuildConfig
import com.example.camera.CameraDeviceCapabilities
import com.example.camera.FlashMode
import com.example.camera.LensRole
import com.example.camera.ProControlsState
import com.example.camera.WhiteBalancePreset
import com.example.system.UltraCamSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Immutable
data class CaptureExifPayload(
    val deviceMake: String,
    val deviceModel: String,
    val software: String,
    val lensMake: String,
    val lensModel: String,
    val cameraId: String,
    val lensRole: LensRole,
    val hardwareLevelName: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val exifOrientation: Int,
    val iso: Int?,
    val exposureTimeNs: Long?,
    val aperture: Float?,
    val focalLengthMm: Float?,
    val focalLength35mm: Int?,
    val focusDistanceDiopters: Float?,
    val exposureBiasEv: Float,
    val isManualMode: Boolean,
    val manualExposureEnabled: Boolean,
    val manualFocusEnabled: Boolean,
    val aeLocked: Boolean,
    val afLocked: Boolean,
    val whiteBalancePreset: WhiteBalancePreset,
    val manualKelvin: Int,
    val flashMode: FlashMode,
    val flashFired: Boolean,
    val hasFlash: Boolean,
    val digitalZoomRatio: Float,
    val modeTag: String,
    val frameCount: Int,
    val focusPowerMode: Boolean,
    val aiSceneName: String?,
    val libPatcher: LibPatcherConfig,
    val tapMetered: Boolean,
    val captureTimestampMs: Long,
    val location: Location?,
    val uniqueId: String
) {
    fun buildDescriptionSummary(): String = buildString {
        append("UltraCam ").append(modeTag)
        if (frameCount > 1) append(" ${frameCount}F")
        append(" | ").append(libPatcher.colorProfile.badge)
        append(" | ").append(libPatcher.hdrToneStyle.name)
        if (manualExposureEnabled && iso != null && exposureTimeNs != null) {
            append(" | M-EXP ISO ").append(iso).append(' ').append(formatShutterShort(exposureTimeNs))
        } else if (abs(exposureBiasEv) >= 0.05f) {
            append(String.format(Locale.US, " | EV %+.2f", exposureBiasEv))
        }
        if (manualFocusEnabled && focusDistanceDiopters != null) {
            append(String.format(Locale.US, " | MF %.2fD", focusDistanceDiopters))
        }
        if (focusPowerMode) append(" | TURBO100")
    }

    fun buildStructuredUserComment(): String {
        val fields = mutableListOf<String>()
        fun addStr(k: String, v: String) {
            val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
            fields += "\"$k\":\"$escaped\""
        }
        fun addNum(k: String, v: Any) {
            fields += "\"$k\":$v"
        }
        addStr("app", "UltraCam ${BuildConfig.VERSION_NAME}")
        addStr("cameraId", cameraId)
        addStr("lensRole", lensRole.name)
        addStr("hwLevel", hardwareLevelName)
        addStr("mode", modeTag)
        addNum("frames", frameCount)
        addNum("focusTurbo", focusPowerMode)
        if (!aiSceneName.isNullOrBlank()) addStr("aiScene", aiSceneName)
        addNum("manualMode", isManualMode)
        addNum("manualExposure", manualExposureEnabled)
        addNum("manualFocus", manualFocusEnabled)
        if (focusDistanceDiopters != null) addStr("focusDiopters", String.format(Locale.US, "%.2f", focusDistanceDiopters))
        addNum("aeLock", aeLocked)
        addNum("afLock", afLocked)
        addStr("evBias", String.format(Locale.US, "%+.2f", exposureBiasEv))
        addStr("wbPreset", whiteBalancePreset.name)
        if (whiteBalancePreset == WhiteBalancePreset.MANUAL_KELVIN) addNum("kelvin", manualKelvin)
        addStr("zoom", String.format(Locale.US, "%.2fx", digitalZoomRatio))
        addStr("colorProfile", libPatcher.colorProfile.name)
        addStr("hdrToneStyle", libPatcher.hdrToneStyle.name)
        addStr("sharpness", String.format(Locale.US, "%.2f", libPatcher.sharpness))
        addStr("clarity", String.format(Locale.US, "%.2f", libPatcher.clarity))
        addStr("lumaDenoise", String.format(Locale.US, "%.2f", libPatcher.lumaDenoise))
        addStr("chromaDenoise", String.format(Locale.US, "%.2f", libPatcher.chromaDenoise))
        addStr("dehaze", String.format(Locale.US, "%.2f", libPatcher.dehaze))
        addNum("skinProtect", libPatcher.skinToneProtect)
        return fields.joinToString(prefix = "{", postfix = "}", separator = ",")
    }

    private fun formatShutterShort(ns: Long): String {
        if (ns <= 0L) return "Auto"
        return if (ns >= 400_000_000L) {
            String.format(Locale.US, "%.1fs", ns / 1_000_000_000.0)
        } else {
            "1/${(1_000_000_000.0 / ns).roundToInt().coerceAtLeast(1)}s"
        }
    }
}

object ExifProcessor {

    private val PRESERVABLE_HAL_TAGS = arrayOf(
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_MAKER_NOTE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED
    )

    fun buildPayload(
        caps: CameraDeviceCapabilities,
        controls: ProControlsState,
        settings: UltraCamSettings,
        captureResult: TotalCaptureResult?,
        modeTag: String,
        frameCount: Int = 1,
        focusPowerMode: Boolean = false,
        aiSceneName: String? = null,
        imageWidth: Int = 0,
        imageHeight: Int = 0,
        exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL,
        location: Location? = null,
        tapMetered: Boolean = false
    ): CaptureExifPayload {
        val rawMfg = Build.MANUFACTURER.orEmpty().trim().ifBlank { "Android" }
        val normalizedMake = rawMfg.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
        }
        val model = Build.MODEL.orEmpty().trim().ifBlank { "Camera2" }

        val iso = captureResult?.get(TotalCaptureResult.SENSOR_SENSITIVITY)
            ?: if (controls.manualExposureEnabled) controls.iso else null
        val expNs = captureResult?.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME)
            ?: if (controls.manualExposureEnabled) controls.exposureTimeNs else null
        val aperture = captureResult?.get(CaptureResult.LENS_APERTURE)
            ?: caps.apertures.firstOrNull()
        val focalMm = captureResult?.get(CaptureResult.LENS_FOCAL_LENGTH)
            ?: caps.focalLengthsMm.firstOrNull()
        val focal35mm = compute35mmEquivalentFocalLength(focalMm, caps.physicalSizeMm)

        val focusDiopters = if (caps.supportsManualFocus) {
            captureResult?.get(CaptureResult.LENS_FOCUS_DISTANCE)
                ?: if (controls.manualFocusEnabled) controls.focusDistanceDiopters else null
        } else {
            null // Fixed-focus front camera: never fabricate manual focus distance
        }

        val flashState = captureResult?.get(CaptureResult.FLASH_STATE)
        val flashFired = caps.hasFlash && (
            flashState == CaptureResult.FLASH_STATE_FIRED ||
                controls.flashMode == FlashMode.ON ||
                controls.flashMode == FlashMode.TORCH
            )

        val evBias = if (controls.manualExposureEnabled) {
            0f
        } else {
            controls.exposureCompensationSteps * caps.aeCompensationStep
        }

        val roleLabel = when (caps.lensRole) {
            LensRole.MAIN -> "Rear Main"
            LensRole.MACRO -> "Rear Macro"
            LensRole.FRONT -> "Front"
            LensRole.AUXILIARY -> "Rear Aux"
        }
        val focalText = focalMm?.let { String.format(Locale.US, " %.2fmm", it) } ?: ""
        val apText = aperture?.let { String.format(Locale.US, " f/%.1f", it) } ?: ""
        val mpText = if (caps.megapixelEstimate >= 1.0) {
            " (${caps.megapixelEstimate.roundToInt()}MP · ID ${caps.cameraId})"
        } else {
            " (ID ${caps.cameraId})"
        }
        val lensModel = "$roleLabel$focalText$apText$mpText".trim()

        val socTag = Build.HARDWARE.orEmpty().trim()
        val software = buildString {
            append("UltraCam ").append(BuildConfig.VERSION_NAME)
            if (socTag.isNotBlank()) append(" [").append(socTag).append(']')
            append(" · ").append(settings.libPatcher.colorProfile.badge)
        }

        return CaptureExifPayload(
            deviceMake = normalizedMake,
            deviceModel = model,
            software = software,
            lensMake = normalizedMake,
            lensModel = lensModel,
            cameraId = caps.cameraId,
            lensRole = caps.lensRole,
            hardwareLevelName = caps.hardwareLevelName,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            exifOrientation = exifOrientation,
            iso = iso,
            exposureTimeNs = expNs,
            aperture = aperture,
            focalLengthMm = focalMm,
            focalLength35mm = focal35mm,
            focusDistanceDiopters = focusDiopters,
            exposureBiasEv = evBias,
            isManualMode = controls.isManualMode,
            manualExposureEnabled = controls.manualExposureEnabled && caps.supportsManualSensor,
            manualFocusEnabled = controls.manualFocusEnabled && caps.supportsManualFocus,
            aeLocked = controls.aeLocked && caps.supportsAeLock,
            afLocked = controls.afLocked && caps.supportsManualFocus,
            whiteBalancePreset = controls.whiteBalancePreset,
            manualKelvin = controls.manualKelvin,
            flashMode = if (caps.hasFlash) controls.flashMode else FlashMode.OFF,
            flashFired = flashFired,
            hasFlash = caps.hasFlash,
            digitalZoomRatio = controls.digitalZoomRatio.coerceAtLeast(1f),
            modeTag = modeTag,
            frameCount = frameCount.coerceAtLeast(1),
            focusPowerMode = focusPowerMode,
            aiSceneName = aiSceneName,
            libPatcher = settings.libPatcher,
            tapMetered = tapMetered,
            captureTimestampMs = System.currentTimeMillis(),
            location = if (settings.gpsExifEnabled) location else null,
            uniqueId = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)
        )
    }

    /**
     * Reads the orientation rotation degrees stored inside HAL-encoded JPEG bytes so bitmaps
     * can be rotated physically upright before computational processing or watermarking.
     */
    fun readJpegOrientationDegrees(jpegBytes: ByteArray): Int {
        return runCatching {
            ByteArrayInputStream(jpegBytes).use { input ->
                val exif = ExifInterface(input)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrDefault(0)
    }

    /**
     * Injects complete device, sensor, manual setting, and GPS EXIF metadata into JPEG bytes.
     * Optionally preserves original HAL MakerNote/radiometric tags from [sourceHalJpegBytes].
     */
    fun injectJpegExifBytes(
        context: Context,
        jpegBytes: ByteArray,
        payload: CaptureExifPayload,
        sourceHalJpegBytes: ByteArray? = null
    ): ByteArray {
        val tempFile = File.createTempFile("ucam_exif_", ".jpg", context.cacheDir)
        return try {
            tempFile.writeBytes(jpegBytes)
            val exif = ExifInterface(tempFile)

            // Copy non-conflicting HAL tags (e.g., MakerNote, BrightnessValue) if re-encoding
            if (sourceHalJpegBytes != null) {
                runCatching {
                    ByteArrayInputStream(sourceHalJpegBytes).use { srcStream ->
                        val srcExif = ExifInterface(srcStream)
                        for (tag in PRESERVABLE_HAL_TAGS) {
                            val v = srcExif.getAttribute(tag)
                            if (!v.isNullOrBlank()) {
                                exif.setAttribute(tag, v)
                            }
                        }
                        if (payload.location == null) {
                            srcExif.latLong?.let { latLng ->
                                val loc = Location("exif").apply {
                                    latitude = latLng[0]
                                    longitude = latLng[1]
                                }
                                exif.setGpsInfo(loc)
                            }
                        }
                    }
                }
            }

            applyPayloadToExifInterface(exif, payload)
            exif.saveAttributes()
            tempFile.readBytes()
        } catch (_: Throwable) {
            jpegBytes
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    /**
     * Preserves all sensor/device EXIF tags from an original gallery photo when exporting
     * an edited JPEG from the Studio, while tagging the exported resolution, orientation,
     * Software string, and Studio edit parameters.
     */
    fun injectStudioExportExif(
        context: Context,
        exportedJpegBytes: ByteArray,
        sourceUri: android.net.Uri,
        params: StudioEditParams,
        outWidth: Int,
        outHeight: Int
    ): ByteArray {
        val tempFile = File.createTempFile("ucam_studio_exif_", ".jpg", context.cacheDir)
        return try {
            tempFile.writeBytes(exportedJpegBytes)
            val dst = ExifInterface(tempFile)

            runCatching {
                context.contentResolver.openInputStream(sourceUri)?.use { srcIn ->
                    val src = ExifInterface(srcIn)
                    val copyTags = arrayOf(
                        ExifInterface.TAG_MAKE,
                        ExifInterface.TAG_MODEL,
                        ExifInterface.TAG_LENS_MAKE,
                        ExifInterface.TAG_LENS_MODEL,
                        ExifInterface.TAG_EXPOSURE_TIME,
                        ExifInterface.TAG_F_NUMBER,
                        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                        ExifInterface.TAG_ISO_SPEED,
                        ExifInterface.TAG_FOCAL_LENGTH,
                        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                        ExifInterface.TAG_EXPOSURE_MODE,
                        ExifInterface.TAG_EXPOSURE_PROGRAM,
                        ExifInterface.TAG_WHITE_BALANCE,
                        ExifInterface.TAG_FLASH,
                        ExifInterface.TAG_DATETIME_ORIGINAL,
                        ExifInterface.TAG_DATETIME_DIGITIZED,
                        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                        ExifInterface.TAG_SUBJECT_DISTANCE,
                        ExifInterface.TAG_DIGITAL_ZOOM_RATIO
                    )
                    for (tag in copyTags) {
                        val v = src.getAttribute(tag)
                        if (!v.isNullOrBlank()) {
                            runCatching { dst.setAttribute(tag, v) }
                        }
                    }
                    src.latLong?.let { latLng ->
                        dst.setGpsInfo(
                            Location("studio").apply {
                                latitude = latLng[0]
                                longitude = latLng[1]
                            }
                        )
                    }
                }
            }

            val nowMs = System.currentTimeMillis()
            val dt = formatExifDateTime(nowMs)
            dst.setAttribute(ExifInterface.TAG_DATETIME, dt)
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            if (outWidth > 0 && outHeight > 0) {
                dst.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, outWidth.toString())
                dst.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, outHeight.toString())
                dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, outWidth.toString())
                dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, outHeight.toString())
            }
            dst.setAttribute(
                ExifInterface.TAG_SOFTWARE,
                "UltraCam Studio ${BuildConfig.VERSION_NAME} · ${params.colorProfile.badge}"
            )
            dst.setAttribute(
                ExifInterface.TAG_IMAGE_DESCRIPTION,
                "UltraCam STUDIO | ${params.colorProfile.badge} | EV ${String.format(Locale.US, "%+.2f", params.exposureEv)}"
            )
            dst.setAttribute(
                ExifInterface.TAG_USER_COMMENT,
                buildString {
                    append("{\"app\":\"UltraCam Studio ").append(BuildConfig.VERSION_NAME).append('"')
                    append(",\"colorProfile\":\"").append(params.colorProfile.name).append('"')
                    append(",\"exposureEv\":\"").append(String.format(Locale.US, "%.2f", params.exposureEv)).append('"')
                    append(",\"contrast\":\"").append(String.format(Locale.US, "%.2f", params.contrast)).append('"')
                    append(",\"highlights\":\"").append(String.format(Locale.US, "%.2f", params.highlights)).append('"')
                    append(",\"shadows\":\"").append(String.format(Locale.US, "%.2f", params.shadows)).append('"')
                    append(",\"saturation\":\"").append(String.format(Locale.US, "%.2f", params.saturation)).append('"')
                    append(",\"vibrance\":\"").append(String.format(Locale.US, "%.2f", params.vibrance)).append('"')
                    append(",\"sharpness\":\"").append(String.format(Locale.US, "%.2f", params.sharpness)).append('"')
                    append('}')
                }
            )
            dst.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1") // sRGB
            dst.saveAttributes()
            tempFile.readBytes()
        } catch (_: Throwable) {
            exportedJpegBytes
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun applyPayloadToExifInterface(exif: ExifInterface, p: CaptureExifPayload) {
        val dateStr = formatExifDateTime(p.captureTimestampMs)
        val subSec = (p.captureTimestampMs % 1000L).toString().padStart(3, '0')
        val offsetStr = formatTimezoneOffset(p.captureTimestampMs)

        exif.setAttribute(ExifInterface.TAG_MAKE, p.deviceMake)
        exif.setAttribute(ExifInterface.TAG_MODEL, p.deviceModel)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, p.software)
        exif.setAttribute(ExifInterface.TAG_LENS_MAKE, p.lensMake)
        exif.setAttribute(ExifInterface.TAG_LENS_MODEL, p.lensModel)
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, p.buildDescriptionSummary())
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, p.buildStructuredUserComment())
        exif.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, p.uniqueId)

        exif.setAttribute(ExifInterface.TAG_ORIENTATION, p.exifOrientation.toString())
        if (p.imageWidth > 0 && p.imageHeight > 0) {
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, p.imageWidth.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, p.imageHeight.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, p.imageWidth.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, p.imageHeight.toString())
        }

        exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME, subSec)
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subSec)
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, subSec)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offsetStr)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offsetStr)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offsetStr)

        p.iso?.takeIf { it > 0 }?.let { isoVal ->
            exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, isoVal.toString())
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED, isoVal.toString())
        }

        p.exposureTimeNs?.takeIf { it > 0L }?.let { ns ->
            val sec = ns / 1_000_000_000.0
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.format(Locale.US, "%.8f", sec))
            val tvApex = -(ln(sec) / ln(2.0))
            exif.setAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE, toSignedRational(tvApex, 1000))
        }

        p.aperture?.takeIf { it > 0f }?.let { fNum ->
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, String.format(Locale.US, "%.2f", fNum))
            val avApex = 2.0 * (ln(fNum.toDouble()) / ln(2.0))
            exif.setAttribute(ExifInterface.TAG_APERTURE_VALUE, toUnsignedRational(avApex, 1000))
        }

        p.focalLengthMm?.takeIf { it > 0f }?.let { focal ->
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, toUnsignedRational(focal.toDouble(), 100))
        }
        p.focalLength35mm?.takeIf { it > 0 }?.let { eq35 ->
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, eq35.toString())
        }

        val exposureMode = when {
            p.modeTag.startsWith("AEB") -> 2 // Auto bracket
            p.manualExposureEnabled -> 1     // Manual exposure
            else -> 0                        // Auto exposure
        }
        exif.setAttribute(ExifInterface.TAG_EXPOSURE_MODE, exposureMode.toString())
        exif.setAttribute(
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            if (p.manualExposureEnabled) "1" else "2"
        )
        exif.setAttribute(
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            toSignedRational(p.exposureBiasEv.toDouble(), 100)
        )

        val wbCode = if (p.whiteBalancePreset == WhiteBalancePreset.AUTO) {
            ExifInterface.WHITE_BALANCE_AUTO
        } else {
            ExifInterface.WHITE_BALANCE_MANUAL
        }
        exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, wbCode.toString())

        exif.setAttribute(ExifInterface.TAG_FLASH, computeFlashExifBitmask(p).toString())
        exif.setAttribute(
            ExifInterface.TAG_METERING_MODE,
            if (p.tapMetered) "3" else "5"
        )

        p.focusDistanceDiopters?.takeIf { it > 0.02f }?.let { diopters ->
            val meters = (1.0 / diopters).coerceIn(0.02, 200.0)
            exif.setAttribute(ExifInterface.TAG_SUBJECT_DISTANCE, toUnsignedRational(meters, 100))
            val rangeCode = when {
                p.lensRole == LensRole.MACRO || meters < 0.35 -> "1" // Macro
                meters < 2.5 -> "2"                                   // Close
                else -> "3"                                           // Distant
            }
            exif.setAttribute(ExifInterface.TAG_SUBJECT_DISTANCE_RANGE, rangeCode)
        }

        exif.setAttribute(
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            toUnsignedRational(p.digitalZoomRatio.toDouble().coerceAtLeast(1.0), 100)
        )
        exif.setAttribute(
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            computeSceneCaptureType(p.modeTag, p.aiSceneName).toString()
        )
        exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1") // sRGB
        exif.setAttribute(ExifInterface.TAG_SENSING_METHOD, "2") // One-chip Bayer CMOS

        if (p.location != null) {
            exif.setGpsInfo(p.location)
        }
    }

    /**
     * Patches DNG (TIFF-EP) IFD0 & ExifIFD metadata non-destructively at EOF so all existing
     * raw SubIFDs, pixel strip/tile offsets, color matrices, and calibration transforms remain
     * at their exact original byte offsets while device, sensor, and manual tags are enriched.
     */
    fun patchDngBytes(dngBytes: ByteArray, p: CaptureExifPayload): ByteArray {
        if (dngBytes.size < 16) return dngBytes
        val byteOrder = when {
            dngBytes[0] == 'I'.code.toByte() && dngBytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            dngBytes[0] == 'M'.code.toByte() && dngBytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return dngBytes
        }
        val buf = ByteBuffer.wrap(dngBytes).order(byteOrder)
        val magic = buf.getShort(2).toInt() and 0xFFFF
        if (magic != 42) return dngBytes

        val origIfd0Offset = buf.getInt(4).toLong() and 0xFFFFFFFFL
        if (origIfd0Offset < 8L || origIfd0Offset + 6L > dngBytes.size) return dngBytes

        val ifd0Parsed = readRawIfd(buf, origIfd0Offset.toInt(), dngBytes.size) ?: return dngBytes
        val origExifPtrEntry = ifd0Parsed.entries[TAG_EXIF_IFD_POINTER]
        val origExifOffset = origExifPtrEntry?.let { entryValueAsInt(it, byteOrder) }
        val exifParsed = if (origExifOffset != null && origExifOffset in 8 until (dngBytes.size - 6)) {
            readRawIfd(buf, origExifOffset, dngBytes.size)
        } else null

        val extraPayloads = ByteArrayOutputStream()
        val baseEof = dngBytes.size

        fun allocPayload(data: ByteArray): Int {
            // Keep word alignment
            if ((baseEof + extraPayloads.size()) % 2 != 0) extraPayloads.write(0)
            val offset = baseEof + extraPayloads.size()
            extraPayloads.write(data)
            return offset
        }

        fun makeAsciiEntry(tag: Int, text: String): TiffEntry {
            val bytes = (text + "\u0000").toByteArray(Charsets.US_ASCII)
            val inline = ByteArray(4)
            val valueOrOffset = if (bytes.size <= 4) {
                bytes.copyInto(inline)
                ByteBuffer.wrap(inline).order(byteOrder).int
            } else {
                allocPayload(bytes)
            }
            return TiffEntry(tag, TYPE_ASCII, bytes.size, valueOrOffset)
        }

        fun makeShortEntry(tag: Int, value: Int): TiffEntry {
            val raw = ByteArray(4)
            ByteBuffer.wrap(raw).order(byteOrder).putShort(0, value.toShort())
            return TiffEntry(tag, TYPE_SHORT, 1, ByteBuffer.wrap(raw).order(byteOrder).int)
        }

        fun makeRationalEntry(tag: Int, num: Int, den: Int, signed: Boolean): TiffEntry {
            val data = ByteArray(8)
            ByteBuffer.wrap(data).order(byteOrder).apply {
                putInt(num)
                putInt(den.coerceAtLeast(1))
            }
            val off = allocPayload(data)
            return TiffEntry(tag, if (signed) TYPE_SRATIONAL else TYPE_URATIONAL, 1, off)
        }

        fun makeUserCommentEntry(comment: String): TiffEntry {
            val prefix = byteArrayOf('A'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte(), 'I'.code.toByte(), 'I'.code.toByte(), 0, 0, 0)
            val body = comment.toByteArray(Charsets.UTF_8)
            val all = prefix + body
            val off = allocPayload(all)
            return TiffEntry(TAG_USER_COMMENT, TYPE_UNDEFINED, all.size, off)
        }

        val dateStr = formatExifDateTime(p.captureTimestampMs)
        val subSec = (p.captureTimestampMs % 1000L).toString().padStart(3, '0')

        // Build enriched ExifIFD entries preserving all existing camera sensor/makernote tags
        val exifMap = LinkedHashMap<Int, TiffEntry>(exifParsed?.entries ?: emptyMap())
        p.exposureTimeNs?.takeIf { it > 0L }?.let { ns ->
            val sec = ns / 1_000_000_000.0
            val (n, d) = rationalPair(sec, 1_000_000)
            exifMap[TAG_EXPOSURE_TIME] = makeRationalEntry(TAG_EXPOSURE_TIME, n, d, signed = false)
            val tvApex = -(ln(sec) / ln(2.0))
            val (sn, sd) = rationalPair(tvApex, 1000)
            exifMap[TAG_SHUTTER_SPEED_VALUE] = makeRationalEntry(TAG_SHUTTER_SPEED_VALUE, sn, sd, signed = true)
        }
        p.aperture?.takeIf { it > 0f }?.let { fNum ->
            val (n, d) = rationalPair(fNum.toDouble(), 100)
            exifMap[TAG_F_NUMBER] = makeRationalEntry(TAG_F_NUMBER, n, d, signed = false)
            val avApex = 2.0 * (ln(fNum.toDouble()) / ln(2.0))
            val (an, ad) = rationalPair(avApex, 1000)
            exifMap[TAG_APERTURE_VALUE] = makeRationalEntry(TAG_APERTURE_VALUE, an, ad, signed = false)
        }
        exifMap[TAG_EXPOSURE_PROGRAM] = makeShortEntry(TAG_EXPOSURE_PROGRAM, if (p.manualExposureEnabled) 1 else 2)
        p.iso?.takeIf { it > 0 }?.let { isoVal ->
            exifMap[TAG_ISO_SPEED_RATINGS] = makeShortEntry(TAG_ISO_SPEED_RATINGS, isoVal.coerceAtMost(65535))
        }
        exifMap[TAG_DATETIME_ORIGINAL] = makeAsciiEntry(TAG_DATETIME_ORIGINAL, dateStr)
        exifMap[TAG_DATETIME_DIGITIZED] = makeAsciiEntry(TAG_DATETIME_DIGITIZED, dateStr)
        val (evN, evD) = rationalPair(p.exposureBiasEv.toDouble(), 100)
        exifMap[TAG_EXPOSURE_BIAS_VALUE] = makeRationalEntry(TAG_EXPOSURE_BIAS_VALUE, evN, evD, signed = true)
        p.focusDistanceDiopters?.takeIf { it > 0.02f }?.let { diopters ->
            val meters = (1.0 / diopters).coerceIn(0.02, 200.0)
            val (mn, md) = rationalPair(meters, 100)
            exifMap[TAG_SUBJECT_DISTANCE] = makeRationalEntry(TAG_SUBJECT_DISTANCE, mn, md, signed = false)
            val rangeCode = when {
                p.lensRole == LensRole.MACRO || meters < 0.35 -> 1
                meters < 2.5 -> 2
                else -> 3
            }
            exifMap[TAG_SUBJECT_DISTANCE_RANGE] = makeShortEntry(TAG_SUBJECT_DISTANCE_RANGE, rangeCode)
        }
        exifMap[TAG_METERING_MODE] = makeShortEntry(TAG_METERING_MODE, if (p.tapMetered) 3 else 5)
        exifMap[TAG_FLASH] = makeShortEntry(TAG_FLASH, computeFlashExifBitmask(p))
        p.focalLengthMm?.takeIf { it > 0f }?.let { focal ->
            val (fn, fd) = rationalPair(focal.toDouble(), 100)
            exifMap[TAG_FOCAL_LENGTH] = makeRationalEntry(TAG_FOCAL_LENGTH, fn, fd, signed = false)
        }
        exifMap[TAG_USER_COMMENT] = makeUserCommentEntry(p.buildStructuredUserComment())
        exifMap[TAG_SUBSEC_TIME] = makeAsciiEntry(TAG_SUBSEC_TIME, subSec)
        exifMap[TAG_SUBSEC_TIME_ORIGINAL] = makeAsciiEntry(TAG_SUBSEC_TIME_ORIGINAL, subSec)
        val expMode = when {
            p.modeTag.startsWith("AEB") -> 2
            p.manualExposureEnabled -> 1
            else -> 0
        }
        exifMap[TAG_EXPOSURE_MODE] = makeShortEntry(TAG_EXPOSURE_MODE, expMode)
        exifMap[TAG_WHITE_BALANCE] = makeShortEntry(
            TAG_WHITE_BALANCE,
            if (p.whiteBalancePreset == WhiteBalancePreset.AUTO) 0 else 1
        )
        val (zn, zd) = rationalPair(p.digitalZoomRatio.toDouble().coerceAtLeast(1.0), 100)
        exifMap[TAG_DIGITAL_ZOOM_RATIO] = makeRationalEntry(TAG_DIGITAL_ZOOM_RATIO, zn, zd, signed = false)
        p.focalLength35mm?.takeIf { it > 0 }?.let { eq35 ->
            exifMap[TAG_FOCAL_LENGTH_IN_35MM] = makeShortEntry(TAG_FOCAL_LENGTH_IN_35MM, eq35)
        }
        exifMap[TAG_SCENE_CAPTURE_TYPE] = makeShortEntry(TAG_SCENE_CAPTURE_TYPE, computeSceneCaptureType(p.modeTag, p.aiSceneName))
        exifMap[TAG_IMAGE_UNIQUE_ID] = makeAsciiEntry(TAG_IMAGE_UNIQUE_ID, p.uniqueId)
        exifMap[TAG_LENS_MAKE] = makeAsciiEntry(TAG_LENS_MAKE, p.lensMake)
        exifMap[TAG_LENS_MODEL] = makeAsciiEntry(TAG_LENS_MODEL, p.lensModel)

        // Write updated ExifIFD block at EOF
        val newExifIfdOffset = allocPayload(serializeIfd(exifMap.values, 0, byteOrder))

        // Build enriched IFD0 preserving all SubIFD / DNG colorimetric / strip offsets verbatim
        val ifd0Map = LinkedHashMap<Int, TiffEntry>(ifd0Parsed.entries)
        ifd0Map[TAG_IMAGE_DESCRIPTION] = makeAsciiEntry(TAG_IMAGE_DESCRIPTION, p.buildDescriptionSummary())
        ifd0Map[TAG_MAKE] = makeAsciiEntry(TAG_MAKE, p.deviceMake)
        ifd0Map[TAG_MODEL] = makeAsciiEntry(TAG_MODEL, p.deviceModel)
        ifd0Map[TAG_SOFTWARE] = makeAsciiEntry(TAG_SOFTWARE, p.software)
        ifd0Map[TAG_DATETIME] = makeAsciiEntry(TAG_DATETIME, dateStr)
        ifd0Map[TAG_EXIF_IFD_POINTER] = TiffEntry(TAG_EXIF_IFD_POINTER, TYPE_LONG, 1, newExifIfdOffset)

        val newIfd0Offset = allocPayload(serializeIfd(ifd0Map.values, ifd0Parsed.nextIfdOffset, byteOrder))

        val outBytes = ByteArray(dngBytes.size + extraPayloads.size())
        System.arraycopy(dngBytes, 0, outBytes, 0, dngBytes.size)
        val appended = extraPayloads.toByteArray()
        System.arraycopy(appended, 0, outBytes, dngBytes.size, appended.size)

        // Update only IFD0 header offset at bytes 4..7
        ByteBuffer.wrap(outBytes).order(byteOrder).putInt(4, newIfd0Offset)
        return outBytes
    }

    fun compute35mmEquivalentFocalLength(focalLengthMm: Float?, physicalSizeMm: String?): Int? {
        if (focalLengthMm == null || focalLengthMm <= 0f || physicalSizeMm.isNullOrBlank()) return null
        val dims = Regex("""(\d+(?:\.\d+)?)x(\d+(?:\.\d+)?)""").find(physicalSizeMm) ?: return null
        val w = dims.groupValues[1].toDoubleOrNull() ?: return null
        val h = dims.groupValues[2].toDoubleOrNull() ?: return null
        val sensorDiag = hypot(w, h)
        if (sensorDiag < 0.5) return null
        // 35mm full-frame diagonal is sqrt(36^2 + 24^2) ~= 43.2666mm
        return ((43.2666 / sensorDiag) * focalLengthMm).roundToInt().coerceIn(8, 600)
    }

    fun computeFlashExifBitmask(p: CaptureExifPayload): Int {
        if (!p.hasFlash) return 32 // No flash function
        if (p.flashFired) {
            return if (p.flashMode == FlashMode.AUTO) 25 else 9 // Auto fired / Compulsory fired
        }
        return when (p.flashMode) {
            FlashMode.OFF -> 16  // Off, did not fire
            FlashMode.AUTO -> 24 // Auto, did not fire
            else -> 0
        }
    }

    fun computeSceneCaptureType(modeTag: String, aiSceneName: String?): Int {
        return when {
            modeTag.contains("PORTRAIT", ignoreCase = true) ||
                aiSceneName.equals("PORTRAIT", ignoreCase = true) -> 2
            modeTag.contains("NIGHT", ignoreCase = true) ||
                modeTag.contains("ASTRO", ignoreCase = true) ||
                aiSceneName.equals("NIGHT", ignoreCase = true) -> 3
            aiSceneName.equals("LANDSCAPE", ignoreCase = true) ||
                modeTag.contains("PANO", ignoreCase = true) -> 1
            else -> 0
        }
    }

    fun toUnsignedRational(value: Double, scale: Int = 100): String {
        val (n, d) = rationalPair(abs(value), scale)
        return "$n/$d"
    }

    fun toSignedRational(value: Double, scale: Int = 100): String {
        val (n, d) = rationalPair(value, scale)
        return "$n/$d"
    }

    fun rationalPair(value: Double, scale: Int = 100): Pair<Int, Int> {
        val den = scale.coerceAtLeast(1)
        val rawNum = (value * den).roundToLong().coerceIn(Int.MIN_VALUE.toLong() / 2, Int.MAX_VALUE.toLong() / 2).toInt()
        if (rawNum == 0) return 0 to 1
        val g = gcd(abs(rawNum), den)
        return (rawNum / g) to (den / g)
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun formatExifDateTime(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        return fmt.format(Date(ms))
    }

    private fun formatTimezoneOffset(ms: Long): String {
        val offsetMin = TimeZone.getDefault().getOffset(ms) / 60_000
        val sign = if (offsetMin >= 0) "+" else "-"
        val absMin = abs(offsetMin)
        return String.format(Locale.US, "%s%02d:%02d", sign, absMin / 60, absMin % 60)
    }

    private data class TiffEntry(val tag: Int, val type: Int, val count: Int, val valueOrOffset: Int)
    private data class ParsedIfd(val entries: Map<Int, TiffEntry>, val nextIfdOffset: Int)

    private fun readRawIfd(buf: ByteBuffer, offset: Int, fileLen: Int): ParsedIfd? {
        if (offset < 8 || offset + 2 > fileLen) return null
        val count = buf.getShort(offset).toInt() and 0xFFFF
        val endBytes = offset + 2 + count * 12 + 4
        if (count > 512 || endBytes > fileLen) return null
        val entries = LinkedHashMap<Int, TiffEntry>(count)
        var pos = offset + 2
        for (i in 0 until count) {
            val tag = buf.getShort(pos).toInt() and 0xFFFF
            val type = buf.getShort(pos + 2).toInt() and 0xFFFF
            val cnt = buf.getInt(pos + 4)
            val valOff = buf.getInt(pos + 8)
            entries[tag] = TiffEntry(tag, type, cnt, valOff)
            pos += 12
        }
        val nextOffset = buf.getInt(pos)
        return ParsedIfd(entries, nextOffset)
    }

    private fun entryValueAsInt(entry: TiffEntry, order: ByteOrder): Int {
        return entry.valueOrOffset
    }

    private fun serializeIfd(entries: Collection<TiffEntry>, nextIfdOffset: Int, order: ByteOrder): ByteArray {
        val sorted = entries.sortedBy { it.tag and 0xFFFF }
        val bytes = ByteArray(2 + sorted.size * 12 + 4)
        val b = ByteBuffer.wrap(bytes).order(order)
        b.putShort(sorted.size.toShort())
        for (e in sorted) {
            b.putShort((e.tag and 0xFFFF).toShort())
            b.putShort((e.type and 0xFFFF).toShort())
            b.putInt(e.count)
            b.putInt(e.valueOrOffset)
        }
        b.putInt(nextIfdOffset)
        return bytes
    }

    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_URATIONAL = 5
    private const val TYPE_UNDEFINED = 7
    private const val TYPE_SRATIONAL = 10

    private const val TAG_IMAGE_DESCRIPTION = 0x010E
    private const val TAG_MAKE = 0x010F
    private const val TAG_MODEL = 0x0110
    private const val TAG_SOFTWARE = 0x0131
    private const val TAG_DATETIME = 0x0132
    private const val TAG_EXIF_IFD_POINTER = 0x8769

    private const val TAG_EXPOSURE_TIME = 0x829A
    private const val TAG_F_NUMBER = 0x829D
    private const val TAG_EXPOSURE_PROGRAM = 0x8822
    private const val TAG_ISO_SPEED_RATINGS = 0x8827
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_DATETIME_DIGITIZED = 0x9004
    private const val TAG_SHUTTER_SPEED_VALUE = 0x9201
    private const val TAG_APERTURE_VALUE = 0x9202
    private const val TAG_EXPOSURE_BIAS_VALUE = 0x9204
    private const val TAG_SUBJECT_DISTANCE = 0x9206
    private const val TAG_METERING_MODE = 0x9207
    private const val TAG_FLASH = 0x9209
    private const val TAG_FOCAL_LENGTH = 0x920A
    private const val TAG_USER_COMMENT = 0x9286
    private const val TAG_SUBSEC_TIME = 0x9290
    private const val TAG_SUBSEC_TIME_ORIGINAL = 0x9291
    private const val TAG_EXPOSURE_MODE = 0xA402
    private const val TAG_WHITE_BALANCE = 0xA403
    private const val TAG_DIGITAL_ZOOM_RATIO = 0xA404
    private const val TAG_FOCAL_LENGTH_IN_35MM = 0xA405
    private const val TAG_SCENE_CAPTURE_TYPE = 0xA406
    private const val TAG_SUBJECT_DISTANCE_RANGE = 0xA40C
    private const val TAG_IMAGE_UNIQUE_ID = 0xA420
    private const val TAG_LENS_MAKE = 0xA433
    private const val TAG_LENS_MODEL = 0xA434
}
