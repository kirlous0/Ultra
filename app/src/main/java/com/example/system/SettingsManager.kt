package com.example.system

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.camera.AntiBandingOption
import com.example.camera.FocusPeakingColor
import com.example.camera.FocusPeakingSensitivity
import com.example.camera.GridLineColor
import com.example.camera.GridLineThickness
import com.example.camera.GridOverlayType
import com.example.camera.ProcessingDetailPreset
import com.example.camera.WatermarkStyle
import androidx.datastore.preferences.core.floatPreferencesKey
import com.example.processing.CameraColorProfile
import com.example.processing.LibPatcherConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ultraCamDataStore: DataStore<Preferences> by preferencesDataStore(name = "ultracam_settings")

@Immutable
data class UltraCamSettings(
    val language: AppLanguage = AppLanguage.ARABIC,
    val antiBanding: AntiBandingOption = AntiBandingOption.HZ_50,
    val defaultJpegQuality: Int = 95,
    val aiAssistantEnabled: Boolean = true,
    val watermarkEnabled: Boolean = false,
    val watermarkText: String = "UltraCam | Galaxy A05s",
    val watermarkStyle: WatermarkStyle = WatermarkStyle.PRO_FILM_FRAME,
    val gpsExifEnabled: Boolean = false,
    val customSafTreeUri: String? = null,
    val filenamePrefix: String = "UCAM",
    val gridOverlay: GridOverlayType = GridOverlayType.RULE_OF_THIRDS,
    val lastActiveGridOverlay: GridOverlayType = GridOverlayType.RULE_OF_THIRDS,
    val gridOpacity: Float = 0.50f,
    val gridThickness: GridLineThickness = GridLineThickness.MEDIUM,
    val gridColor: GridLineColor = GridLineColor.WHITE,
    val gridShowDiagonals: Boolean = false,
    val gridShowIntersectionDots: Boolean = false,
    val showHistogram: Boolean = true,
    val showRgbHistogram: Boolean = true,
    val showDigitalLevel: Boolean = true,
    val focusPeakingEnabled: Boolean = false,
    val focusPeakingColor: FocusPeakingColor = FocusPeakingColor.CYAN,
    val focusPeakingSensitivity: FocusPeakingSensitivity = FocusPeakingSensitivity.MEDIUM,
    val zebraWarningsEnabled: Boolean = false,
    val thermalProtectionEnabled: Boolean = true,
    val superResolutionEnabled: Boolean = false,
    val focusPowerMode: Boolean = false,
    val showLiveColorBar: Boolean = true,
    val aiAutoAdjustEnabled: Boolean = true,
    val mirrorFrontCamera: Boolean = true,
    val hapticShutterFeedback: Boolean = true,
    val hdrFrameCount: Int = 3,
    val hdrGhostRejection: Boolean = true,
    val lowLightAiBoost: Boolean = true,
    val lowLightTargetMaxIso: Int = 1600,
    val lowLightEvBiasSteps: Int = 1,
    val processingDetailPreset: ProcessingDetailPreset = ProcessingDetailPreset.BALANCED,
    val libPatcher: LibPatcherConfig = LibPatcherConfig(),
    val activePresetSlot: Int = 0, // 0 = Custom/Active, 1 = C1, 2 = C2, 3 = C3
    val hasPresetC1: Boolean = false,
    val hasPresetC2: Boolean = false,
    val hasPresetC3: Boolean = false
)

