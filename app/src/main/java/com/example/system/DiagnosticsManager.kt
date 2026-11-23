package com.example.system

import com.example.camera.HardwareInventory
import org.json.JSONArray
import org.json.JSONObject

class DiagnosticsManager {

    fun buildJsonReport(
        inventory: HardwareInventory,
        health: SystemHealthState
    ): String {
        val root = JSONObject()
        root.put("schema", "ultracam.diagnostics.v1")
        root.put("scanned_at_ms", inventory.scanTimestampMs)

        val device = JSONObject().apply {
            put("manufacturer", inventory.deviceManufacturer)
            put("model", inventory.deviceModel)
            put("hardware", inventory.deviceHardware)
            put("soc", inventory.socModel)
            put("android_version", inventory.androidVersion)
            put("api_level", inventory.apiLevel)
            put("total_ram_mb", inventory.totalRamMb)
        }
        root.put("device", device)

        val systemHealth = JSONObject().apply {
            put("thermal_severity", health.thermalSeverity.name)
            put("battery_temp_c", health.batteryTempC.toDouble())
            put("battery_percent", health.batteryPercent)
            put("available_ram_mb", health.availableRamMb)
            put("low_memory", health.isLowMemory)
            put("adaptive_max_burst_frames", health.recommendedMaxBurstFrames)
            put("adaptive_night_frames", health.recommendedNightFrames)
            put("allow_super_resolution", health.allowSuperResolution)
        }
        root.put("runtime_telemetry", systemHealth)

        val encodersArr = JSONArray()
        inventory.videoEncoders.forEach { enc ->
            encodersArr.put(JSONObject().apply {
                put("codec", enc.codecType.label)
                put("mime_type", enc.codecType.mimeType)
                put("supported", enc.supported)
                put("codec_name", enc.codecName ?: JSONObject.NULL)
                put("max_width", enc.maxWidth)
                put("max_height", enc.maxHeight)
                put("max_bitrate_bps", enc.maxBitrateBps)
                put("supported_fps_1080p", enc.supportedFrameRates?.toString() ?: JSONObject.NULL)
            })
        }
        root.put("video_encoders", encodersArr)

        val camsArr = JSONArray()
        inventory.cameras.forEach { cam ->
            val obj = JSONObject().apply {
                put("camera_id", cam.cameraId)
                put("facing", cam.facingLabel)
                put("classified_role", cam.lensRole.name)
                put("usable_in_app", cam.isUsableInApp)
                put("usability_note", cam.usabilityNote)
                put("hardware_level", cam.hardwareLevelName)
                put("is_logical_multi_camera", cam.isLogicalMultiCamera)
                put("physical_camera_ids", JSONArray(cam.physicalCameraIds))
                put("capabilities", JSONArray(cam.capabilities))
                put("supports_raw", cam.supportsRaw)
                put("supports_manual_sensor", cam.supportsManualSensor)
                put("supports_manual_post_processing", cam.supportsManualPostProcessing)
                put("supports_burst_capture", cam.supportsBurstCapture)
                put("supports_yuv_reprocessing", cam.supportsYuvReprocessing)
                put("supports_constrained_high_speed", cam.supportsConstrainedHighSpeed)
                put("supports_face_detection", cam.supportsFaceDetection)
                put("max_face_count", cam.maxFaceCount)
                put("af_modes", JSONArray(cam.afModeNames))
                put("supports_manual_focus", cam.supportsManualFocus)
                put("minimum_focus_distance_diopters", cam.minimumFocusDistance.toDouble())
                put("hyperfocal_distance_diopters", cam.hyperfocalDistance.toDouble())
                put("focus_calibration", cam.focusDistanceCalibration)
                put("exposure_time_range_ns", cam.exposureTimeRangeNs?.let { "${it.first}..${it.last}" } ?: JSONObject.NULL)
                put("iso_range", cam.isoRange?.let { "${it.first}..${it.last}" } ?: JSONObject.NULL)
                put("ae_compensation_range", "${cam.aeCompensationRange.first}..${cam.aeCompensationRange.last}")
                put("ae_compensation_step", cam.aeCompensationStep.toDouble())
                put("awb_modes", JSONArray(cam.awbModeNames))
                put("has_flash", cam.hasFlash)
                put("sensor_orientation_deg", cam.sensorOrientation)
                put("pixel_array_size", cam.pixelArraySize?.toString() ?: JSONObject.NULL)
                put("physical_size_mm", cam.physicalSizeMm ?: JSONObject.NULL)
                put("apertures", JSONArray(cam.apertures.map { it.toDouble() }))
                put("focal_lengths_mm", JSONArray(cam.focalLengthsMm.map { it.toDouble() }))
                put("max_digital_zoom", cam.maxDigitalZoom.toDouble())
                put("supports_eis", cam.supportsEis)
                put("supports_preview_stabilization", cam.supportsPreviewStabilization)
                put("supports_ois", cam.supportsOis)
                put("supports_ae_lock", cam.supportsAeLock)
                put("supports_awb_lock", cam.supportsAwbLock)
                put("color_filter_arrangement", cam.colorFilterArrangement)
                put("noise_reduction_modes", JSONArray(cam.noiseReductionModes))
                put("edge_modes", JSONArray(cam.edgeModes))
                put("tonemap_modes", JSONArray(cam.tonemapModes))
                put("shading_modes", JSONArray(cam.shadingModes))
                put("hot_pixel_modes", JSONArray(cam.hotPixelModes))
                put("antibanding_modes", JSONArray(cam.antiBandingModes))
                put("supported_extensions", JSONArray(cam.supportedExtensions))
                put("concurrent_camera_sets", JSONArray(cam.concurrentCameraSets.map { JSONArray(it) }))
                put("ae_target_fps_ranges", JSONArray(cam.aeTargetFpsRanges.map { "${it.first}..${it.last}" }))

                val formatsObj = JSONObject()
                cam.outputFormats.forEach { fmt ->
                    formatsObj.put(fmt.formatName, JSONArray(fmt.resolutions.map { "${it.width}x${it.height}" }))
                }
                put("output_formats", formatsObj)

                val hsArr = JSONArray()
                cam.highSpeedConfigs.forEach { hs ->
                    hsArr.put("${hs.width}x${hs.height}@${hs.minFps}-${hs.maxFps}fps")
                }
                put("high_speed_video_configs", hsArr)

                val videoFpsObj = JSONObject()
                cam.videoFpsBySize.forEach { (sz, ranges) ->
                    videoFpsObj.put(sz, JSONArray(ranges.map { "${it.first}..${it.last}" }))
                }
                put("video_fps_by_size", videoFpsObj)
            }
            camsArr.put(obj)
        }
        root.put("cameras", camsArr)
        return root.toString(2)
    }
}
