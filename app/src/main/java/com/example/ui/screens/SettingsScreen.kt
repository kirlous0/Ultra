package com.example.ui.screens

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import com.example.camera.AntiBandingOption
import com.example.camera.AppDestination
import com.example.camera.FocusPeakingColor
import com.example.camera.FocusPeakingSensitivity
import com.example.camera.GridLineColor
import com.example.camera.GridLineThickness
import com.example.camera.GridOverlayType
import com.example.camera.ProcessingDetailPreset
import com.example.system.AppLanguage
import com.example.system.AppStrings
import com.example.ui.MainViewModel
import com.example.ui.components.ProPillButton
import com.example.ui.theme.AmberFocus
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.GraphiteBorder
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SapphireTelemetry
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var prefixDraft by remember(settings.filenamePrefix) { mutableStateOf(settings.filenamePrefix) }
    var watermarkDraft by remember(settings.watermarkText) { mutableStateOf(settings.watermarkText) }

    val safFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            scope.launch { viewModel.settingsManager.setCustomSafTreeUri(uri.toString()) }
        }
    }

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch { viewModel.settingsManager.setGpsExifEnabled(granted) }
    }

    BackHandler {
        viewModel.navigateTo(AppDestination.CAMERA)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ObsidianVoid,
        topBar = {
            TopAppBar(
                title = { Text(strings.modeSettings, style = MaterialTheme.typography.titleMedium, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(AppDestination.CAMERA) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CarbonSurface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Language (Arabic default + English RTL/LTR)
            SettingCard(title = strings.languageLabel, icon = Icons.Filled.Language) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProPillButton(
                        label = "العربية",
                        selected = settings.language == AppLanguage.ARABIC,
                        testTag = "lang_arabic_button",
                        onClick = { scope.launch { viewModel.settingsManager.updateLanguage(AppLanguage.ARABIC) } }
                    )
                    ProPillButton(
                        label = "English",
                        selected = settings.language == AppLanguage.ENGLISH,
                        testTag = "lang_english_button",
                        onClick = { scope.launch { viewModel.settingsManager.updateLanguage(AppLanguage.ENGLISH) } }
                    )
                }
            }

            // Anti-banding (50Hz Egypt Default)
            SettingCard(title = strings.antiBandingLabel, icon = Icons.Filled.Tune) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProPillButton("50 Hz", settings.antiBanding == AntiBandingOption.HZ_50) {
                        scope.launch { viewModel.settingsManager.updateAntiBanding(AntiBandingOption.HZ_50) }
                    }
                    ProPillButton("60 Hz", settings.antiBanding == AntiBandingOption.HZ_60) {
                        scope.launch { viewModel.settingsManager.updateAntiBanding(AntiBandingOption.HZ_60) }
                    }
                    ProPillButton(strings.autoLabel, settings.antiBanding == AntiBandingOption.AUTO) {
                        scope.launch { viewModel.settingsManager.updateAntiBanding(AntiBandingOption.AUTO) }
                    }
                }
            }

            // AI & Computational Processing
            SettingCard(title = strings.aiAssistantTitle, icon = Icons.Filled.AutoAwesome) {
                SettingSwitchRow(
                    label = strings.aiAssistantTitle,
                    checked = settings.aiAssistantEnabled,
                    onCheckedChange = { scope.launch { viewModel.settingsManager.setAiAssistantEnabled(it) } }
                )
                SettingSwitchRow(
                    label = strings.aiAutoAdjustLabel,
                    subtitle = strings.aiAutoAdjustDesc,
                    checked = settings.aiAutoAdjustEnabled,
                    switchTestTag = "settings_ai_auto_adjust_switch",
                    onCheckedChange = { scope.launch { viewModel.settingsManager.setAiAutoAdjustEnabled(it) } }
                )
                SettingSwitchRow(
                    label = strings.focusPeaking,
                    checked = settings.focusPeakingEnabled,
                    switchTestTag = "settings_focus_peaking_switch",
                    onCheckedChange = {
                        scope.launch { viewModel.settingsManager.setOverlays(focusPeaking = it) }
                    }
                )
                if (settings.focusPeakingEnabled) {
                    Text(strings.focusPeakingColorLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FocusPeakingColor.entries.forEach { c ->
                            ProPillButton(
                                label = if (strings.isArabic) c.labelAr else c.labelEn,
                                selected = settings.focusPeakingColor == c,
                                activeColor = Color(c.argb),
                                onClick = {
                                    scope.launch { viewModel.settingsManager.setFocusPeakingStyle(color = c) }
                                }
                            )
                        }
                    }
                    Text(strings.focusPeakingSensitivityLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FocusPeakingSensitivity.entries.forEach { s ->
                            ProPillButton(
                                label = if (strings.isArabic) s.labelAr else s.labelEn,
                                selected = settings.focusPeakingSensitivity == s,
                                activeColor = Color(settings.focusPeakingColor.argb),
                                onClick = {
                                    scope.launch { viewModel.settingsManager.setFocusPeakingStyle(sensitivity = s) }
                                }
                            )
                        }
                    }
                }
                SettingSwitchRow(
                    label = strings.focusPowerModeLabel,
                    subtitle = strings.focusPowerModeDesc,
                    checked = settings.focusPowerMode,
                    switchTestTag = "settings_focus_power_switch",
                    onCheckedChange = { scope.launch { viewModel.settingsManager.setFocusPowerMode(it) } }
                )
                SettingSwitchRow(
                    label = strings.thermalProtectionLabel,
                    checked = settings.thermalProtectionEnabled,
                    onCheckedChange = { scope.launch { viewModel.settingsManager.setThermalProtection(it) } }
                )
                SettingSwitchRow(
                    label = strings.superResToggleLabel,
                    checked = settings.superResolutionEnabled,
                    onCheckedChange = { scope.launch { viewModel.settingsManager.setSuperResolutionEnabled(it) } }
                )
                SettingSwitchRow(
                    label = strings.colorProfileLabel,
                    checked = settings.showLiveColorBar,
                    onCheckedChange = { scope.launch { viewModel.settingsManager.setShowLiveColorBar(it) } }
                )
                SettingSwitchRow(
                    label = strings.lowLightAiBoostLabel,
                    subtitle = strings.lowLightAiBoostDesc,
                    checked = settings.lowLightAiBoost,
                    switchTestTag = "settings_low_light_boost_switch",
                    onCheckedChange = { scope.launch { viewModel.settingsManager.setLowLightAiBoost(it) } }
                )
                Text("${strings.lowLightMaxIsoLabel}: ISO ${settings.lowLightTargetMaxIso}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(800, 1600, 3200, 6400).forEach { isoCap ->
                        ProPillButton(
                            label = "ISO $isoCap",
                            selected = settings.lowLightTargetMaxIso == isoCap,
                            activeColor = SapphireTelemetry,
                            testTag = "settings_low_light_iso_$isoCap",
                            onClick = { scope.launch { viewModel.settingsManager.setLowLightTuning(maxIso = isoCap) } }
                        )
                    }
                }
                val nightEvFormatted = settings.lowLightEvBiasSteps * 0.3f
                Text(
                    text = "${strings.lowLightEvBiasLabel}: ${if (nightEvFormatted >= 0f) "+" else ""}${"%.1f".format(nightEvFormatted)} EV",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Slider(
                    value = settings.lowLightEvBiasSteps.toFloat(),
                    onValueChange = { s ->
                        scope.launch { viewModel.settingsManager.setLowLightTuning(evBiasSteps = s.roundToInt()) }
                    },
                    valueRange = -3f..6f,
                    colors = SliderDefaults.colors(thumbColor = SapphireTelemetry, activeTrackColor = SapphireTelemetry),
                    modifier = Modifier.testTag("settings_low_light_ev_slider")
                )
                Text(strings.processingDetailPresetLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProcessingDetailPreset.entries.forEach { preset ->
                        ProPillButton(
                            label = if (strings.isArabic) preset.labelAr else preset.labelEn,
                            selected = settings.processingDetailPreset == preset,
                            testTag = "settings_detail_preset_${preset.name.lowercase()}",
                            onClick = {
                                scope.launch { viewModel.settingsManager.setProcessingDetailPreset(preset) }
                            }
                        )
                    }
                }
                Text(strings.hdrFramesCountLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProPillButton(
                        label = "3F",
                        selected = settings.hdrFrameCount < 5,
                        testTag = "settings_hdr_3f_pill",
                        onClick = { scope.launch { viewModel.settingsManager.setHdrOptions(frameCount = 3) } }
                    )
                    ProPillButton(
                        label = "5F",
                        selected = settings.hdrFrameCount >= 5,
                        testTag = "settings_hdr_5f_pill",
                        onClick = { scope.launch { viewModel.settingsManager.setHdrOptions(frameCount = 5) } }
                    )
                }
                SettingSwitchRow(
                    label = strings.hdrGhostRejectionLabel,
                    checked = settings.hdrGhostRejection,
                    switchTestTag = "settings_hdr_ghost_switch",
                    onCheckedChange = { scope.launch { viewModel.settingsManager.setHdrOptions(ghostRejection = it) } }
                )
                Text("${strings.jpegQualityLabel}: ${settings.defaultJpegQuality}%", color = TextSecondary)
                Slider(
                    value = settings.defaultJpegQuality.toFloat(),
                    onValueChange = { q ->
                        scope.launch { viewModel.settingsManager.updateJpegQuality(q.roundToInt()) }
                    },
                    valueRange = 60f..100f,
                    colors = SliderDefaults.colors(thumbColor = AmberFocus, activeTrackColor = AmberFocus)
                )
            }

            // Viewfinder Grids & HUD Overlays
            SettingCard(title = strings.viewfinderOverlaysTitle, icon = Icons.Filled.GridOn) {
                SettingSwitchRow(
                    label = strings.gridLabel,
                    subtitle = strings.gridOverlayToggleDesc,
                    checked = settings.gridOverlay != GridOverlayType.NONE,
                    switchTestTag = "settings_grid_master_switch",
                    onCheckedChange = {
                        scope.launch { viewModel.settingsManager.toggleGridOverlayEnabled(it) }
                    }
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GridOverlayType.entries.forEach { grid ->
                        ProPillButton(
                            label = if (strings.isArabic) grid.labelAr else grid.labelEn,
                            selected = settings.gridOverlay == grid,
                            testTag = "settings_grid_type_${grid.name.lowercase()}",
                            onClick = {
                                scope.launch { viewModel.settingsManager.setGridOverlay(grid) }
                            }
                        )
                    }
                }
                if (settings.gridOverlay != GridOverlayType.NONE) {
                    Text(
                        text = "${strings.gridOpacityLabel}: ${(settings.gridOpacity * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Slider(
                        value = settings.gridOpacity,
                        onValueChange = { a ->
                            scope.launch { viewModel.settingsManager.setGridConfig(opacity = a) }
                        },
                        valueRange = 0.20f..0.95f,
                        colors = SliderDefaults.colors(thumbColor = AmberFocus, activeTrackColor = AmberFocus),
                        modifier = Modifier.testTag("settings_grid_opacity_slider")
                    )
                    Text(strings.gridThicknessLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GridLineThickness.entries.forEach { t ->
                            ProPillButton(
                                label = if (strings.isArabic) t.labelAr else t.labelEn,
                                selected = settings.gridThickness == t,
                                testTag = "settings_grid_thickness_${t.name.lowercase()}",
                                onClick = {
                                    scope.launch { viewModel.settingsManager.setGridConfig(thickness = t) }
                                }
                            )
                        }
                    }
                    Text(strings.gridColorLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GridLineColor.entries.forEach { c ->
                            ProPillButton(
                                label = if (strings.isArabic) c.labelAr else c.labelEn,
                                selected = settings.gridColor == c,
                                activeColor = Color(c.argb),
                                testTag = "settings_grid_color_${c.name.lowercase()}",
                                onClick = {
                                    scope.launch { viewModel.settingsManager.setGridConfig(color = c) }
                                }
                            )
                        }
                    }
                    if (settings.gridOverlay == GridOverlayType.RULE_OF_THIRDS) {
                        SettingSwitchRow(
                            label = strings.gridDiagonalsLabel,
                            checked = settings.gridShowDiagonals,
                            switchTestTag = "settings_grid_diagonals_switch",
                            onCheckedChange = {
                                scope.launch { viewModel.settingsManager.setGridConfig(showDiagonals = it) }
                            }
                        )
                        SettingSwitchRow(
                            label = strings.gridIntersectionDotsLabel,
                            checked = settings.gridShowIntersectionDots,
                            switchTestTag = "settings_grid_points_switch",
                            onCheckedChange = {
                                scope.launch { viewModel.settingsManager.setGridConfig(showIntersectionDots = it) }
                            }
                        )
                    }
                }
                SettingSwitchRow(
                    label = strings.histogramLabel,
                    checked = settings.showHistogram,
                    switchTestTag = "settings_histogram_switch",
                    onCheckedChange = {
                        scope.launch { viewModel.settingsManager.setOverlays(showHistogram = it) }
                    }
                )
                SettingSwitchRow(
                    label = strings.rgbHistogram,
                    checked = settings.showRgbHistogram,
                    switchTestTag = "settings_rgb_histogram_switch",
                    onCheckedChange = {
                        scope.launch { viewModel.settingsManager.setOverlays(showRgbHistogram = it) }
                    }
                )
                SettingSwitchRow(
                    label = strings.zebraLabel,
                    checked = settings.zebraWarningsEnabled,
                    switchTestTag = "settings_zebra_switch",
                    onCheckedChange = {
                        scope.launch { viewModel.settingsManager.setOverlays(zebraWarnings = it) }
                    }
                )
                SettingSwitchRow(
                    label = strings.digitalLevel,
                    checked = settings.showDigitalLevel,
                    switchTestTag = "settings_digital_level_switch",
                    onCheckedChange = {
                        scope.launch { viewModel.settingsManager.setOverlays(showDigitalLevel = it) }
                    }
                )
            }

            // Capture & Lens Controls
            SettingCard(title = strings.captureControlsTitle, icon = Icons.Filled.CameraAlt) {
                SettingSwitchRow(
                    label = strings.mirrorFrontCameraLabel,
                    checked = settings.mirrorFrontCamera,
                    switchTestTag = "settings_mirror_front_switch",
                    onCheckedChange = {
                        scope.launch { viewModel.settingsManager.setMirrorFrontCamera(it) }
                    }
                )
                SettingSwitchRow(
                    label = strings.hapticShutterLabel,
                    checked = settings.hapticShutterFeedback,
                    switchTestTag = "settings_haptic_shutter_switch",
                    onCheckedChange = {
                        scope.launch { viewModel.settingsManager.setHapticShutterFeedback(it) }
                    }
                )
            }

            // Storage & SAF SD Card Selection
            SettingCard(title = strings.storageLocationLabel, icon = Icons.Filled.Folder) {
                Text(
                    text = settings.customSafTreeUri ?: strings.resetInternalStorage,
                    style = MaterialTheme.typography.labelSmall,
                    color = SapphireTelemetry
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { safFolderPicker.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberFocus, contentColor = ObsidianVoid)
                    ) {
                        Text(strings.selectSdFolder)
                    }
                    if (settings.customSafTreeUri != null) {
                        ProPillButton(strings.resetInternalStorage, selected = false) {
                            scope.launch { viewModel.settingsManager.setCustomSafTreeUri(null) }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = prefixDraft,
                    onValueChange = {
                        prefixDraft = it
                        scope.launch { viewModel.settingsManager.setFilenamePrefix(it) }
                    },
                    label = { Text(strings.customPrefixLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Watermark & Optional GPS EXIF
            SettingCard(title = "${strings.watermarkLabel} & EXIF", icon = Icons.Filled.BrandingWatermark) {
                SettingSwitchRow(
                    label = strings.watermarkLabel,
                    checked = settings.watermarkEnabled,
                    onCheckedChange = {
                        scope.launch { viewModel.settingsManager.setWatermark(it, watermarkDraft) }
                    }
                )
                if (settings.watermarkEnabled) {
                    Text(strings.watermarkStyleLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProPillButton(
                            label = strings.watermarkFrameLabel,
                            selected = settings.watermarkStyle == com.example.camera.WatermarkStyle.PRO_FILM_FRAME,
                            onClick = {
                                scope.launch {
                                    viewModel.settingsManager.setWatermarkStyle(com.example.camera.WatermarkStyle.PRO_FILM_FRAME)
                                }
                            }
                        )
                        ProPillButton(
                            label = strings.watermarkCornerLabel,
                            selected = settings.watermarkStyle == com.example.camera.WatermarkStyle.TEXT_CORNER,
                            onClick = {
                                scope.launch {
                                    viewModel.settingsManager.setWatermarkStyle(com.example.camera.WatermarkStyle.TEXT_CORNER)
                                }
                            }
                        )
                    }
                    OutlinedTextField(
                        value = watermarkDraft,
                        onValueChange = {
                            watermarkDraft = it
                            scope.launch { viewModel.settingsManager.setWatermark(true, it) }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SettingSwitchRow(
                    label = strings.gpsExifLabel,
                    checked = settings.gpsExifEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            locationPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        } else {
                            scope.launch { viewModel.settingsManager.setGpsExifEnabled(false) }
                        }
                    }
                )
            }

            // Direct Shortcut to Hardware Diagnostics
            SettingCard(title = strings.modeDiagnostics, icon = Icons.Filled.MonitorHeart) {
                OutlinedButton(
                    onClick = { viewModel.navigateTo(AppDestination.DIAGNOSTICS) },
                    modifier = Modifier.fillMaxWidth().testTag("settings_open_diagnostics_button")
                ) {
                    Icon(Icons.Filled.MonitorHeart, contentDescription = null, tint = SapphireTelemetry)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.modeDiagnostics, color = SapphireTelemetry)
                }
            }

            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    icon: ImageVector? = null,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, GraphiteBorder),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = AmberFocus,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = AmberFocus)
            }
            HorizontalDivider(color = GraphiteBorder.copy(alpha = 0.55f))
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    switchTestTag: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier
        )
    }
}