class SettingsManager(private val context: Context) {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val ANTI_BANDING = stringPreferencesKey("anti_banding")
        val JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val WATERMARK_ENABLED = booleanPreferencesKey("watermark_enabled")
        val WATERMARK_TEXT = stringPreferencesKey("watermark_text")
        val WATERMARK_STYLE = stringPreferencesKey("watermark_style")
        val GPS_EXIF_ENABLED = booleanPreferencesKey("gps_exif_enabled")
        val SAF_TREE_URI = stringPreferencesKey("saf_tree_uri")
        val FILENAME_PREFIX = stringPreferencesKey("filename_prefix")
        val GRID_OVERLAY = stringPreferencesKey("grid_overlay")
        val LAST_ACTIVE_GRID = stringPreferencesKey("last_active_grid")
        val GRID_OPACITY = floatPreferencesKey("grid_opacity")
        val GRID_THICKNESS = stringPreferencesKey("grid_thickness")
        val GRID_COLOR = stringPreferencesKey("grid_color")
        val GRID_SHOW_DIAGONALS = booleanPreferencesKey("grid_show_diagonals")
        val GRID_SHOW_DOTS = booleanPreferencesKey("grid_show_dots")
        val SHOW_HISTOGRAM = booleanPreferencesKey("show_histogram")
        val SHOW_RGB_HISTOGRAM = booleanPreferencesKey("show_rgb_histogram")
        val SHOW_LEVEL = booleanPreferencesKey("show_level")
        val FOCUS_PEAKING = booleanPreferencesKey("focus_peaking")
        val FOCUS_PEAKING_COLOR = stringPreferencesKey("focus_peaking_color")
        val FOCUS_PEAKING_SENS = stringPreferencesKey("focus_peaking_sensitivity")
        val ZEBRA_WARNINGS = booleanPreferencesKey("zebra_warnings")
        val THERMAL_PROTECTION = booleanPreferencesKey("thermal_protection")
        val SUPER_RES = booleanPreferencesKey("super_res")
        val FOCUS_POWER_MODE = booleanPreferencesKey("focus_power_mode")
        val SHOW_LIVE_COLOR_BAR = booleanPreferencesKey("show_live_color_bar")
        val AI_AUTO_ADJUST = booleanPreferencesKey("ai_auto_adjust")
        val MIRROR_FRONT_CAMERA = booleanPreferencesKey("mirror_front_camera")
        val HAPTIC_SHUTTER = booleanPreferencesKey("haptic_shutter")
        val HDR_FRAME_COUNT = intPreferencesKey("hdr_frame_count")
        val HDR_GHOST_REJECTION = booleanPreferencesKey("hdr_ghost_rejection")
        val LOW_LIGHT_AI_BOOST = booleanPreferencesKey("low_light_ai_boost")
        val LOW_LIGHT_MAX_ISO = intPreferencesKey("low_light_max_iso")
        val LOW_LIGHT_EV_BIAS = intPreferencesKey("low_light_ev_bias")
        val DETAIL_PRESET = stringPreferencesKey("processing_detail_preset")
        val LIB_PATCHER_JSON = stringPreferencesKey("lib_patcher_json")
        val ACTIVE_PRESET_SLOT = intPreferencesKey("active_preset_slot")
        val PRESET_C1_JSON = stringPreferencesKey("preset_c1_json")
        val PRESET_C2_JSON = stringPreferencesKey("preset_c2_json")
        val PRESET_C3_JSON = stringPreferencesKey("preset_c3_json")
    }

    val settingsFlow: Flow<UltraCamSettings> = context.ultraCamDataStore.data.map { prefs ->
        val grid = prefs[Keys.GRID_OVERLAY]?.let { name ->
            runCatching { GridOverlayType.valueOf(name) }.getOrNull()
        } ?: GridOverlayType.RULE_OF_THIRDS
        val lastGrid = prefs[Keys.LAST_ACTIVE_GRID]?.let { name ->
            runCatching { GridOverlayType.valueOf(name) }.getOrNull()
        }?.takeIf { it != GridOverlayType.NONE }
            ?: if (grid != GridOverlayType.NONE) grid else GridOverlayType.RULE_OF_THIRDS

        UltraCamSettings(
            language = prefs[Keys.LANGUAGE]?.let { code ->
                AppLanguage.entries.firstOrNull { it.code == code }
            } ?: AppLanguage.ARABIC,
            antiBanding = prefs[Keys.ANTI_BANDING]?.let { name ->
                runCatching { AntiBandingOption.valueOf(name) }.getOrNull()
            } ?: AntiBandingOption.HZ_50,
            defaultJpegQuality = (prefs[Keys.JPEG_QUALITY] ?: 95).coerceIn(60, 100),
            aiAssistantEnabled = prefs[Keys.AI_ENABLED] ?: true,
            watermarkEnabled = prefs[Keys.WATERMARK_ENABLED] ?: false,
            watermarkText = prefs[Keys.WATERMARK_TEXT] ?: "UltraCam | Galaxy A05s",
            watermarkStyle = prefs[Keys.WATERMARK_STYLE]?.let { name ->
                runCatching { WatermarkStyle.valueOf(name) }.getOrNull()
            } ?: WatermarkStyle.PRO_FILM_FRAME,
            gpsExifEnabled = prefs[Keys.GPS_EXIF_ENABLED] ?: false,
            customSafTreeUri = prefs[Keys.SAF_TREE_URI],
            filenamePrefix = (prefs[Keys.FILENAME_PREFIX] ?: "UCAM").ifBlank { "UCAM" },
            gridOverlay = grid,
            lastActiveGridOverlay = lastGrid,
            gridOpacity = (prefs[Keys.GRID_OPACITY] ?: 0.50f).coerceIn(0.20f, 0.95f),
            gridThickness = prefs[Keys.GRID_THICKNESS]?.let { name ->
                runCatching { GridLineThickness.valueOf(name) }.getOrNull()
            } ?: GridLineThickness.MEDIUM,
            gridColor = prefs[Keys.GRID_COLOR]?.let { name ->
                runCatching { GridLineColor.valueOf(name) }.getOrNull()
            } ?: GridLineColor.WHITE,
            gridShowDiagonals = prefs[Keys.GRID_SHOW_DIAGONALS] ?: false,
            gridShowIntersectionDots = prefs[Keys.GRID_SHOW_DOTS] ?: false,
            showHistogram = prefs[Keys.SHOW_HISTOGRAM] ?: true,
            showRgbHistogram = prefs[Keys.SHOW_RGB_HISTOGRAM] ?: true,
            showDigitalLevel = prefs[Keys.SHOW_LEVEL] ?: true,
            focusPeakingEnabled = prefs[Keys.FOCUS_PEAKING] ?: false,
            focusPeakingColor = prefs[Keys.FOCUS_PEAKING_COLOR]?.let { name ->
                runCatching { FocusPeakingColor.valueOf(name) }.getOrNull()
            } ?: FocusPeakingColor.CYAN,
            focusPeakingSensitivity = prefs[Keys.FOCUS_PEAKING_SENS]?.let { name ->
                runCatching { FocusPeakingSensitivity.valueOf(name) }.getOrNull()
            } ?: FocusPeakingSensitivity.MEDIUM,
            zebraWarningsEnabled = prefs[Keys.ZEBRA_WARNINGS] ?: false,
            thermalProtectionEnabled = prefs[Keys.THERMAL_PROTECTION] ?: true,
            superResolutionEnabled = prefs[Keys.SUPER_RES] ?: false,
            focusPowerMode = prefs[Keys.FOCUS_POWER_MODE] ?: false,
            showLiveColorBar = prefs[Keys.SHOW_LIVE_COLOR_BAR] ?: true,
            aiAutoAdjustEnabled = prefs[Keys.AI_AUTO_ADJUST] ?: true,
            mirrorFrontCamera = prefs[Keys.MIRROR_FRONT_CAMERA] ?: true,
            hapticShutterFeedback = prefs[Keys.HAPTIC_SHUTTER] ?: true,
            hdrFrameCount = if ((prefs[Keys.HDR_FRAME_COUNT] ?: 3) >= 5) 5 else 3,
            hdrGhostRejection = prefs[Keys.HDR_GHOST_REJECTION] ?: true,
            lowLightAiBoost = prefs[Keys.LOW_LIGHT_AI_BOOST] ?: true,
            lowLightTargetMaxIso = (prefs[Keys.LOW_LIGHT_MAX_ISO] ?: 1600).coerceIn(400, 6400),
            lowLightEvBiasSteps = (prefs[Keys.LOW_LIGHT_EV_BIAS] ?: 1).coerceIn(-6, 8),
            processingDetailPreset = prefs[Keys.DETAIL_PRESET]?.let { name ->
                runCatching { ProcessingDetailPreset.valueOf(name) }.getOrNull()
            } ?: ProcessingDetailPreset.BALANCED,
            libPatcher = LibPatcherConfig.fromJsonString(prefs[Keys.LIB_PATCHER_JSON]),
            activePresetSlot = prefs[Keys.ACTIVE_PRESET_SLOT] ?: 0,
            hasPresetC1 = !prefs[Keys.PRESET_C1_JSON].isNullOrBlank(),
            hasPresetC2 = !prefs[Keys.PRESET_C2_JSON].isNullOrBlank(),
            hasPresetC3 = !prefs[Keys.PRESET_C3_JSON].isNullOrBlank()
        )
    }

    suspend fun updateLanguage(language: AppLanguage) {
        context.ultraCamDataStore.edit { it[Keys.LANGUAGE] = language.code }
    }

    suspend fun updateAntiBanding(option: AntiBandingOption) {
        context.ultraCamDataStore.edit { it[Keys.ANTI_BANDING] = option.name }
    }

    suspend fun updateJpegQuality(quality: Int) {
        context.ultraCamDataStore.edit { it[Keys.JPEG_QUALITY] = quality.coerceIn(60, 100) }
    }

    suspend fun setAiAssistantEnabled(enabled: Boolean) {
        context.ultraCamDataStore.edit { it[Keys.AI_ENABLED] = enabled }
    }

    suspend fun setWatermark(enabled: Boolean, text: String) {
        context.ultraCamDataStore.edit {
            it[Keys.WATERMARK_ENABLED] = enabled
            it[Keys.WATERMARK_TEXT] = text.take(40)
        }
    }

    suspend fun setWatermarkStyle(style: WatermarkStyle) {
        context.ultraCamDataStore.edit { it[Keys.WATERMARK_STYLE] = style.name }
    }

    suspend fun setGpsExifEnabled(enabled: Boolean) {
        context.ultraCamDataStore.edit { it[Keys.GPS_EXIF_ENABLED] = enabled }
    }

    suspend fun setCustomSafTreeUri(uri: String?) {
        context.ultraCamDataStore.edit {
            if (uri == null) it.remove(Keys.SAF_TREE_URI)
            else it[Keys.SAF_TREE_URI] = uri
        }
    }

    suspend fun setFilenamePrefix(prefix: String) {
        val sanitized = prefix.replace(Regex("[^A-Za-z0-9_-]"), "").take(16).ifBlank { "UCAM" }
        context.ultraCamDataStore.edit { it[Keys.FILENAME_PREFIX] = sanitized }
    }

    suspend fun setGridOverlay(grid: GridOverlayType) {
        context.ultraCamDataStore.edit { prefs ->
            prefs[Keys.GRID_OVERLAY] = grid.name
            if (grid != GridOverlayType.NONE) {
                prefs[Keys.LAST_ACTIVE_GRID] = grid.name
            }
        }
    }

    suspend fun toggleGridOverlayEnabled(enabled: Boolean) {
        context.ultraCamDataStore.edit { prefs ->
            if (enabled) {
                val restored = prefs[Keys.LAST_ACTIVE_GRID]?.let {
                    runCatching { GridOverlayType.valueOf(it) }.getOrNull()
                }?.takeIf { it != GridOverlayType.NONE } ?: GridOverlayType.RULE_OF_THIRDS
                prefs[Keys.GRID_OVERLAY] = restored.name
            } else {
                val cur = prefs[Keys.GRID_OVERLAY]?.let {
                    runCatching { GridOverlayType.valueOf(it) }.getOrNull()
                }
                if (cur != null && cur != GridOverlayType.NONE) {
                    prefs[Keys.LAST_ACTIVE_GRID] = cur.name
                }
                prefs[Keys.GRID_OVERLAY] = GridOverlayType.NONE.name
            }
        }
    }

    suspend fun setGridConfig(
        opacity: Float? = null,
        thickness: GridLineThickness? = null,
        color: GridLineColor? = null,
        showDiagonals: Boolean? = null,
        showIntersectionDots: Boolean? = null
    ) {
        context.ultraCamDataStore.edit { prefs ->
            opacity?.let { prefs[Keys.GRID_OPACITY] = it.coerceIn(0.20f, 0.95f) }
            thickness?.let { prefs[Keys.GRID_THICKNESS] = it.name }
            color?.let { prefs[Keys.GRID_COLOR] = it.name }
            showDiagonals?.let { prefs[Keys.GRID_SHOW_DIAGONALS] = it }
            showIntersectionDots?.let { prefs[Keys.GRID_SHOW_DOTS] = it }
        }
    }

    suspend fun setOverlays(
        showHistogram: Boolean? = null,
        showRgbHistogram: Boolean? = null,
        showDigitalLevel: Boolean? = null,
        focusPeaking: Boolean? = null,
        zebraWarnings: Boolean? = null
    ) {
        context.ultraCamDataStore.edit { prefs ->
            showHistogram?.let { prefs[Keys.SHOW_HISTOGRAM] = it }
            showRgbHistogram?.let { prefs[Keys.SHOW_RGB_HISTOGRAM] = it }
            showDigitalLevel?.let { prefs[Keys.SHOW_LEVEL] = it }
            focusPeaking?.let { prefs[Keys.FOCUS_PEAKING] = it }
            zebraWarnings?.let { prefs[Keys.ZEBRA_WARNINGS] = it }
        }
    }

    suspend fun setFocusPeakingStyle(
        color: FocusPeakingColor? = null,
        sensitivity: FocusPeakingSensitivity? = null
    ) {
        context.ultraCamDataStore.edit { prefs ->
            color?.let { prefs[Keys.FOCUS_PEAKING_COLOR] = it.name }
            sensitivity?.let { prefs[Keys.FOCUS_PEAKING_SENS] = it.name }
        }
    }

    suspend fun setThermalProtection(enabled: Boolean) {
        context.ultraCamDataStore.edit { it[Keys.THERMAL_PROTECTION] = enabled }
    }

    suspend fun setSuperResolutionEnabled(enabled: Boolean) {
        context.ultraCamDataStore.edit { it[Keys.SUPER_RES] = enabled }
    }

    suspend fun setFocusPowerMode(enabled: Boolean) {
        context.ultraCamDataStore.edit { it[Keys.FOCUS_POWER_MODE] = enabled }
    }

    suspend fun setShowLiveColorBar(enabled: Boolean) {
        context.ultraCamDataStore.edit { it[Keys.SHOW_LIVE_COLOR_BAR] = enabled }
    }

    suspend fun setAiAutoAdjustEnabled(enabled: Boolean) {
        context.ultraCamDataStore.edit { it[Keys.AI_AUTO_ADJUST] = enabled }
    }

    suspend fun setMirrorFrontCamera(enabled: Boolean) {
        context.ultraCamDataStore.edit { it[Keys.MIRROR_FRONT_CAMERA] = enabled }
    }

    suspend fun setHapticShutterFeedback(enabled: Boolean) {
        context.ultraCamDataStore.edit { it[Keys.HAPTIC_SHUTTER] = enabled }
    }

    suspend fun setHdrOptions(frameCount: Int? = null, ghostRejection: Boolean? = null) {
        context.ultraCamDataStore.edit { prefs ->
            frameCount?.let { prefs[Keys.HDR_FRAME_COUNT] = if (it >= 5) 5 else 3 }
            ghostRejection?.let { prefs[Keys.HDR_GHOST_REJECTION] = it }
        }
    }

    suspend fun setLowLightAiBoost(enabled: Boolean) {
        context.ultraCamDataStore.edit { it[Keys.LOW_LIGHT_AI_BOOST] = enabled }
    }

    suspend fun setLowLightTuning(maxIso: Int? = null, evBiasSteps: Int? = null) {
        context.ultraCamDataStore.edit { prefs ->
            maxIso?.let { prefs[Keys.LOW_LIGHT_MAX_ISO] = it.coerceIn(400, 6400) }
            evBiasSteps?.let { prefs[Keys.LOW_LIGHT_EV_BIAS] = it.coerceIn(-6, 8) }
        }
    }

    suspend fun setProcessingDetailPreset(preset: ProcessingDetailPreset) {
        context.ultraCamDataStore.edit { it[Keys.DETAIL_PRESET] = preset.name }
    }

    suspend fun updateLibPatcher(transform: (LibPatcherConfig) -> LibPatcherConfig) {
        context.ultraCamDataStore.edit { prefs ->
            val current = LibPatcherConfig.fromJsonString(prefs[Keys.LIB_PATCHER_JSON])
            prefs[Keys.LIB_PATCHER_JSON] = transform(current).toJsonString()
            prefs[Keys.ACTIVE_PRESET_SLOT] = 0
        }
    }

    suspend fun setColorProfile(profile: CameraColorProfile) {
        updateLibPatcher { it.copy(colorProfile = profile) }
    }

    suspend fun applyLibPreset(config: LibPatcherConfig) {
        context.ultraCamDataStore.edit { prefs ->
            prefs[Keys.LIB_PATCHER_JSON] = config.toJsonString()
            prefs[Keys.ACTIVE_PRESET_SLOT] = 0
        }
    }

    suspend fun saveCustomSlot(slot: Int) {
        val key = when (slot) {
            1 -> Keys.PRESET_C1_JSON
            2 -> Keys.PRESET_C2_JSON
            3 -> Keys.PRESET_C3_JSON
            else -> return
        }
        context.ultraCamDataStore.edit { prefs ->
            val current = prefs[Keys.LIB_PATCHER_JSON] ?: LibPatcherConfig().toJsonString()
            prefs[key] = current
            prefs[Keys.ACTIVE_PRESET_SLOT] = slot
        }
    }

    suspend fun loadCustomSlot(slot: Int) {
        val key = when (slot) {
            1 -> Keys.PRESET_C1_JSON
            2 -> Keys.PRESET_C2_JSON
            3 -> Keys.PRESET_C3_JSON
            else -> return
        }
        context.ultraCamDataStore.edit { prefs ->
            val saved = prefs[key]
            if (!saved.isNullOrBlank()) {
                prefs[Keys.LIB_PATCHER_JSON] = saved
                prefs[Keys.ACTIVE_PRESET_SLOT] = slot
            }
        }
    }
}
