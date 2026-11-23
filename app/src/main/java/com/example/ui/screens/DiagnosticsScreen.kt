package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.AppDestination
import com.example.camera.CameraDeviceCapabilities
import com.example.system.AppStrings
import com.example.ui.MainViewModel
import com.example.ui.components.formatShutterNs
import com.example.ui.theme.AmberFocus
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.EmeraldLock
import com.example.ui.theme.GraphiteBorder
import com.example.ui.theme.MachinedSlate
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SapphireTelemetry
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    viewModel: MainViewModel,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val inventory = viewModel.inventory.collectAsStateWithLifecycle().value
    val health = viewModel.healthState.collectAsStateWithLifecycle().value
    val jsonReport = viewModel.diagnosticsJson.collectAsStateWithLifecycle().value
    val statusBanner = viewModel.statusBanner.collectAsStateWithLifecycle().value
    var showJsonPreview by remember { mutableStateOf(false) }

    BackHandler {
        viewModel.navigateTo(AppDestination.CAMERA)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ObsidianVoid,
        topBar = {
            TopAppBar(
                title = { Text(strings.modeDiagnostics, style = MaterialTheme.typography.titleMedium, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(AppDestination.CAMERA) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.rescanHardware() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = strings.rescanHardware, tint = SapphireTelemetry)
                    }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_TEXT, jsonReport)
                        }
                        context.startActivity(Intent.createChooser(send, strings.shareReport))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = strings.shareReport, tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CarbonSurface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.exportDiagnosticsJson {} },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberFocus, contentColor = ObsidianVoid),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_diagnostics_json_button")
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.exportJsonReport, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { showJsonPreview = !showJsonPreview },
                        modifier = Modifier.testTag("toggle_json_preview_button")
                    ) {
                        Icon(Icons.Filled.Code, contentDescription = null, tint = SapphireTelemetry, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.rawJsonPreviewLabel, color = SapphireTelemetry, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                statusBanner?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.labelMedium, color = EmeraldLock, modifier = Modifier.padding(top = 6.dp))
                }
            }

            if (showJsonPreview && jsonReport.isNotBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MachinedSlate),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SapphireTelemetry.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = strings.rawJsonPreviewLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = SapphireTelemetry
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = jsonReport,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }

            // System & Thermal Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GraphiteBorder),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Memory, contentDescription = null, tint = AmberFocus, modifier = Modifier.size(18.dp))
                            Text("Device & Thermal Telemetry", style = MaterialTheme.typography.titleMedium, color = AmberFocus)
                        }
                        HorizontalDivider(color = GraphiteBorder.copy(alpha = 0.55f))
                        if (inventory != null) {
                            DiagRow("Model", "${inventory.deviceManufacturer} ${inventory.deviceModel}")
                            DiagRow("SoC / HW", "${inventory.socModel} (${inventory.deviceHardware})")
                            DiagRow("Android", "Android ${inventory.androidVersion} (API ${inventory.apiLevel})")
                            DiagRow("RAM", "${health.availableRamMb} MB free / ${inventory.totalRamMb} MB total")
                            DiagRow(
                                "Cameras Discovered",
                                "${inventory.cameras.size} total (${inventory.cameras.count { it.isUsableInApp }} usable)"
                            )
                        }
                        DiagRow("Thermal", "${health.thermalSeverity.name} (${"%.1f".format(health.batteryTempC)}°C)")
                        DiagRow("Battery", "${health.batteryPercent}% (Low=${health.isLowBattery})")
                        DiagRow("Adaptive Budget", "Burst=${health.recommendedMaxBurstFrames}, Night=${health.recommendedNightFrames}, SR=${health.allowSuperResolution}")
                    }
                }
            }

            // Video Encoders Card
            if (inventory != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, GraphiteBorder),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.Videocam, contentDescription = null, tint = SapphireTelemetry, modifier = Modifier.size(18.dp))
                                Text("MediaCodec Video Encoders", style = MaterialTheme.typography.titleMedium, color = SapphireTelemetry)
                            }
                            HorizontalDivider(color = GraphiteBorder.copy(alpha = 0.55f))
                            inventory.videoEncoders.forEach { enc ->
                                DiagRow(
                                    enc.codecType.label,
                                    if (enc.supported) "${enc.codecName} · ${enc.maxWidth}x${enc.maxHeight} · ${enc.maxBitrateBps / 1_000_000}Mbps"
                                    else "Unsupported"
                                )
                            }
                        }
                    }
                }

                items(inventory.cameras, key = { it.cameraId }) { cam ->
                    CameraCapabilityCard(cam)
                }
            }

            item { Spacer(Modifier.navigationBarsPadding().height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraCapabilityCard(cam: CameraDeviceCapabilities) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, GraphiteBorder),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = AmberFocus, modifier = Modifier.size(18.dp))
                    Text(
                        text = "ID ${cam.cameraId} · ${cam.facingLabel} (${cam.lensRole.name})",
                        style = MaterialTheme.typography.titleMedium,
                        color = AmberFocus,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        color = AmberFocus.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmberFocus.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = cam.hardwareLevelName,
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberFocus,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        color = (if (cam.isUsableInApp) EmeraldLock else SapphireTelemetry).copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            (if (cam.isUsableInApp) EmeraldLock else SapphireTelemetry).copy(alpha = 0.6f)
                        )
                    ) {
                        Text(
                            text = if (cam.isUsableInApp) "READY" else "AUX",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (cam.isUsableInApp) EmeraldLock else SapphireTelemetry,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            HorizontalDivider(color = GraphiteBorder.copy(alpha = 0.55f))
            DiagRow("Hardware Level", cam.hardwareLevelName)
            DiagRow("Usability", "${cam.isUsableInApp} — ${cam.usabilityNote}")
            DiagRow("Logical / Physical", "Logical=${cam.isLogicalMultiCamera}, Phys=${cam.physicalCameraIds.joinToString().ifBlank { "None" }}")
            DiagRow("Pixel Array / CFA", "${cam.pixelArraySize} (~${"%.1f".format(cam.megapixelEstimate)} MP) · ${cam.colorFilterArrangement}")
            DiagRow("Physical Size", cam.physicalSizeMm ?: "—")
            DiagRow("Aperture / Focal", "f/${cam.apertures.joinToString()} · ${cam.focalLengthsMm.joinToString()} mm")
            DiagRow("RAW / Manual", "RAW=${cam.supportsRaw}, ManualSensor=${cam.supportsManualSensor}, ManualPP=${cam.supportsManualPostProcessing}")
            DiagRow("Burst / Reproc", "Burst=${cam.supportsBurstCapture}, YUVReproc=${cam.supportsYuvReprocessing}, HighSpeed=${cam.supportsConstrainedHighSpeed}")
            DiagRow("AF Modes", cam.afModeNames.joinToString().ifBlank { "None" })
            DiagRow("Focus Range", "ManualFocus=${cam.supportsManualFocus}, MinDist=${cam.minimumFocusDistance}D (${cam.focusDistanceCalibration})")
            DiagRow("ISO Range", cam.isoRange?.let { "${it.first} .. ${it.last}" } ?: "—")
            DiagRow("Exposure Range", cam.exposureTimeRangeNs?.let { "${formatShutterNs(it.first)} .. ${formatShutterNs(it.last)}" } ?: "—")
            DiagRow("EV Comp", "${cam.aeCompensationRange.first}..${cam.aeCompensationRange.last} (step ${cam.aeCompensationStep})")
            DiagRow("AE / AWB Lock", "AELock=${cam.supportsAeLock}, AWBLock=${cam.supportsAwbLock}")
            DiagRow("AWB Modes", cam.awbModeNames.joinToString().ifBlank { "None" })
            DiagRow("Stabilization", "EIS=${cam.supportsEis}, PreviewStab=${cam.supportsPreviewStabilization}, OIS=${cam.supportsOis}")
            DiagRow("Noise Reduction", cam.noiseReductionModes.joinToString().ifBlank { "—" })
            DiagRow("Edge / Anti-Banding", "${cam.edgeModes.joinToString()} | ${cam.antiBandingModes.joinToString()}")
            if (cam.tonemapModes.isNotEmpty()) {
                DiagRow("Tonemap Modes", cam.tonemapModes.joinToString())
            }
            if (cam.shadingModes.isNotEmpty() || cam.hotPixelModes.isNotEmpty()) {
                DiagRow("Shading / HotPixel", "${cam.shadingModes.joinToString()} | ${cam.hotPixelModes.joinToString()}")
            }
            DiagRow("Flash / Max Zoom", "Flash=${cam.hasFlash}, MaxDigitalZoom=${"%.1f".format(cam.maxDigitalZoom)}x")
            DiagRow("Face Detect", "${cam.supportsFaceDetection} (max ${cam.maxFaceCount})")
            if (cam.concurrentCameraSets.isNotEmpty()) {
                DiagRow("Concurrent Sets", cam.concurrentCameraSets.joinToString(" | ") { it.joinToString("+") })
            }

            Text("Capabilities", style = MaterialTheme.typography.labelSmall, color = SapphireTelemetry)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cam.capabilities.forEach { cap ->
                    Text(
                        text = cap,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MachinedSlate)
                            .border(1.dp, GraphiteBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (cam.highSpeedConfigs.isNotEmpty()) {
                DiagRow(
                    "High-Speed Video",
                    cam.highSpeedConfigs.joinToString { "${it.width}x${it.height}@${it.minFps}-${it.maxFps}fps" }
                )
            }
            DiagRow("AE FPS Ranges", cam.aeTargetFpsRanges.joinToString { "${it.first}-${it.last}" })
            if (cam.videoFpsBySize.isNotEmpty()) {
                val fpsSummary = cam.videoFpsBySize.entries.take(4).joinToString("; ") { (sz, rng) ->
                    "$sz: ${rng.joinToString { "${it.first}-${it.last}" }}"
                } + if (cam.videoFpsBySize.size > 4) " (+${cam.videoFpsBySize.size - 4})" else ""
                DiagRow("Video Size FPS", fpsSummary)
            }
            if (cam.supportedExtensions.isNotEmpty()) {
                DiagRow("Extensions", cam.supportedExtensions.joinToString())
            }
            cam.outputFormats.forEach { fmt ->
                DiagRow(
                    "Format ${fmt.formatName}",
                    fmt.resolutions.take(5).joinToString { "${it.width}x${it.height}" } +
                        if (fmt.resolutions.size > 5) " (+${fmt.resolutions.size - 5})" else ""
                )
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
            modifier = Modifier.weight(0.58f)
        )
    }
}
