package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.camera.LiveTelemetry
import com.example.ui.theme.AmberFocus
import com.example.ui.theme.CrimsonRecord
import com.example.ui.theme.EmeraldLock
import com.example.ui.theme.GraphiteBorder
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SapphireTelemetry
import com.example.ui.theme.TextSecondary

data class HistogramExposureStats(
    val redClipping: Boolean,
    val greenClipping: Boolean,
    val blueClipping: Boolean,
    val shadowClipping: Boolean,
    val highlightClipping: Boolean
)

fun computeHistogramExposureStats(telemetry: LiveTelemetry): HistogramExposureStats {
    fun topEdgeSpike(bins: IntArray): Boolean {
        if (bins.size < 64) return false
        val total = bins.sum().coerceAtLeast(1)
        val topBins = bins[62] + bins[63]
        return (topBins * 100f / total) >= 2.5f
    }
    return HistogramExposureStats(
        redClipping = telemetry.redHighlightClipPercent >= 2.5f || topEdgeSpike(telemetry.redHistogram),
        greenClipping = telemetry.greenHighlightClipPercent >= 2.5f || topEdgeSpike(telemetry.greenHistogram),
        blueClipping = telemetry.blueHighlightClipPercent >= 2.5f || topEdgeSpike(telemetry.blueHistogram),
        shadowClipping = telemetry.shadowClippingPercent > 5f,
        highlightClipping = telemetry.highlightClippingPercent > 2.5f
    )
}

