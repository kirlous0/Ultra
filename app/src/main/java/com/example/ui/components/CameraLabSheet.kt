package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.processing.CameraColorProfile
import com.example.processing.HdrToneStyle
import com.example.processing.LibPatcherConfig
import com.example.system.AppStrings
import com.example.system.UltraCamSettings
import com.example.ui.theme.AmberFocus
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.EmeraldLock
import com.example.ui.theme.GraphiteBorder
import com.example.ui.theme.MachinedSlate
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SapphireTelemetry
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraLabSheet(
    settings: UltraCamSettings,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onUpdatePatch: ((LibPatcherConfig) -> LibPatcherConfig) -> Unit,
    onApplyPreset: (LibPatcherConfig) -> Unit,
    onSaveSlot: (Int) -> Unit,
    onLoadSlot: (Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val patch = settings.libPatcher
    var savedToastSlot by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(savedToastSlot) {
        if (savedToastSlot != null) {
            kotlinx.coroutines.delay(1800L)
            savedToastSlot = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CarbonSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = strings.cameraLabTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AmberFocus
                    )
                    Text(
                        text = "${if (strings.isArabic) patch.colorProfile.labelAr else patch.colorProfile.labelEn} · ${if (strings.isArabic) patch.hdrToneStyle.labelAr else patch.hdrToneStyle.labelEn}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onApplyPreset(LibPatcherConfig()) },
                        modifier = Modifier.testTag("lab_reset_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = strings.resetEdits, tint = TextSecondary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = strings.cancelLabel, tint = TextPrimary)
                    }
                }
            }

            HorizontalDivider(color = GraphiteBorder.copy(alpha = 0.6f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Custom Slots C1 - C3 & Quick Presets Card
                Surface(
                    color = MachinedSlate.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GraphiteBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(strings.customPresetsLabel, style = MaterialTheme.typography.labelMedium, color = SapphireTelemetry)
                            savedToastSlot?.let { slot ->
                                Text(
                                    text = if (strings.isArabic) "تم حفظ C$slot ✓" else "Saved C$slot ✓",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldLock
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val slots = listOf(
                                Triple(1, "C1", settings.hasPresetC1),
                                Triple(2, "C2", settings.hasPresetC2),
                                Triple(3, "C3", settings.hasPresetC3)
                            )
                            slots.forEach { (slotId, name, hasSaved) ->
                                val isActive = settings.activePresetSlot == slotId
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isActive) AmberFocus.copy(alpha = 0.2f) else CarbonSurface)
                                        .border(1.dp, if (isActive) AmberFocus else GraphiteBorder, RoundedCornerShape(10.dp)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .clickable(enabled = hasSaved) { onLoadSlot(slotId) }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (hasSaved) name else "$name · —",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isActive) AmberFocus else if (hasSaved) TextPrimary else TextSecondary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            onSaveSlot(slotId)
                                            savedToastSlot = slotId
                                        },
                                        modifier = Modifier.testTag("save_slot_c$slotId")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Save,
                                            contentDescription = "Save $name",
                                            tint = SapphireTelemetry,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ProPillButton("HDR+ Punch", false, SapphireTelemetry) {
                                onApplyPreset(LibPatcherConfig.PRESET_GCAM_PUNCH)
                            }
                            ProPillButton("Leica Classic", false, SapphireTelemetry) {
                                onApplyPreset(LibPatcherConfig.PRESET_LEICA_CLASSIC)
                            }
                            ProPillButton("Night Crisp", false, SapphireTelemetry) {
                                onApplyPreset(LibPatcherConfig.PRESET_NIGHT_CRISP)
                            }
                            ProPillButton("Cinema T&O", false, SapphireTelemetry) {
                                onApplyPreset(LibPatcherConfig.PRESET_CINEMA_FILM)
                            }
                        }
                    }
                }

                // Color Science Selector
                Text(strings.colorProfileLabel, style = MaterialTheme.typography.labelMedium, color = AmberFocus)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CameraColorProfile.entries.forEach { profile ->
                        val selected = patch.colorProfile == profile
                        Row(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) AmberFocus else MachinedSlate)
                                .border(1.dp, if (selected) AmberFocus else GraphiteBorder, RoundedCornerShape(10.dp))
                                .clickable { onUpdatePatch { it.copy(colorProfile = profile) } }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(profileAccentColor(profile))
                            )
                            Text(
                                text = if (strings.isArabic) profile.labelAr else profile.labelEn,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) ObsidianVoid else TextPrimary
                            )
                        }
                    }
                }

                // HDR+ Tone Curve
                Text(strings.hdrToneCurveLabel, style = MaterialTheme.typography.labelMedium, color = AmberFocus)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HdrToneStyle.entries.forEach { style ->
                        ProPillButton(
                            label = if (strings.isArabic) style.labelAr else style.labelEn,
                            selected = patch.hdrToneStyle == style,
                            activeColor = EmeraldLock,
                            onClick = { onUpdatePatch { it.copy(hdrToneStyle = style) } }
                        )
                    }
                }

                HorizontalDivider(color = GraphiteBorder.copy(alpha = 0.5f))

                // Detail & Denoise Sliders
                LabSlider(strings.sharpness, patch.sharpness, 0f..1.0f) { v ->
                    onUpdatePatch { it.copy(sharpness = v) }
                }
                LabSlider(strings.clarityLabel, patch.clarity, 0f..0.8f) { v ->
                    onUpdatePatch { it.copy(clarity = v) }
                }
                LabSlider(strings.lumaDenoiseLabel, patch.lumaDenoise, 0f..0.8f) { v ->
                    onUpdatePatch { it.copy(lumaDenoise = v) }
                }
                LabSlider(strings.chromaDenoiseLabel, patch.chromaDenoise, 0f..0.85f) { v ->
                    onUpdatePatch { it.copy(chromaDenoise = v) }
                }
                LabSlider(strings.shadows, patch.shadowLift, 0f..0.40f) { v ->
                    onUpdatePatch { it.copy(shadowLift = v) }
                }
                LabSlider(strings.highlights, patch.highlightCompress, 0f..0.40f) { v ->
                    onUpdatePatch { it.copy(highlightCompress = v) }
                }
                LabSlider(strings.dehazeLabel, patch.dehaze, 0f..0.45f) { v ->
                    onUpdatePatch { it.copy(dehaze = v) }
                }
                LabSlider(strings.contrast, patch.contrast, -0.35f..0.45f) { v ->
                    onUpdatePatch { it.copy(contrast = v) }
                }
                LabSlider(strings.vibrance, patch.vibrance, 0f..0.50f) { v ->
                    onUpdatePatch { it.copy(vibrance = v) }
                }
                LabSlider(strings.saturation, patch.saturation, -0.40f..0.50f) { v ->
                    onUpdatePatch { it.copy(saturation = v) }
                }
                LabSlider(strings.temperature, patch.warmth, -0.35f..0.35f) { v ->
                    onUpdatePatch { it.copy(warmth = v) }
                }
                LabSlider(strings.tint, patch.tint, -0.35f..0.35f) { v ->
                    onUpdatePatch { it.copy(tint = v) }
                }
                LabSlider(strings.vignetteLabel, patch.vignette, 0f..0.45f) { v ->
                    onUpdatePatch { it.copy(vignette = v) }
                }

                // Skin Tone Protection Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.skinProtectLabel, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Switch(
                        checked = patch.skinToneProtect,
                        onCheckedChange = { checked -> onUpdatePatch { it.copy(skinToneProtect = checked) } }
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun LabSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
            Text("%.2f".format(value), style = MaterialTheme.typography.labelSmall, color = AmberFocus)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = AmberFocus, activeTrackColor = AmberFocus)
        )
    }
}

fun profileAccentColor(profile: CameraColorProfile): Color = when (profile) {
    CameraColorProfile.NATURAL_TRUE -> Color(0xFF94A3B8)
    CameraColorProfile.LEICA_VIBRANT -> Color(0xFFE11D48)
    CameraColorProfile.LEICA_AUTHENTIC -> Color(0xFFD97706)
    CameraColorProfile.HASSELBLAD_NATURAL -> Color(0xFFF59E0B)
    CameraColorProfile.FUJI_CLASSIC_CHROME -> Color(0xFF0EA5E9)
    CameraColorProfile.CINEMA_TEAL_ORANGE -> Color(0xFF14B8A6)
    CameraColorProfile.GOLDEN_PORTRA -> Color(0xFFFBBF24)
    CameraColorProfile.MONOCHROME_NOIR -> Color(0xFFE2E8F0)
}
