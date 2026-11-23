package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.camera.AudioSourceOption
import com.example.camera.CameraDeviceCapabilities
import com.example.camera.HardwareInventory
import com.example.camera.VideoCodecType
import com.example.system.AppStrings
import com.example.ui.VideoSettingsUiState
import com.example.ui.theme.AmberFocus
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.EmeraldLock
import com.example.ui.theme.GraphiteBorder
import com.example.ui.theme.SapphireTelemetry
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun VideoControlsBar(
    caps: CameraDeviceCapabilities,
    inventory: HardwareInventory?,
    videoSettings: VideoSettingsUiState,
    isSlowMotionMode: Boolean,
    estMinutesRemaining: Long,
    strings: AppStrings,
    onUpdateVideo: ((VideoSettingsUiState) -> VideoSettingsUiState) -> Unit,
    modifier: Modifier = Modifier,
    aiAssistantEnabled: Boolean = true,
    focusPowerMode: Boolean = false,
    aiBitrateMultiplier: Float = 1.15f
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CarbonSurface.copy(alpha = 0.88f))
            .border(1.dp, GraphiteBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (isSlowMotionMode) {
            if (!caps.supportsConstrainedHighSpeed || caps.highSpeedConfigs.isEmpty()) {
                Text(
                    text = strings.slowMotionUnavailable,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmberFocus
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    caps.highSpeedConfigs.forEach { hs ->
                        ProPillButton(
                            label = "${hs.width}×${hs.height} @ ${hs.maxFps}fps",
                            selected = videoSettings.selectedHighSpeed == hs,
                            onClick = { onUpdateVideo { it.copy(selectedHighSpeed = hs) } }
                        )
                    }
                }
            }
            return@Column
        }

        // Video Resolutions (strictly <= max discovered videoSizes, never advertising 4K unless supported)
        val filteredSizes = caps.videoSizes
            .filter { it.width <= 1920 || (caps.videoSizes.none { s -> s.width <= 1920 }) }
            .distinctBy { "${it.width}x${it.height}" }
            .take(6)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filteredSizes.forEach { sz ->
                val label = when {
                    sz.width == 1920 && sz.height == 1080 -> "1080p"
                    sz.width == 1280 && sz.height == 720 -> "720p"
                    else -> "${sz.width}×${sz.height}"
                }
                ProPillButton(
                    label = label,
                    selected = videoSettings.selectedSize == sz,
                    onClick = { onUpdateVideo { it.copy(selectedSize = sz) } }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Supported FPS for selected size + Codec + EIS + Audio + Timelapse
        val key = "${videoSettings.selectedSize.width}x${videoSettings.selectedSize.height}"
        val fpsChoices = caps.videoFpsBySize[key]
            ?.flatMap { listOf(it.last) }
            ?.filter { it >= 24 }
            ?.distinct()
            ?.sorted()
            ?.ifEmpty { listOf(30) }
            ?: listOf(30)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            fpsChoices.forEach { fps ->
                ProPillButton(
                    label = "${fps}fps",
                    selected = videoSettings.selectedFps == fps,
                    activeColor = SapphireTelemetry,
                    onClick = { onUpdateVideo { it.copy(selectedFps = fps) } }
                )
            }

            VideoCodecType.entries.forEach { codec ->
                val enc = inventory?.videoEncoders?.firstOrNull { it.codecType == codec }
                if (enc?.supported != false) {
                    ProPillButton(
                        label = if (codec == VideoCodecType.H264) "H.264" else "HEVC",
                        selected = videoSettings.codec == codec,
                        onClick = { onUpdateVideo { it.copy(codec = codec) } }
                    )
                }
            }

            if (caps.supportsEis) {
                ProPillButton(
                    label = "${strings.eisLabel}: ${if (videoSettings.eisEnabled) "ON" else "OFF"}",
                    selected = videoSettings.eisEnabled,
                    activeColor = EmeraldLock,
                    onClick = { onUpdateVideo { it.copy(eisEnabled = !it.eisEnabled) } }
                )
            }

            ProPillButton(
                label = if (videoSettings.recordAudio) "MIC ON" else strings.audioMuteLabel,
                selected = videoSettings.recordAudio,
                onClick = { onUpdateVideo { it.copy(recordAudio = !it.recordAudio) } }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Bitrate Slider & Time-lapse selector + AI/Turbo effective bitrate + storage estimate
        val encMaxMbps = ((inventory?.videoEncoders
            ?.firstOrNull { it.codecType == videoSettings.codec && it.supported }
            ?.maxBitrateBps ?: 60_000_000) / 1_000_000).coerceAtLeast(videoSettings.bitrateMbps)
        val effMult = (if (aiAssistantEnabled || focusPowerMode) aiBitrateMultiplier else 1.0f) *
            (if (focusPowerMode) 1.25f else 1.0f)
        val effectiveMbps = (videoSettings.bitrateMbps * effMult).roundToInt()
            .coerceIn(videoSettings.bitrateMbps, encMaxMbps)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bitrateText = if (effectiveMbps > videoSettings.bitrateMbps) {
                "${strings.bitrateLabel}: ${videoSettings.bitrateMbps}→${effectiveMbps} Mbps"
            } else {
                "${strings.bitrateLabel}: ${videoSettings.bitrateMbps} Mbps"
            }
            Text(
                text = bitrateText,
                style = MaterialTheme.typography.labelSmall,
                color = if (focusPowerMode) AmberFocus else TextSecondary
            )
            if (aiAssistantEnabled || focusPowerMode) {
                Text(
                    text = if (focusPowerMode) "${strings.aiVideoBoostBadge} · 100%" else strings.aiVideoBoostBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldLock
                )
            }
            Text(
                text = "${strings.estStorageRemaining}: ~${estMinutesRemaining} min",
                style = MaterialTheme.typography.labelSmall,
                color = SapphireTelemetry
            )
        }
        Slider(
            value = videoSettings.bitrateMbps.toFloat(),
            onValueChange = { mbps -> onUpdateVideo { it.copy(bitrateMbps = mbps.roundToInt()) } },
            valueRange = 8f..45f,
            colors = SliderDefaults.colors(thumbColor = AmberFocus, activeTrackColor = AmberFocus)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${strings.timelapseLabel}:",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            listOf(1, 2, 5, 10, 30).forEach { factor ->
                ProPillButton(
                    label = if (factor == 1) "1x" else "${factor}x",
                    selected = videoSettings.timeLapseFactor == factor,
                    onClick = { onUpdateVideo { it.copy(timeLapseFactor = factor) } }
                )
            }
            if (videoSettings.recordAudio && videoSettings.timeLapseFactor == 1) {
                AudioSourceOption.entries.forEach { src ->
                    ProPillButton(
                        label = src.name,
                        selected = videoSettings.audioSource == src,
                        onClick = { onUpdateVideo { it.copy(audioSource = src) } }
                    )
                }
            }
        }
    }
}
