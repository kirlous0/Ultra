package com.example.ui.components

import android.hardware.camera2.CaptureRequest
import android.util.Size
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import com.example.camera.CameraDeviceCapabilities
import com.example.camera.CaptureFormat
import com.example.camera.FlashMode
import com.example.camera.FocusPeakingColor
import com.example.camera.FocusPeakingSensitivity
import com.example.camera.ProControlsState
import com.example.camera.WhiteBalancePreset
import com.example.system.AppStrings
import com.example.ui.theme.AmberFocus
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.EmeraldLock
import com.example.ui.theme.GraphiteBorder
import com.example.ui.theme.MachinedSlate
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SapphireTelemetry
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.ln
import kotlin.math.roundToInt

private enum class ProControlTab {
    ISO_SHUTTER,
    FOCUS,
    WB_EV,
    FORMAT_ISP,
    DRIVE_BURST
}

@Composable
fun ProControlsBar(
    caps: CameraDeviceCapabilities,
    controls: ProControlsState,
    strings: AppStrings,
    onUpdateControls: ((ProControlsState) -> ProControlsState) -> Unit,
    modifier: Modifier = Modifier,
    focusPeakingEnabled: Boolean = false,
    focusPeakingColor: FocusPeakingColor = FocusPeakingColor.CYAN,
    focusPeakingSensitivity: FocusPeakingSensitivity = FocusPeakingSensitivity.MEDIUM,
    focusSharpnessPercent: Int = 0,
    onToggleFocusPeaking: (() -> Unit)? = null,
    onSelectPeakingStyle: ((FocusPeakingColor?, FocusPeakingSensitivity?) -> Unit)? = null
) {
    var activeTab by remember { mutableStateOf(ProControlTab.ISO_SHUTTER) }

    // Never keep Focus tab active when switching to a fixed-focus lens (e.g., front camera)
    LaunchedEffect(caps.cameraId, caps.supportsManualFocus) {
        if (!caps.supportsManualFocus && activeTab == ProControlTab.FOCUS) {
            activeTab = ProControlTab.ISO_SHUTTER
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CarbonSurface.copy(alpha = 0.88f))
            .border(1.dp, GraphiteBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // Parameter selector pills + AE Lock + AF Lock + Flash
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProPillButton(
                label = "${strings.isoLabel} ${if (controls.manualExposureEnabled) controls.iso else strings.autoLabel}",
                selected = activeTab == ProControlTab.ISO_SHUTTER,
                testTag = "pro_tab_iso_shutter",
                onClick = { activeTab = ProControlTab.ISO_SHUTTER }
            )
            if (caps.supportsManualFocus) {
                ProPillButton(
                    label = "${strings.focusLabel} ${if (controls.manualFocusEnabled) "%.1fD".format(controls.focusDistanceDiopters) else strings.autoLabel}",
                    selected = activeTab == ProControlTab.FOCUS,
                    testTag = "pro_tab_focus",
                    onClick = { activeTab = ProControlTab.FOCUS }
                )
            }
            ProPillButton(
                label = "${strings.wbLabel} / ${strings.evLabel}",
                selected = activeTab == ProControlTab.WB_EV,
                testTag = "pro_tab_wb_ev",
                onClick = { activeTab = ProControlTab.WB_EV }
            )
            ProPillButton(
                label = "${controls.captureFormat.name.replace("_", "+")} · ${controls.jpegQuality}%",
                selected = activeTab == ProControlTab.FORMAT_ISP,
                testTag = "pro_tab_format",
                onClick = { activeTab = ProControlTab.FORMAT_ISP }
            )
            ProPillButton(
                label = if (controls.burstEnabledInPro) {
                    "${strings.burstDriveLabel}: ${controls.burstFrameCount}F"
                } else {
                    strings.burstDriveLabel
                },
                selected = activeTab == ProControlTab.DRIVE_BURST || controls.burstEnabledInPro,
                activeColor = if (controls.burstEnabledInPro) SapphireTelemetry else AmberFocus,
                testTag = "pro_tab_burst",
                onClick = { activeTab = ProControlTab.DRIVE_BURST }
            )
            if (caps.supportsAeLock) {
                ProPillButton(
                    label = if (controls.aeLocked) "${strings.aeLockLabel} ✓" else strings.aeLockLabel,
                    selected = controls.aeLocked,
                    activeColor = EmeraldLock,
                    testTag = "pro_ae_lock",
                    onClick = { onUpdateControls { it.copy(aeLocked = !it.aeLocked) } }
                )
            }
            if (caps.supportsManualFocus) {
                ProPillButton(
                    label = if (controls.afLocked) "${strings.afLockLabel} ✓" else strings.afLockLabel,
                    selected = controls.afLocked,
                    activeColor = EmeraldLock,
                    testTag = "pro_af_lock",
                    onClick = {
                        onUpdateControls {
                            it.copy(
                                afLocked = !it.afLocked,
                                manualFocusEnabled = if (!it.afLocked) false else it.manualFocusEnabled
                            )
                        }
                    }
                )
            }
            if (caps.hasFlash) {
                ProPillButton(
                    label = "${strings.flashLabel}: ${controls.flashMode.name}",
                    selected = controls.flashMode != FlashMode.OFF,
                    activeColor = AmberFocus,
                    testTag = "pro_flash_toggle",
                    onClick = {
                        val next = when (controls.flashMode) {
                            FlashMode.OFF -> FlashMode.AUTO
                            FlashMode.AUTO -> FlashMode.ON
                            FlashMode.ON -> FlashMode.TORCH
                            FlashMode.TORCH -> FlashMode.OFF
                        }
                        onUpdateControls { it.copy(flashMode = next) }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        when (activeTab) {
            ProControlTab.ISO_SHUTTER -> {
                if (!caps.supportsManualSensor) {
                    Text(
                        text = "Manual sensor exposure not exposed by this lens (Hardware: ${caps.hardwareLevelName})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                } else {
                    val isoRange = caps.isoRange ?: (100..3200)
                    val expRange = caps.exposureTimeRangeNs ?: (100_000L..1_000_000_000L)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = !controls.manualExposureEnabled,
                            onClick = { onUpdateControls { it.copy(manualExposureEnabled = !it.manualExposureEnabled) } },
                            label = {
                                Text(
                                    if (controls.manualExposureEnabled) strings.manualLabel else strings.autoLabel,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AmberFocus,
                                selectedLabelColor = ObsidianVoid
                            ),
                            modifier = Modifier.testTag("pro_manual_exposure_toggle")
                        )
                        Text(
                            text = "ISO ${controls.iso}  ·  ${formatShutterNs(controls.exposureTimeNs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = AmberFocus
                        )
                    }

                    Text(
                        text = "${strings.isoLabel} (${isoRange.first}..${isoRange.last})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Slider(
                        value = controls.iso.toFloat().coerceIn(isoRange.first.toFloat(), isoRange.last.toFloat()),
                        onValueChange = { v ->
                            val snapped = ((v / 25f).roundToInt() * 25).coerceIn(isoRange.first, isoRange.last)
                            onUpdateControls {
                                it.copy(manualExposureEnabled = true, iso = snapped)
                            }
                        },
                        valueRange = isoRange.first.toFloat()..isoRange.last.toFloat().coerceAtLeast(isoRange.first.toFloat() + 1f),
                        colors = SliderDefaults.colors(thumbColor = AmberFocus, activeTrackColor = AmberFocus),
                        modifier = Modifier.testTag("pro_iso_slider")
                    )

                    val minLog = ln(expRange.first.toDouble().coerceAtLeast(1000.0))
                    val maxLog = ln(expRange.last.toDouble().coerceAtLeast(2000.0))
                    val curLog = ln(controls.exposureTimeNs.toDouble().coerceIn(expRange.first.toDouble(), expRange.last.toDouble()))

                    Text(
                        text = "${strings.shutterLabel} (${formatShutterNs(expRange.first)} .. ${formatShutterNs(expRange.last)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Slider(
                        value = curLog.toFloat(),
                        onValueChange = { v ->
                            val ns = kotlin.math.exp(v.toDouble()).toLong().coerceIn(expRange.first, expRange.last)
                            onUpdateControls { it.copy(manualExposureEnabled = true, exposureTimeNs = ns) }
                        },
                        valueRange = minLog.toFloat()..maxLog.toFloat().coerceAtLeast(minLog.toFloat() + 0.01f),
                        colors = SliderDefaults.colors(thumbColor = SapphireTelemetry, activeTrackColor = SapphireTelemetry),
                        modifier = Modifier.testTag("pro_shutter_slider")
                    )
                }
            }

            ProControlTab.FOCUS -> {
                if (!caps.supportsManualFocus) {
                    Text(
                        text = strings.fixedFocusNotice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmberFocus
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = !controls.manualFocusEnabled,
                            onClick = {
                                onUpdateControls {
                                    it.copy(
                                        manualFocusEnabled = !it.manualFocusEnabled,
                                        afLocked = if (!it.manualFocusEnabled) false else it.afLocked
                                    )
                                }
                            },
                            label = {
                                Text(
                                    if (controls.manualFocusEnabled) strings.manualLabel else strings.autoLabel,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AmberFocus,
                                selectedLabelColor = ObsidianVoid
                            ),
                            modifier = Modifier.testTag("pro_manual_focus_toggle")
                        )
                        val distLabel = if (controls.focusDistanceDiopters <= 0.05f) "∞"
                        else "${"%.2f".format(1f / controls.focusDistanceDiopters)} m"
                        Text(
                            text = "${"%.2f".format(controls.focusDistanceDiopters)}/${"%.1f".format(caps.minimumFocusDistance)} D ($distLabel) · ${caps.focusDistanceCalibration}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SapphireTelemetry
                        )
                    }
                    Slider(
                        value = controls.focusDistanceDiopters.coerceIn(0f, caps.minimumFocusDistance),
                        onValueChange = { d ->
                            onUpdateControls {
                                it.copy(
                                    manualFocusEnabled = true,
                                    afLocked = false,
                                    focusDistanceDiopters = d
                                )
                            }
                        },
                        valueRange = 0f..caps.minimumFocusDistance.coerceAtLeast(0.1f),
                        colors = SliderDefaults.colors(thumbColor = AmberFocus, activeTrackColor = AmberFocus),
                        modifier = Modifier.testTag("pro_focus_slider")
                    )

                    if (onToggleFocusPeaking != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val peakTint = Color(focusPeakingColor.argb)
                            ProPillButton(
                                label = if (focusPeakingEnabled) {
                                    "${strings.focusPeaking} · ${strings.focusSharpnessPeakLabel} ${focusSharpnessPercent}%"
                                } else {
                                    strings.focusPeaking
                                },
                                selected = focusPeakingEnabled,
                                activeColor = peakTint,
                                testTag = "pro_focus_peaking_toggle",
                                onClick = onToggleFocusPeaking
                            )
                            if (focusPeakingEnabled && onSelectPeakingStyle != null) {
                                FocusPeakingColor.entries.forEach { c ->
                                    val cVal = Color(c.argb)
                                    val selectedColor = focusPeakingColor == c
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(cVal)
                                            .border(
                                                width = if (selectedColor) 2.5.dp else 1.dp,
                                                color = if (selectedColor) Color.White else GraphiteBorder,
                                                shape = CircleShape
                                            )
                                            .clickable { onSelectPeakingStyle(c, null) }
                                            .testTag("peaking_color_${c.name.lowercase()}")
                                    )
                                }
                                FocusPeakingSensitivity.entries.forEach { sens ->
                                    ProPillButton(
                                        label = if (strings.isArabic) sens.labelAr else sens.labelEn,
                                        selected = focusPeakingSensitivity == sens,
                                        activeColor = peakTint,
                                        testTag = "peaking_sens_${sens.name.lowercase()}",
                                        onClick = { onSelectPeakingStyle(null, sens) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ProControlTab.WB_EV -> {
                val evSteps = caps.aeCompensationRange
                val minEv = evSteps.first * caps.aeCompensationStep
                val maxEv = evSteps.last * caps.aeCompensationStep
                val evVal = controls.exposureCompensationSteps * caps.aeCompensationStep
                Text(
                    text = "${strings.evLabel}: ${if (evVal >= 0) "+" else ""}${"%.2f".format(evVal)} EV (${"%.1f".format(minEv)}..+${"%.1f".format(maxEv)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                if (evSteps.last > evSteps.first) {
                    Slider(
                        value = controls.exposureCompensationSteps.toFloat().coerceIn(evSteps.first.toFloat(), evSteps.last.toFloat()),
                        onValueChange = { v ->
                            onUpdateControls {
                                it.copy(manualExposureEnabled = false, exposureCompensationSteps = v.roundToInt())
                            }
                        },
                        valueRange = evSteps.first.toFloat()..evSteps.last.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = AmberFocus, activeTrackColor = AmberFocus),
                        modifier = Modifier.testTag("pro_ev_slider")
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WhiteBalancePreset.entries.forEach { preset ->
                        val isSupported = if (preset == WhiteBalancePreset.MANUAL_KELVIN) {
                            caps.supportsManualPostProcessing
                        } else {
                            caps.awbModes.isEmpty() || caps.awbModes.contains(preset.awbMode)
                        }
                        if (!isSupported) return@forEach
                        ProPillButton(
                            label = if (strings.isArabic) preset.labelAr else preset.labelEn,
                            selected = controls.whiteBalancePreset == preset,
                            testTag = "pro_wb_${preset.name.lowercase()}",
                            onClick = { onUpdateControls { it.copy(whiteBalancePreset = preset) } }
                        )
                    }
                }

                if (controls.whiteBalancePreset == WhiteBalancePreset.MANUAL_KELVIN && caps.supportsManualPostProcessing) {
                    Text(
                        text = "${controls.manualKelvin} K (2500K .. 8000K)",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberFocus,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Slider(
                        value = controls.manualKelvin.toFloat(),
                        onValueChange = { k ->
                            onUpdateControls { it.copy(manualKelvin = (k / 100f).roundToInt() * 100) }
                        },
                        valueRange = 2500f..8000f,
                        colors = SliderDefaults.colors(thumbColor = AmberFocus, activeTrackColor = AmberFocus),
                        modifier = Modifier.testTag("pro_kelvin_slider")
                    )
                }
            }

            ProControlTab.FORMAT_ISP -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ProPillButton(
                        label = "JPEG",
                        selected = controls.captureFormat == CaptureFormat.JPEG,
                        testTag = "pro_fmt_jpeg",
                        onClick = { onUpdateControls { it.copy(captureFormat = CaptureFormat.JPEG) } }
                    )
                    if (caps.supportsRaw) {
                        ProPillButton(
                            label = "RAW (DNG)",
                            selected = controls.captureFormat == CaptureFormat.RAW_DNG,
                            testTag = "pro_fmt_raw",
                            onClick = {
                                onUpdateControls {
                                    it.copy(
                                        captureFormat = CaptureFormat.RAW_DNG,
                                        burstEnabledInPro = false
                                    )
                                }
                            }
                        )
                        ProPillButton(
                            label = "RAW + JPEG",
                            selected = controls.captureFormat == CaptureFormat.RAW_PLUS_JPEG,
                            testTag = "pro_fmt_raw_jpeg",
                            onClick = {
                                onUpdateControls {
                                    it.copy(
                                        captureFormat = CaptureFormat.RAW_PLUS_JPEG,
                                        burstEnabledInPro = false
                                    )
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${strings.resolutionLabel} (${caps.jpegSizes.size}):",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    caps.jpegSizes.forEach { sz: Size ->
                        val mp = (sz.width.toLong() * sz.height.toLong()) / 1_000_000.0
                        ProPillButton(
                            label = "${sz.width}×${sz.height} (${"%.1f".format(mp)}MP)",
                            selected = controls.selectedResolution == sz,
                            onClick = { onUpdateControls { it.copy(selectedResolution = sz) } }
                        )
                    }
                }

                Text(
                    text = "${strings.jpegQualityLabel}: ${controls.jpegQuality}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Slider(
                    value = controls.jpegQuality.toFloat(),
                    onValueChange = { q -> onUpdateControls { it.copy(jpegQuality = q.roundToInt()) } },
                    valueRange = 60f..100f,
                    colors = SliderDefaults.colors(thumbColor = AmberFocus, activeTrackColor = AmberFocus),
                    modifier = Modifier.testTag("pro_jpeg_quality_slider")
                )

                // Discovered Noise Reduction modes
                val nrPairs = if (caps.noiseReductionModeIds.isNotEmpty()) {
                    caps.noiseReductionModeIds.zip(caps.noiseReductionModes)
                } else {
                    listOf(
                        CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY to "HIGH_QUALITY",
                        CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL to "MINIMAL",
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF to "OFF"
                    )
                }
                Text(
                    text = "${strings.nrLabel}:",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    nrPairs.forEach { (modeId, name) ->
                        ProPillButton(
                            label = name.replace("HIGH_QUALITY", "HQ"),
                            selected = controls.noiseReductionMode == modeId,
                            onClick = { onUpdateControls { it.copy(noiseReductionMode = modeId) } }
                        )
                    }
                }

                // Discovered Edge Enhancement modes
                val edgePairs = if (caps.edgeModeIds.isNotEmpty()) {
                    caps.edgeModeIds.zip(caps.edgeModes)
                } else {
                    listOf(
                        CaptureRequest.EDGE_MODE_HIGH_QUALITY to "HIGH_QUALITY",
                        CaptureRequest.EDGE_MODE_FAST to "FAST",
                        CaptureRequest.EDGE_MODE_OFF to "OFF"
                    )
                }
                Text(
                    text = "${strings.edgeLabel}:",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    edgePairs.forEach { (modeId, name) ->
                        ProPillButton(
                            label = name.replace("HIGH_QUALITY", "HQ"),
                            selected = controls.edgeEnhancementMode == modeId,
                            onClick = { onUpdateControls { it.copy(edgeEnhancementMode = modeId) } }
                        )
                    }
                }
            }

            ProControlTab.DRIVE_BURST -> {
                Text(
                    text = if (caps.supportsBurstCapture) strings.burstHardwareSupported else strings.burstSoftwareFallback,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (caps.supportsBurstCapture) EmeraldLock else SapphireTelemetry
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProPillButton(
                        label = strings.singleDriveLabel,
                        selected = !controls.burstEnabledInPro,
                        testTag = "pro_drive_single",
                        onClick = { onUpdateControls { it.copy(burstEnabledInPro = false) } }
                    )
                    ProPillButton(
                        label = strings.continuousBurstLabel,
                        selected = controls.burstEnabledInPro,
                        activeColor = SapphireTelemetry,
                        testTag = "pro_burst_drive_toggle",
                        onClick = {
                            onUpdateControls {
                                it.copy(
                                    burstEnabledInPro = true,
                                    captureFormat = CaptureFormat.JPEG
                                )
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${strings.burstFramesLabel}: ${controls.burstFrameCount}F",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(3, 5, 8, 10, 12).forEach { count ->
                        ProPillButton(
                            label = "${count}F",
                            selected = controls.burstFrameCount == count,
                            activeColor = SapphireTelemetry,
                            testTag = "pro_burst_count_$count",
                            onClick = {
                                onUpdateControls {
                                    it.copy(
                                        burstEnabledInPro = true,
                                        burstFrameCount = count,
                                        captureFormat = CaptureFormat.JPEG
                                    )
                                }
                            }
                        )
                    }
                }
                Text(
                    text = strings.holdForBurstHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun ProPillButton(
    label: String,
    selected: Boolean,
    activeColor: androidx.compose.ui.graphics.Color = AmberFocus,
    testTag: String = "",
    onClick: () -> Unit
) {
    val bg = if (selected) activeColor else MachinedSlate
    val fg = if (selected) ObsidianVoid else TextPrimary
    val mod = if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier
    Box(
        modifier = mod
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, if (selected) activeColor else GraphiteBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg
        )
    }
}

fun formatShutterNs(ns: Long): String {
    if (ns <= 0L) return "—"
    val sec = ns / 1_000_000_000.0
    return if (sec >= 0.4) {
        "${"%.1f".format(sec)}s"
    } else {
        val denom = (1_000_000_000.0 / ns).roundToInt().coerceAtLeast(1)
        "1/${denom}s"
    }
}
