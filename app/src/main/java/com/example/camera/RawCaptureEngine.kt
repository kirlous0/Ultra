package com.example.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.location.Location
import android.media.Image
import androidx.exifinterface.media.ExifInterface
import com.example.processing.ExifProcessor
import com.example.system.SavedMediaResult
import com.example.system.StorageManager
import com.example.system.UltraCamSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class RawCaptureEngine(
    private val context: Context,
    private val storageManager: StorageManager
) {

    /**
     * Writes an authentic Adobe DNG file using Camera2 DngCreator from raw sensor Image
     * and TotalCaptureResult metadata, then enriches IFD0 & ExifIFD with device, sensor,
     * lens, and manual setting metadata via ExifProcessor.
     */
    suspend fun saveDng(
        caps: CameraDeviceCapabilities,
        controls: ProControlsState,
        rawImage: Image,
        captureResult: TotalCaptureResult,
        deviceOrientationDeg: Int,
        settings: UltraCamSettings,
        location: Location?,
        aiSceneName: String? = null,
        focusPowerMode: Boolean = false,
        tapMetered: Boolean = false
    ): SavedMediaResult? = withContext(Dispatchers.IO) {
        val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = camMgr.getCameraCharacteristics(caps.cameraId)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val isFront = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

        val jpegOrientation = if (isFront) {
            (sensorOrientation - deviceOrientationDeg + 360) % 360
        } else {
            (sensorOrientation + deviceOrientationDeg) % 360
        }

        val exifOrientation = when (jpegOrientation) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }

        val payload = ExifProcessor.buildPayload(
            caps = caps,
            controls = controls,
            settings = settings,
            captureResult = captureResult,
            modeTag = if (controls.isManualMode) "PRO_RAW" else "RAW",
            frameCount = 1,
            focusPowerMode = focusPowerMode,
            aiSceneName = aiSceneName,
            imageWidth = rawImage.width,
            imageHeight = rawImage.height,
            exifOrientation = exifOrientation,
            location = location,
            tapMetered = tapMetered
        )

        val fileName = storageManager.buildUniqueFilename(settings.filenamePrefix, "RAW", "dng")
        val target = storageManager.createStreamTargetForDng(fileName, settings)
            ?: return@withContext null

        val (uri, outputStream) = target
        var written = false
        var finalSize = 0L
        try {
            val rawDngBaos = ByteArrayOutputStream()
            DngCreator(chars, captureResult).use { dng ->
                dng.setOrientation(exifOrientation)
                if (location != null && settings.gpsExifEnabled) {
                    dng.setLocation(location)
                }
                dng.setDescription(payload.buildDescriptionSummary())
                dng.writeImage(rawDngBaos, rawImage)
            }
            val rawDngBytes = rawDngBaos.toByteArray()
            val enrichedDngBytes = runCatching {
                ExifProcessor.patchDngBytes(rawDngBytes, payload)
            }.getOrDefault(rawDngBytes)

            outputStream.write(enrichedDngBytes)
            outputStream.flush()
            finalSize = enrichedDngBytes.size.toLong()
            written = true
        } finally {
            runCatching { outputStream.close() }
            if (written) storageManager.finalizeMediaStoreEntry(uri)
        }

        if (!written) return@withContext null
        SavedMediaResult(
            uri = uri,
            displayName = fileName,
            mimeType = "image/x-adobe-dng",
            byteSize = finalSize
        )
    }
}