@Composable
fun LiveHistogramOverlay(
    telemetry: LiveTelemetry,
    showRgb: Boolean,
    modifier: Modifier = Modifier,
    rgbLabel: String = "RGB",
    lumaLabel: String = "LUMA",
    onToggleMode: (() -> Unit)? = null
) {
    val stats = computeHistogramExposureStats(telemetry)
    val anyHighlightClip = stats.highlightClipping || stats.redClipping || stats.greenClipping || stats.blueClipping

    Column(
        modifier = modifier
            .width(164.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ObsidianVoid.copy(alpha = 0.78f))
            .border(
                width = 1.dp,
                color = when {
                    anyHighlightClip -> CrimsonRecord.copy(alpha = 0.85f)
                    stats.shadowClipping -> SapphireTelemetry.copy(alpha = 0.85f)
                    else -> GraphiteBorder.copy(alpha = 0.8f)
                },
                shape = RoundedCornerShape(12.dp)
            )
            .then(
                if (onToggleMode != null) Modifier.clickable(onClick = onToggleMode) else Modifier
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("live_histogram_overlay")
    ) {
        // Header: RGB/LUMA Badge + Live Per-Channel Clipping Dots
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (showRgb) rgbLabel else lumaLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (showRgb) AmberFocus else Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(GraphiteBorder.copy(alpha = 0.45f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
                    .testTag("histogram_mode_pill")
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChannelClipDot(active = stats.redClipping, baseColor = Color(0xFFEF4444))
                ChannelClipDot(active = stats.greenClipping, baseColor = Color(0xFF10B981))
                ChannelClipDot(active = stats.blueClipping, baseColor = Color(0xFF38BDF8))
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            // Exposure zone vertical guides (Shadows | Midtones | Highlights)
            val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            for (frac in listOf(0.25f, 0.5f, 0.75f)) {
                val gx = size.width * frac
                drawLine(
                    color = Color.White.copy(alpha = 0.14f),
                    start = Offset(gx, 0f),
                    end = Offset(gx, size.height),
                    strokeWidth = 1f,
                    pathEffect = dash
                )
            }
            drawLine(
                color = Color.White.copy(alpha = 0.10f),
                start = Offset(0f, size.height * 0.5f),
                end = Offset(size.width, size.height * 0.5f),
                strokeWidth = 1f
            )

            // Shadow crush / highlight blow-out edge warning strips
            if (stats.shadowClipping) {
                drawRoundRect(
                    color = SapphireTelemetry.copy(alpha = 0.36f),
                    topLeft = Offset.Zero,
                    size = Size(size.width * 0.05f, size.height),
                    cornerRadius = CornerRadius(3f, 3f)
                )
            }
            if (anyHighlightClip) {
                drawRoundRect(
                    color = CrimsonRecord.copy(alpha = 0.42f),
                    topLeft = Offset(size.width * 0.95f, 0f),
                    size = Size(size.width * 0.05f, size.height),
                    cornerRadius = CornerRadius(3f, 3f)
                )
            }

            if (showRgb) {
                // Shared peak scaling across R, G, B prevents weak channels from exaggerating noise
                val sharedPeak = maxOf(
                    telemetry.redHistogram.maxOrNull() ?: 1,
                    telemetry.greenHistogram.maxOrNull() ?: 1,
                    telemetry.blueHistogram.maxOrNull() ?: 1,
                    1
                ).toFloat()
                drawHistCurve(telemetry.lumaHistogram, Color.White.copy(alpha = 0.24f), filled = false, overridePeak = sharedPeak)
                drawHistCurve(telemetry.redHistogram, Color(0xFFEF4444), filled = true, fillAlpha = 0.18f, overridePeak = sharedPeak)
                drawHistCurve(telemetry.greenHistogram, Color(0xFF10B981), filled = true, fillAlpha = 0.18f, overridePeak = sharedPeak)
                drawHistCurve(telemetry.blueHistogram, Color(0xFF38BDF8), filled = true, fillAlpha = 0.18f, overridePeak = sharedPeak)
            } else {
                drawHistCurve(telemetry.lumaHistogram, Color.White.copy(alpha = 0.90f), filled = true, fillAlpha = 0.32f)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val shColor = if (stats.shadowClipping) SapphireTelemetry else TextSecondary
            val hiColor = if (anyHighlightClip) CrimsonRecord else TextSecondary
            Text(
                text = "SH ${"%.1f".format(telemetry.shadowClippingPercent)}%",
                style = MaterialTheme.typography.labelSmall,
                color = shColor,
                fontWeight = if (stats.shadowClipping) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = "HI ${"%.1f".format(telemetry.highlightClippingPercent)}%",
                style = MaterialTheme.typography.labelSmall,
                color = hiColor,
                fontWeight = if (anyHighlightClip) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun ChannelClipDot(active: Boolean, baseColor: Color) {
    Box(
        modifier = Modifier
            .size(if (active) 7.dp else 5.dp)
            .clip(CircleShape)
            .background(if (active) baseColor else baseColor.copy(alpha = 0.32f))
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistCurve(
    bins: IntArray,
    color: Color,
    filled: Boolean,
    fillAlpha: Float = 0.30f,
    overridePeak: Float? = null
) {
    if (bins.isEmpty()) return
    val maxVal = (overridePeak ?: (bins.maxOrNull() ?: 1).toFloat()).coerceAtLeast(1f)
    val stepX = size.width / (bins.size - 1).coerceAtLeast(1)
    val path = Path()
    if (filled) path.moveTo(0f, size.height)

    for (i in bins.indices) {
        val x = i * stepX
        val norm = (bins[i] / maxVal).coerceIn(0f, 1f)
        val y = size.height - (norm * size.height * 0.92f)
        if (i == 0 && !filled) path.moveTo(x, y) else path.lineTo(x, y)
    }

    if (filled) {
        path.lineTo(size.width, size.height)
        path.close()
        drawPath(path, color.copy(alpha = fillAlpha), style = Fill)
    }
    drawPath(path, color, style = Stroke(width = 1.75f))
}

@Composable
fun AudioLevelMeterBar(
    level: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ObsidianVoid.copy(alpha = 0.78f))
            .border(1.dp, GraphiteBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "MIC",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(GraphiteBorder)
        ) {
            val fillColor = when {
                level > 0.85f -> CrimsonRecord
                level > 0.6f -> AmberFocus
                else -> EmeraldLock
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(level.coerceIn(0.04f, 1f))
                    .height(6.dp)
                    .background(fillColor)
            )
        }
    }
}
