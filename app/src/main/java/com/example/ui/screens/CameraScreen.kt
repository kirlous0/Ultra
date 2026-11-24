package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.camera.AppDestination
import com.example.camera.BackgroundProcessingJob
import com.example.camera.BackgroundProcessingStage
import com.example.camera.CameraPreview
import com.example.camera.FlashMode
import com.example.camera.FocusPeakingColor
import com.example.camera.FocusPeakingSensitivity
import com.example.camera.GridLineColor
import com.example.camera.GridLineThickness
import com.example.camera.GridOverlayConfig
import com.example.camera.GridOverlayType
import com.example.camera.LensRole
import com.example.camera.PhotoToolMode
import com.example.processing.CameraColorProfile
import com.example.processing.RecognizedScene
import com.example.system.AppStrings
import com.example.system.ThermalSeverity
import com.example.ui.MainViewModel
import com.example.ui.components.AudioLevelMeterBar
import com.example.ui.components.CameraLabSheet
import com.example.ui.components.LiveHistogramOverlay
import com.example.ui.components.LowLightExposureIsoStrip
import com.example.ui.components.ProControlsBar
import com.example.ui.components.ProPillButton
import com.example.ui.components.VideoControlsBar
import com.example.ui.components.formatShutterNs
import com.example.ui.components.profileAccentColor
import com.example.ui.theme.AmberFocus
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CrimsonRecord
import com.example.ui.theme.EmeraldLock
import com.example.ui.theme.GraphiteBorder
import com.example.ui.theme.MachinedSlate
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SapphireTelemetry
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    strings: AppStrings,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val currentDest by viewModel.currentDestination.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val health by viewModel.healthState.collectAsStateWithLifecycle()
    val inventory by viewModel.inventory.collectAsStateWithLifecycle()
    val activeCam by viewModel.activeCamera.collectAsStateWithLifecycle()
    val proControls by viewModel.proControls.collectAsStateWithLifecycle()
    val photoTool by viewModel.photoToolMode.collectAsStateWithLifecycle()
    val timerSec by viewModel.timerSeconds.collectAsStateWithLifecycle()
    val videoSettings by viewModel.videoSettings.collectAsStateWithLifecycle()
    val videoRecState by viewModel.videoRecordingState.collectAsStateWithLifecycle()
    val galleryItems by viewModel.galleryItems.collectAsStateWithLifecycle()
    val processingJobs by viewModel.processingJobs.collectAsStateWithLifecycle()

    val previewSize by viewModel.cameraController.previewSize.collectAsStateWithLifecycle()
    val telemetry by viewModel.cameraController.telemetry.collectAsStateWithLifecycle()
    val peakingBmp by viewModel.cameraController.peakingOverlay.collectAsStateWithLifecycle()
    val zebraBmp by viewModel.cameraController.zebraOverlay.collectAsStateWithLifecycle()
    val aiRec by viewModel.cameraController.aiRecommendation.collectAsStateWithLifecycle()
    val qrResult by viewModel.cameraController.qrResult.collectAsStateWithLifecycle()
    val isCapturing by viewModel.cameraController.isCapturing.collectAsStateWithLifecycle()
    val procStage by viewModel.cameraController.processingStage.collectAsStateWithLifecycle()
    val burstProg by viewModel.cameraController.burstProgress.collectAsStateWithLifecycle()
    val countdown by viewModel.cameraController.countdownSec.collectAsStateWithLifecycle()
    val panoCount by viewModel.cameraController.panoramaFramesCount.collectAsStateWithLifecycle()
    val intervalProg by viewModel.cameraController.intervalProgress.collectAsStateWithLifecycle()
    val feedback by viewModel.cameraController.captureFeedback.collectAsStateWithLifecycle()
    val focusPt by viewModel.cameraController.focusPointNormalized.collectAsStateWithLifecycle()
    val liveShadowBias by viewModel.cameraController.liveShadowBias.collectAsStateWithLifecycle()
    val liveHighlightBias by viewModel.cameraController.liveHighlightBias.collectAsStateWithLifecycle()

    var showOverlayPanel by remember { mutableStateOf(false) }
    var showCameraLab by remember { mutableStateOf(false) }
    var showLiveTonePanel by remember { mutableStateOf(false) }
    var showEvIsoPanel by remember { mutableStateOf(false) }
    var showProcessingSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = showOverlayPanel || showLiveTonePanel || showEvIsoPanel || showCameraLab || showProcessingSheet) {
        when {
            showProcessingSheet -> showProcessingSheet = false
            showCameraLab -> showCameraLab = false
            showEvIsoPanel -> showEvIsoPanel = false
            showLiveTonePanel -> showLiveTonePanel = false
            showOverlayPanel -> showOverlayPanel = false
        }
    }

    // Request RECORD_AUDIO only when entering Video mode with audio recording enabled
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.updateVideoSettings { it.copy(recordAudio = false) }
        }
    }

    LaunchedEffect(currentDest, videoSettings.recordAudio) {
        if (currentDest == AppDestination.VIDEO && videoSettings.recordAudio) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(ObsidianVoid)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CenterFocusStrong,
                    contentDescription = null,
                    tint = AmberFocus,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = strings.cameraPermissionTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary
                )
                Text(
                    text = strings.cameraPermissionDesc,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary
                )
                Button(
                    onClick = onRequestCameraPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = AmberFocus, contentColor = ObsidianVoid),
                    modifier = Modifier.testTag("grant_camera_permission_button")
                ) {
                    Text(strings.grantPermission)
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = { viewModel.navigateTo(AppDestination.DIAGNOSTICS) },
                    modifier = Modifier.testTag("permission_screen_diagnostics_button")
                ) {
                    Icon(Icons.Filled.MonitorHeart, contentDescription = null, tint = SapphireTelemetry)
                    Spacer(Modifier.width(6.dp))
                    Text(strings.modeDiagnostics, color = SapphireTelemetry)
                }
            }
        }
        return
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        val isLandscape = maxWidth > maxHeight

        // Full-bleed Viewfinder occupying largest practical area
        CameraPreview(
            previewSize = previewSize,
            sensorOrientation = activeCam?.sensorOrientation ?: 90,
            isFrontCamera = activeCam?.lensRole == LensRole.FRONT,
            telemetry = telemetry,
            gridType = settings.gridOverlay,
            gridConfig = GridOverlayConfig(
                type = settings.gridOverlay,
                opacity = settings.gridOpacity,
                thickness = settings.gridThickness,
                color = settings.gridColor,
                showDiagonals = settings.gridShowDiagonals,
                showIntersectionDots = settings.gridShowIntersectionDots
            ),
            showDigitalLevel = settings.showDigitalLevel,
            peakingBitmap = if (settings.focusPeakingEnabled) peakingBmp else null,
            zebraBitmap = if (settings.zebraWarningsEnabled) zebraBmp else null,
            focusPointNormalized = focusPt,
            isFocusLocked = proControls.afLocked || proControls.aeLocked,
            zoomRatio = proControls.digitalZoomRatio,
            libPatcher = settings.libPatcher,
            aiRecommendation = aiRec,
            aiAutoAdjustEnabled = settings.aiAssistantEnabled && settings.aiAutoAdjustEnabled,
            onSurfaceTextureReady = { st -> viewModel.attachPreviewTexture(st) },
            onSurfaceTextureDestroyed = { st -> viewModel.detachPreviewTexture(st) },
            onTapFocus = { x, y ->
                if (settings.hapticShutterFeedback) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                viewModel.cameraController.triggerTapToFocus(x, y, lockAeAf = false)
            },
            onLongPressLock = { x, y ->
                if (settings.hapticShutterFeedback) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                viewModel.cameraController.triggerTapToFocus(x, y, lockAeAf = true)
            },
            onPinchZoomDelta = { delta ->
                val maxZ = activeCam?.maxDigitalZoom ?: 4f
                viewModel.updateProControls {
                    it.copy(digitalZoomRatio = (it.digitalZoomRatio * delta).coerceIn(1f, maxZ))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top & Bottom Subtle Gradient Scrims for High-Contrast HUD Legibility
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(148.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(ObsidianVoid.copy(alpha = 0.72f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, ObsidianVoid.copy(alpha = 0.85f))
                    )
                )
        )

        // Top Telemetry & Quick Controls Strip (2-tier layout prevents crowding on 360dp screens)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianVoid.copy(alpha = 0.78f))
                    .border(1.dp, GraphiteBorder.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Live Sensor Telemetry + Active Color Science Pill
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val badgeText = when {
                        currentDest == AppDestination.VIDEO -> "${videoSettings.selectedSize.height}p${videoSettings.selectedFps}"
                        currentDest == AppDestination.SLOW_MOTION -> "${videoSettings.selectedHighSpeed?.maxFps ?: 120}fps"
                        else -> proControls.captureFormat.name.replace("_", "+")
                    }
                    Surface(
                        color = AmberFocus.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AmberFocus.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberFocus,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = "ISO ${telemetry.iso} · ${formatShutterNs(telemetry.exposureTimeNs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Right: Primary 4 Quick Hardware Toggles
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (activeCam?.hasFlash == true) {
                        IconButton(
                            onClick = { viewModel.cycleFlash() },
                            modifier = Modifier.testTag("flash_button")
                        ) {
                            Icon(
                                imageVector = when (proControls.flashMode) {
                                    FlashMode.OFF -> Icons.Filled.FlashOff
                                    FlashMode.AUTO -> Icons.Filled.FlashAuto
                                    FlashMode.ON -> Icons.Filled.FlashOn
                                    FlashMode.TORCH -> Icons.Filled.FlashlightOn
                                },
                                contentDescription = strings.flashLabel,
                                tint = if (proControls.flashMode == FlashMode.OFF) TextSecondary else AmberFocus
                            )
                        }
                    }

                    IconButton(
                        onClick = { viewModel.cycleTimer() },
                        modifier = Modifier.testTag("timer_button")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Timer,
                                contentDescription = strings.timerLabel,
                                tint = if (timerSec > 0) AmberFocus else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            if (timerSec > 0) {
                                Text("${timerSec}s", style = MaterialTheme.typography.labelSmall, color = AmberFocus)
                            }
                        }
                    }

                    IconButton(
                        onClick = { showCameraLab = true },
                        modifier = Modifier.testTag("camera_lab_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = strings.cameraLabTitle,
                            tint = AmberFocus
                        )
                    }

                    IconButton(
                        onClick = { showOverlayPanel = !showOverlayPanel },
                        modifier = Modifier.testTag("overlays_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GridOn,
                            contentDescription = strings.quickMenuLabel,
                            tint = if (showOverlayPanel) AmberFocus else TextPrimary
                        )
                    }
                }
            }

            // Tier-2 Compact Scrollable Quick Utility Ribbon (prevents icon crowding on 360dp width)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Active Color Science Quick Badge (opens Camera Lab)
                Surface(
                    color = CarbonSurface.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GraphiteBorder),
                    modifier = Modifier.clickable { showCameraLab = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(profileAccentColor(settings.libPatcher.colorProfile))
                        )
                        Text(
                            text = settings.libPatcher.colorProfile.badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Live Tone Dual Slider Toggle
                val toneActive = showLiveTonePanel || liveShadowBias != 0f || liveHighlightBias != 0f
                Surface(
                    color = if (toneActive) AmberFocus.copy(alpha = 0.18f) else CarbonSurface.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (toneActive) AmberFocus else GraphiteBorder),
                    modifier = Modifier
                        .clickable { showLiveTonePanel = !showLiveTonePanel }
                        .testTag("live_tone_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Contrast,
                            contentDescription = strings.liveToneAdjustLabel,
                            tint = if (toneActive) AmberFocus else TextPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (strings.isArabic) "الإضاءة" else "Tone",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (toneActive) AmberFocus else TextPrimary
                        )
                    }
                }

                // Focus Turbo 100% 1-tap HUD pill
                Surface(
                    color = if (settings.focusPowerMode) AmberFocus.copy(alpha = 0.92f) else CarbonSurface.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (settings.focusPowerMode) AmberFocus else GraphiteBorder),
                    modifier = Modifier
                        .clickable { viewModel.toggleFocusPowerMode() }
                        .testTag("focus_power_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = strings.focusPowerModeLabel,
                            tint = if (settings.focusPowerMode) ObsidianVoid else AmberFocus,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (settings.focusPowerMode) strings.focusPowerActiveBadge else strings.focusPowerModeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (settings.focusPowerMode) ObsidianVoid else TextPrimary
                        )
                    }
                }

                // 1-tap 3x3 Composition Grid quick pill
                val is3x3Active = settings.gridOverlay == GridOverlayType.RULE_OF_THIRDS
                Surface(
                    color = if (is3x3Active) AmberFocus.copy(alpha = 0.20f) else CarbonSurface.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (is3x3Active) AmberFocus else GraphiteBorder
                    ),
                    modifier = Modifier
                        .clickable { viewModel.toggle3x3GridQuick() }
                        .testTag("quick_3x3_grid_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GridOn,
                            contentDescription = strings.gridLabel,
                            tint = if (is3x3Active) AmberFocus else TextPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = strings.grid3x3QuickLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (is3x3Active) FontWeight.Bold else FontWeight.Medium,
                            color = if (is3x3Active) AmberFocus else TextPrimary
                        )
                    }
                }

                // 1-tap EV / Low-Light ISO quick toggle pill
                val evIsoHighlighted = showEvIsoPanel || proControls.manualExposureEnabled || proControls.exposureCompensationSteps != 0
                Surface(
                    color = if (evIsoHighlighted) SapphireTelemetry.copy(alpha = 0.22f) else CarbonSurface.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (evIsoHighlighted) SapphireTelemetry else GraphiteBorder
                    ),
                    modifier = Modifier
                        .clickable { showEvIsoPanel = !showEvIsoPanel }
                        .testTag("quick_ev_iso_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Exposure,
                            contentDescription = strings.exposureIsoTitle,
                            tint = if (evIsoHighlighted) SapphireTelemetry else TextPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (proControls.manualExposureEnabled) "ISO ${proControls.iso}"
                            else "${strings.evLabel}/${strings.isoLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (evIsoHighlighted) FontWeight.Bold else FontWeight.Medium,
                            color = if (evIsoHighlighted) SapphireTelemetry else TextPrimary
                        )
                    }
                }

                // 1-tap Focus Peaking quick pill
                val peakTint = Color(settings.focusPeakingColor.argb)
                Surface(
                    color = if (settings.focusPeakingEnabled) peakTint.copy(alpha = 0.22f) else CarbonSurface.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (settings.focusPeakingEnabled) peakTint else GraphiteBorder
                    ),
                    modifier = Modifier
                        .clickable {
                            viewModel.updateSettings {
                                setOverlays(focusPeaking = !settings.focusPeakingEnabled)
                            }
                        }
                        .testTag("quick_focus_peaking_toggle")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CenterFocusStrong,
                            contentDescription = strings.focusPeaking,
                            tint = if (settings.focusPeakingEnabled) peakTint else TextPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = strings.focusPeaking,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (settings.focusPeakingEnabled) FontWeight.Bold else FontWeight.Medium,
                            color = if (settings.focusPeakingEnabled) peakTint else TextPrimary
                        )
                    }
                }

                // Hardware Diagnostics Shortcut
                Surface(
                    color = CarbonSurface.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GraphiteBorder),
                    modifier = Modifier
                        .clickable { viewModel.navigateTo(AppDestination.DIAGNOSTICS) }
                        .testTag("nav_diagnostics_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MonitorHeart,
                            contentDescription = strings.modeDiagnostics,
                            tint = SapphireTelemetry,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = strings.modeDiagnostics,
                            style = MaterialTheme.typography.labelSmall,
                            color = SapphireTelemetry
                        )
                    }
                }

                // Settings Shortcut
                Surface(
                    color = CarbonSurface.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GraphiteBorder),
                    modifier = Modifier
                        .clickable { viewModel.navigateTo(AppDestination.SETTINGS) }
                        .testTag("nav_settings_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = strings.modeSettings,
                            tint = TextPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = strings.modeSettings,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary
                        )
                    }
                }
            }

            // Expandable Overlays Quick Drawer (Grid, Histogram, Peaking, Zebra, SuperRes)
            AnimatedVisibility(visible = showOverlayPanel) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CarbonSurface.copy(alpha = 0.92f))
                        .border(1.dp, GraphiteBorder, RoundedCornerShape(12.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val gridTitle = if (strings.isArabic) settings.gridOverlay.labelAr else settings.gridOverlay.labelEn
                    ProPillButton(
                        label = "${strings.gridLabel}: $gridTitle",
                        selected = settings.gridOverlay != GridOverlayType.NONE,
                        testTag = "overlay_grid_cycle_pill",
                        onClick = {
                            val next = GridOverlayType.entries[(settings.gridOverlay.ordinal + 1) % GridOverlayType.entries.size]
                            viewModel.updateSettings { setGridOverlay(next) }
                        }
                    )
                    if (settings.gridOverlay != GridOverlayType.NONE) {
                        val opacityLevels = listOf(0.25f, 0.50f, 0.75f, 0.90f)
                        val nextOpacity = opacityLevels.firstOrNull { it > settings.gridOpacity + 0.05f } ?: opacityLevels.first()
                        ProPillButton(
                            label = "${(settings.gridOpacity * 100).toInt()}%",
                            selected = true,
                            activeColor = Color(settings.gridColor.argb),
                            testTag = "overlay_grid_opacity_pill",
                            onClick = {
                                viewModel.updateSettings { setGridConfig(opacity = nextOpacity) }
                            }
                        )
                        val nextThickness = GridLineThickness.entries[
                            (settings.gridThickness.ordinal + 1) % GridLineThickness.entries.size
                        ]
                        ProPillButton(
                            label = if (strings.isArabic) settings.gridThickness.labelAr else settings.gridThickness.labelEn,
                            selected = true,
                            testTag = "overlay_grid_thickness_pill",
                            onClick = {
                                viewModel.updateSettings { setGridConfig(thickness = nextThickness) }
                            }
                        )
                        val nextGridColor = GridLineColor.entries[
                            (settings.gridColor.ordinal + 1) % GridLineColor.entries.size
                        ]
                        ProPillButton(
                            label = if (strings.isArabic) settings.gridColor.labelAr else settings.gridColor.labelEn,
                            selected = settings.gridColor != GridLineColor.WHITE,
                            activeColor = Color(settings.gridColor.argb),
                            testTag = "overlay_grid_color_pill",
                            onClick = {
                                viewModel.updateSettings { setGridConfig(color = nextGridColor) }
                            }
                        )
                        if (settings.gridOverlay == GridOverlayType.RULE_OF_THIRDS) {
                            ProPillButton(
                                label = strings.gridDiagonalsLabel,
                                selected = settings.gridShowDiagonals,
                                activeColor = AmberFocus,
                                testTag = "overlay_grid_diagonals_pill",
                                onClick = {
                                    viewModel.updateSettings { setGridConfig(showDiagonals = !settings.gridShowDiagonals) }
                                }
                            )
                            ProPillButton(
                                label = strings.gridIntersectionDotsLabel,
                                selected = settings.gridShowIntersectionDots,
                                activeColor = AmberFocus,
                                testTag = "overlay_grid_points_pill",
                                onClick = {
                                    viewModel.updateSettings { setGridConfig(showIntersectionDots = !settings.gridShowIntersectionDots) }
                                }
                            )
                        }
                    }
                    ProPillButton(
                        label = strings.histogramLabel,
                        selected = settings.showHistogram,
                        onClick = {
                            viewModel.updateSettings {
                                setOverlays(showHistogram = !settings.showHistogram)
                            }
                        }
                    )
                    ProPillButton(
                        label = strings.rgbHistogram,
                        selected = settings.showRgbHistogram,
                        onClick = {
                            viewModel.updateSettings {
                                setOverlays(showRgbHistogram = !settings.showRgbHistogram)
                            }
                        }
                    )
                    ProPillButton(
                        label = strings.focusPeaking,
                        selected = settings.focusPeakingEnabled,
                        activeColor = Color(settings.focusPeakingColor.argb),
                        testTag = "overlay_focus_peaking_pill",
                        onClick = {
                            viewModel.updateSettings {
                                setOverlays(focusPeaking = !settings.focusPeakingEnabled)
                            }
                        }
                    )
                    if (settings.focusPeakingEnabled) {
                        FocusPeakingColor.entries.forEach { c ->
                            ProPillButton(
                                label = if (strings.isArabic) c.labelAr else c.labelEn,
                                selected = settings.focusPeakingColor == c,
                                activeColor = Color(c.argb),
                                onClick = {
                                    viewModel.updateSettings { setFocusPeakingStyle(color = c) }
                                }
                            )
                        }
                        val nextSens = FocusPeakingSensitivity.entries[
                            (settings.focusPeakingSensitivity.ordinal + 1) % FocusPeakingSensitivity.entries.size
                        ]
                        ProPillButton(
                            label = "${strings.focusPeakingSensitivityLabel}: ${if (strings.isArabic) settings.focusPeakingSensitivity.labelAr else settings.focusPeakingSensitivity.labelEn}",
                            selected = true,
                            activeColor = Color(settings.focusPeakingColor.argb),
                            onClick = {
                                viewModel.updateSettings { setFocusPeakingStyle(sensitivity = nextSens) }
                            }
                        )
                    }
                    ProPillButton(
                        label = strings.zebraLabel,
                        selected = settings.zebraWarningsEnabled,
                        onClick = {
                            viewModel.updateSettings {
                                setOverlays(zebraWarnings = !settings.zebraWarningsEnabled)
                            }
                        }
                    )
                    ProPillButton(
                        label = strings.digitalLevel,
                        selected = settings.showDigitalLevel,
                        onClick = {
                            viewModel.updateSettings {
                                setOverlays(showDigitalLevel = !settings.showDigitalLevel)
                            }
                        }
                    )
                    ProPillButton(
                        label = "Super-Res",
                        selected = settings.superResolutionEnabled,
                        onClick = {
                            viewModel.updateSettings {
                                setSuperResolutionEnabled(!settings.superResolutionEnabled)
                            }
                        }
                    )
                    ProPillButton(
                        label = strings.focusPowerActiveBadge,
                        selected = settings.focusPowerMode,
                        activeColor = AmberFocus,
                        testTag = "focus_power_overlay_pill",
                        onClick = { viewModel.toggleFocusPowerMode() }
                    )
                }
            }

            // Expandable GCam+ Live Dual Sliders (EV, Shadows, Highlights)
            AnimatedVisibility(visible = showLiveTonePanel) {
                val cam = activeCam
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CarbonSurface.copy(alpha = 0.92f))
                        .border(1.dp, GraphiteBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.liveToneAdjustLabel, style = MaterialTheme.typography.labelMedium, color = AmberFocus)
                        IconButton(
                            onClick = {
                                viewModel.setLiveToneBiases(0f, 0f)
                                viewModel.updateProControls { it.copy(exposureCompensationSteps = 0) }
                            }
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = strings.resetEdits, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (cam != null && cam.aeCompensationRange.last > cam.aeCompensationRange.first) {
                        val evVal = proControls.exposureCompensationSteps * cam.aeCompensationStep
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${strings.evLabel} ${if (evVal >= 0) "+" else ""}${"%.1f".format(evVal)}", style = MaterialTheme.typography.labelSmall, color = TextPrimary, modifier = Modifier.width(68.dp))
                            Slider(
                                value = proControls.exposureCompensationSteps.toFloat().coerceIn(cam.aeCompensationRange.first.toFloat(), cam.aeCompensationRange.last.toFloat()),
                                onValueChange = { v -> viewModel.updateProControls { it.copy(manualExposureEnabled = false, exposureCompensationSteps = kotlin.math.round(v).toInt()) } },
                                valueRange = cam.aeCompensationRange.first.toFloat()..cam.aeCompensationRange.last.toFloat(),
                                colors = SliderDefaults.colors(thumbColor = AmberFocus, activeTrackColor = AmberFocus),
                                modifier = Modifier.weight(1f).height(26.dp)
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${strings.shadows} ${"%+.2f".format(liveShadowBias)}", style = MaterialTheme.typography.labelSmall, color = TextPrimary, modifier = Modifier.width(68.dp))
                        Slider(
                            value = liveShadowBias,
                            onValueChange = { v -> viewModel.setLiveToneBiases(v, liveHighlightBias) },
                            valueRange = -0.25f..0.35f,
                            colors = SliderDefaults.colors(thumbColor = SapphireTelemetry, activeTrackColor = SapphireTelemetry),
                            modifier = Modifier.weight(1f).height(26.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${strings.highlights} ${"%+.2f".format(liveHighlightBias)}", style = MaterialTheme.typography.labelSmall, color = TextPrimary, modifier = Modifier.width(68.dp))
                        Slider(
                            value = liveHighlightBias,
                            onValueChange = { v -> viewModel.setLiveToneBiases(liveShadowBias, v) },
                            valueRange = -0.35f..0.25f,
                            colors = SliderDefaults.colors(thumbColor = EmeraldLock, activeTrackColor = EmeraldLock),
                            modifier = Modifier.weight(1f).height(26.dp)
                        )
                    }
                }
            }

            // Expandable Low-Light EV Compensation & ISO Adjustment Drawer (in Camera / HDR / Video)
            AnimatedVisibility(visible = showEvIsoPanel && currentDest != AppDestination.NIGHT && currentDest != AppDestination.PRO) {
                val cam = activeCam
                if (cam != null) {
                    LowLightExposureIsoStrip(
                        cam = cam,
                        proControls = proControls,
                        telemetryIso = telemetry.iso,
                        telemetryExpNs = telemetry.exposureTimeNs,
                        strings = strings,
                        onSetEvSteps = { viewModel.setEvCompensationSteps(it) },
                        onSelectIso = { viewModel.applyIsoAdjustment(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CarbonSurface.copy(alpha = 0.92f))
                            .border(1.dp, SapphireTelemetry.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Secondary HUD Row: Histogram + Warnings + AI Scene Pill + Video Timer/Audio Meter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (settings.showHistogram || currentDest == AppDestination.PRO) {
                        LiveHistogramOverlay(
                            telemetry = telemetry,
                            showRgb = settings.showRgbHistogram,
                            rgbLabel = strings.rgbOverlayLabel,
                            lumaLabel = strings.lumaOverlayLabel,
                            onToggleMode = {
                                viewModel.updateSettings {
                                    setOverlays(showRgbHistogram = !settings.showRgbHistogram)
                                }
                            }
                        )
                    }

                    if (proControls.digitalZoomRatio > 1.02f) {
                        HudBadge(
                            text = "${"%.1f".format(proControls.digitalZoomRatio)}x · ${strings.digitalZoomWarning}",
                            color = AmberFocus
                        )
                    }

                    if (proControls.afLocked) {
                        HudBadge(text = strings.afLockLabel, color = EmeraldLock)
                    }

                    if (settings.superResolutionEnabled && currentDest == AppDestination.CAMERA) {
                        HudBadge(text = "SR 1.5x", color = SapphireTelemetry)
                    }

                    if (health.thermalSeverity.ordinal >= ThermalSeverity.MODERATE.ordinal) {
                        HudBadge(
                            text = if (health.thermalSeverity == ThermalSeverity.MODERATE)
                                strings.thermalWarningModerate else strings.thermalWarningSevere,
                            color = CrimsonRecord
                        )
                    }

                    if (health.isLowBattery) {
                        HudBadge(text = strings.lowBatteryNotice, color = AmberFocus)
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (videoRecState.isRecording) {
                        val secs = (videoRecState.elapsedMs / 1000L).toInt()
                        HudBadge(
                            text = "REC %02d:%02d".format(secs / 60, secs % 60),
                            color = CrimsonRecord
                        )
                        if (videoSettings.recordAudio && currentDest == AppDestination.VIDEO) {
                            AudioLevelMeterBar(level = videoRecState.audioLevelNormalized)
                        }
                    }

                    if (settings.focusPeakingEnabled) {
                        val peakColor = Color(settings.focusPeakingColor.argb)
                        HudBadge(
                            text = "${strings.focusPeaking} · ${strings.focusSharpnessPeakLabel} ${telemetry.focusSharpnessPercent}%",
                            color = peakColor
                        )
                    }

                    if ((settings.aiAssistantEnabled || settings.focusPowerMode) && aiRec != null) {
                        val rec = aiRec!!
                        val sceneLabel = when (rec.scene) {
                            RecognizedScene.PORTRAIT -> strings.scenePortrait
                            RecognizedScene.LANDSCAPE -> strings.sceneLandscape
                            RecognizedScene.FOOD -> strings.sceneFood
                            RecognizedScene.DOCUMENT -> strings.sceneDocument
                            RecognizedScene.PET -> strings.scenePet
                            RecognizedScene.BUILDING -> strings.sceneBuilding
                            RecognizedScene.NIGHT -> strings.sceneNight
                            RecognizedScene.BACKLIT -> strings.sceneBacklit
                            RecognizedScene.OUTDOOR -> strings.sceneOutdoor
                            RecognizedScene.INDOOR -> strings.sceneIndoor
                        }
                        val mlTag = rec.mlKitLabel?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                        val cPct = ((rec.profile.contrastAdjust - 0.06f) * 100f).toInt()
                        val sPct = ((rec.profile.saturationAdjust - 0.04f) * 100f).toInt()
                        val autoSummary = if (settings.aiAutoAdjustEnabled) {
                            val wbStr = if (strings.isArabic) rec.recommendedWb.labelAr else rec.recommendedWb.labelEn
                            "${strings.aiAutoPresetActiveBadge} · $wbStr · C${if (cPct >= 0) "+" else ""}$cPct S${if (sPct >= 0) "+" else ""}$sPct"
                        } else {
                            strings.aiApplySuggestion
                        }
                        val isVideo = currentDest == AppDestination.VIDEO || currentDest == AppDestination.SLOW_MOTION
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(ObsidianVoid.copy(alpha = 0.78f))
                                .border(1.dp, SapphireTelemetry.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                                .clickable(enabled = !isVideo) { viewModel.applyAiRecommendation() }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .testTag("ai_scene_recognition_pill"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = strings.aiAssistantTitle,
                                tint = SapphireTelemetry,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isVideo) "$sceneLabel$mlTag · ${strings.aiVideoBoostBadge}"
                                else "$sceneLabel$mlTag · $autoSummary",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Countdown, Live Action Burst Counter, or Computational Processing Overlay
        if (countdown > 0) {
            Text(
                text = countdown.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = AmberFocus,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(ObsidianVoid.copy(alpha = 0.6f), CircleShape)
                    .padding(24.dp)
            )
        } else if (burstProg != null) {
            val bp = burstProg!!
            val fpsPart = if (bp.achievedFps > 0.5f) " · ${"%.1f".format(bp.achievedFps)} fps" else ""
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianVoid.copy(alpha = 0.88f))
                    .border(1.5.dp, SapphireTelemetry, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("burst_progress_pill"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    color = SapphireTelemetry,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "BURST ${bp.capturedCount}/${bp.targetCount}$fpsPart",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        } else if (procStage != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianVoid.copy(alpha = 0.86f))
                    .border(1.dp, AmberFocus, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    color = AmberFocus,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(20.dp)
                )
                val msg = when (procStage) {
                    "CAPTURING" -> strings.capturingFrames
                    "MERGING_HDR" -> strings.mergingHdr
                    "MERGING_NIGHT" -> strings.mergingNight
                    "SUPER_RES" -> strings.superResReconstructing
                    else -> strings.aligningFrames
                }
                Text(text = msg, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }
        }

        // Subtle non-blocking background processing indicator chip (clickable anytime, never freezes screen)
        if (processingJobs.isNotEmpty()) {
            val activeJob = processingJobs.last()
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 12.dp, top = 112.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianVoid.copy(alpha = 0.84f))
                    .border(1.dp, AmberFocus.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                    .clickable { showProcessingSheet = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("background_processing_pill"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                CircularProgressIndicator(
                    progress = { activeJob.progress },
                    color = AmberFocus,
                    trackColor = GraphiteBorder,
                    strokeWidth = 2.4.dp,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${activeJob.modeTag} · ${(activeJob.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberFocus,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // QR Code Live Result Banner
        qrResult?.let { qr ->
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CarbonSurface.copy(alpha = 0.94f))
                    .border(1.dp, EmeraldLock, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(qr.formatLabel, style = MaterialTheme.typography.labelSmall, color = EmeraldLock)
                    Text(
                        text = qr.rawValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ProPillButton(
                    label = if (qr.isUrl) (if (strings.isArabic) "فتح" else "Open") else (if (strings.isArabic) "نسخ" else "Copy"),
                    selected = true,
                    activeColor = EmeraldLock,
                    onClick = {
                        if (qr.isUrl) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(qr.rawValue)))
                            }
                        } else {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("QR", qr.rawValue))
                        }
                        viewModel.cameraController.clearQrResult()
                    }
                )
                IconButton(onClick = { viewModel.cameraController.clearQrResult() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }
        }

        // Capture Feedback Toast Pill
        feedback?.let { fb ->
            LaunchedEffect(fb) {
                kotlinx.coroutines.delay(2600L)
                viewModel.cameraController.clearFeedback()
            }
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 120.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(ObsidianVoid.copy(alpha = 0.84f))
                    .border(1.dp, if (fb.isWarning) AmberFocus else EmeraldLock, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (fb.isWarning) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = AmberFocus, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = fb.message ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Bottom Control Deck
        Column(
            modifier = Modifier
                .align(if (isLandscape) Alignment.BottomEnd else Alignment.BottomCenter)
                .then(if (isLandscape) Modifier.fillMaxWidth(0.58f) else Modifier.fillMaxWidth())
                .background(ObsidianVoid.copy(alpha = 0.78f))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Contextual Controls: Pro / Video / SlowMo / Photo Tools
            val cam = activeCam
            if (currentDest == AppDestination.PRO && cam != null) {
                ProControlsBar(
                    caps = cam,
                    controls = proControls,
                    strings = strings,
                    onUpdateControls = { viewModel.updateProControls(it) },
                    focusPeakingEnabled = settings.focusPeakingEnabled,
                    focusPeakingColor = settings.focusPeakingColor,
                    focusPeakingSensitivity = settings.focusPeakingSensitivity,
                    focusSharpnessPercent = telemetry.focusSharpnessPercent,
                    liveIso = telemetry.iso,
                    liveExposureNs = telemetry.exposureTimeNs,
                    onSelectIsoPreset = { iso -> viewModel.applyIsoAdjustment(iso) },
                    onToggleFocusPeaking = {
                        viewModel.updateSettings {
                            setOverlays(focusPeaking = !settings.focusPeakingEnabled)
                        }
                    },
                    onSelectPeakingStyle = { col, sens ->
                        viewModel.updateSettings {
                            setFocusPeakingStyle(color = col, sensitivity = sens)
                        }
                    }
                )
            } else if ((currentDest == AppDestination.VIDEO || currentDest == AppDestination.SLOW_MOTION) && cam != null) {
                VideoControlsBar(
                    caps = cam,
                    inventory = inventory,
                    videoSettings = videoSettings,
                    isSlowMotionMode = currentDest == AppDestination.SLOW_MOTION,
                    estMinutesRemaining = viewModel.estimateVideoMinutesRemaining(),
                    strings = strings,
                    onUpdateVideo = { viewModel.updateVideoSettings(it) },
                    aiAssistantEnabled = settings.aiAssistantEnabled,
                    focusPowerMode = settings.focusPowerMode,
                    aiBitrateMultiplier = aiRec?.profile?.videoBitrateMultiplier ?: 1.15f
                )
            } else if (currentDest == AppDestination.NIGHT && cam != null) {
                LowLightExposureIsoStrip(
                    cam = cam,
                    proControls = proControls,
                    telemetryIso = telemetry.iso,
                    telemetryExpNs = telemetry.exposureTimeNs,
                    strings = strings,
                    onSetEvSteps = { viewModel.setEvCompensationSteps(it) },
                    onSelectIso = { viewModel.applyIsoAdjustment(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CarbonSurface.copy(alpha = 0.90f))
                        .border(1.dp, SapphireTelemetry.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            } else if (currentDest == AppDestination.CAMERA) {
                // Quick Photo Tools Strip (Standard, Portrait, Astro, Burst, AEB, Interval, Pano, Doc, QR)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val tools = listOf(
                        PhotoToolMode.STANDARD to strings.modeCamera,
                        PhotoToolMode.PORTRAIT to strings.portraitLabel,
                        PhotoToolMode.ASTRO to strings.astroLabel,
                        PhotoToolMode.BURST to strings.burstLabel,
                        PhotoToolMode.BRACKETING to strings.bracketLabel,
                        PhotoToolMode.INTERVALOMETER to strings.intervalLabel,
                        PhotoToolMode.PANORAMA to strings.panoramaLabel,
                        PhotoToolMode.DOCUMENT to strings.documentLabel,
                        PhotoToolMode.QR_SCANNER to strings.qrLabel
                    )
                    tools.forEach { (t, lbl) ->
                        ProPillButton(
                            label = lbl,
                            selected = photoTool == t,
                            activeColor = SapphireTelemetry,
                            onClick = { viewModel.setPhotoToolMode(t) }
                        )
                    }
                }
            }

            // Live Color Science Strip (Instant 1-tap Pro Look switching with real-time GPU preview)
            if (settings.showLiveColorBar) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CameraColorProfile.entries.forEach { profile ->
                        val selected = settings.libPatcher.colorProfile == profile
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) AmberFocus.copy(alpha = 0.22f) else MachinedSlate.copy(alpha = 0.72f))
                                .border(1.dp, if (selected) AmberFocus else GraphiteBorder, RoundedCornerShape(8.dp))
                                .clickable { viewModel.setColorProfile(profile) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(profileAccentColor(profile))
                            )
                            Text(
                                text = profile.badge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) AmberFocus else TextPrimary
                            )
                        }
                    }
                }
            }

            // Lens Switcher Row (Discovered Real Physical/Usable Lenses Only!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                inventory?.mainRearCamera?.let {
                    ProPillButton(
                        label = strings.lensMain,
                        selected = activeCam?.cameraId == it.cameraId,
                        onClick = { viewModel.switchLens(LensRole.MAIN) }
                    )
                    Spacer(Modifier.width(8.dp))
                }
                inventory?.macroCamera?.let {
                    ProPillButton(
                        label = strings.lensMacro,
                        selected = activeCam?.cameraId == it.cameraId,
                        onClick = { viewModel.switchLens(LensRole.MACRO) }
                    )
                    Spacer(Modifier.width(8.dp))
                }
                inventory?.frontCamera?.let {
                    ProPillButton(
                        label = strings.lensFront,
                        selected = activeCam?.cameraId == it.cameraId,
                        onClick = { viewModel.switchLens(LensRole.FRONT) }
                    )
                }
            }

            // Mode Carousel + Studio/Gallery Quick Access
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val modes = listOf(
                    AppDestination.CAMERA to strings.modeCamera,
                    AppDestination.PRO to strings.modePro,
                    AppDestination.NIGHT to strings.modeNight,
                    AppDestination.HDR to strings.modeHdr,
                    AppDestination.VIDEO to strings.modeVideo,
                    AppDestination.SLOW_MOTION to strings.modeSlowMotion,
                    AppDestination.GALLERY to strings.modeGallery,
                    AppDestination.STUDIO to strings.modeStudio
                )
                modes.forEach { (dest, label) ->
                    val isSelected = currentDest == dest
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) AmberFocus.copy(alpha = 0.16f) else Color.Transparent
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) AmberFocus.copy(alpha = 0.55f) else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                if (dest == AppDestination.STUDIO && galleryItems.isNotEmpty()) {
                                    viewModel.openUriInStudio(galleryItems.first().uri)
                                } else {
                                    viewModel.navigateTo(dest)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("mode_tab_${dest.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) AmberFocus else TextSecondary
                        )
                    }
                }
            }

            // Primary Shutter Deck
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Thumbnail (shows live processing ring + preview & opens processing status when tapped mid-processing)
                val activeBgJob = processingJobs.lastOrNull()
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MachinedSlate)
                        .border(
                            1.5.dp,
                            if (activeBgJob != null) AmberFocus else GraphiteBorder,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            if (processingJobs.isNotEmpty()) {
                                showProcessingSheet = true
                            } else {
                                viewModel.navigateTo(AppDestination.GALLERY)
                            }
                        }
                        .testTag("gallery_thumb_button"),
                    contentAlignment = Alignment.Center
                ) {
                    val latest = galleryItems.firstOrNull()
                    val jobBmp = activeBgJob?.previewBitmap
                    if (jobBmp != null && !jobBmp.isRecycled) {
                        Image(
                            bitmap = jobBmp.asImageBitmap(),
                            contentDescription = strings.processingInProgressTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (latest != null) {
                        AsyncImage(
                            model = latest.uri,
                            contentDescription = strings.modeGallery,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PhotoLibrary,
                            contentDescription = strings.modeGallery,
                            tint = TextSecondary
                        )
                    }

                    if (activeBgJob != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(ObsidianVoid.copy(alpha = 0.48f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { activeBgJob.progress },
                                color = AmberFocus,
                                trackColor = Color.White.copy(alpha = 0.25f),
                                strokeWidth = 3.dp,
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("gallery_thumb_processing_ring")
                            )
                        }
                        if (processingJobs.size > 1) {
                            Text(
                                text = "${processingJobs.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ObsidianVoid,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(AmberFocus)
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // Shutter / Record / Continuous Action Burst / Panorama Step Button
                val isVideoTab = currentDest == AppDestination.VIDEO || currentDest == AppDestination.SLOW_MOTION
                val isProBurstArmed = currentDest == AppDestination.PRO && proControls.burstEnabledInPro
                val btnScale by animateFloatAsState(
                    targetValue = if (isCapturing || videoRecState.isRecording) 0.88f else 1f,
                    label = "shutter_scale"
                )
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .scale(btnScale)
                        .clip(CircleShape)
                        .border(
                            3.5.dp,
                            when {
                                isVideoTab -> CrimsonRecord
                                isProBurstArmed -> SapphireTelemetry
                                else -> Color.White
                            },
                            CircleShape
                        )
                        .padding(6.dp)
                        .clip(if (videoRecState.isRecording) RoundedCornerShape(10.dp) else CircleShape)
                        .background(
                            when {
                                videoRecState.isRecording -> CrimsonRecord
                                isVideoTab -> CrimsonRecord
                                isProBurstArmed -> SapphireTelemetry
                                currentDest == AppDestination.PRO -> AmberFocus
                                else -> Color.White
                            }
                        )
                        .pointerInput(isVideoTab) {
                            if (!isVideoTab) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                                    waitForUpOrCancellation(pass = PointerEventPass.Final)
                                    viewModel.stopActionBurst()
                                }
                            }
                        }
                        .combinedClickable(
                            enabled = !isCapturing,
                            onClick = {
                                if (settings.hapticShutterFeedback) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                viewModel.triggerCaptureOrRecord()
                            },
                            onLongClick = {
                                if (!isVideoTab && panoCount == 0 && intervalProg == null) {
                                    if (settings.hapticShutterFeedback) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    viewModel.startActionBurst()
                                }
                            }
                        )
                        .testTag("shutter_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            color = ObsidianVoid,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(26.dp)
                        )
                    } else if (panoCount > 0) {
                        Text(
                            text = "$panoCount/5",
                            style = MaterialTheme.typography.labelMedium,
                            color = ObsidianVoid
                        )
                    } else if (intervalProg != null) {
                        Text(
                            text = "${intervalProg!!.first}/${intervalProg!!.second}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ObsidianVoid
                        )
                    } else if (isProBurstArmed) {
                        Text(
                            text = "${proControls.burstFrameCount}F",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ObsidianVoid
                        )
                    }
                }

                // Contextual Right Action: Panorama Stitch / Interval Stop / Studio or Flip Camera
                if (panoCount >= 2) {
                    ProPillButton(
                        label = strings.stitchLabel,
                        selected = true,
                        activeColor = EmeraldLock,
                        onClick = { viewModel.finishPanorama() }
                    )
                } else if (intervalProg != null) {
                    ProPillButton(
                        label = strings.stopLabel,
                        selected = true,
                        activeColor = CrimsonRecord,
                        onClick = { viewModel.cameraController.stopIntervalometer() }
                    )
                } else {
                    IconButton(
                        onClick = {
                            val nextRole = if (activeCam?.lensRole == LensRole.FRONT) LensRole.MAIN else LensRole.FRONT
                            viewModel.switchLens(nextRole)
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MachinedSlate)
                            .testTag("flip_camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cameraswitch,
                            contentDescription = "Flip Camera",
                            tint = TextPrimary
                        )
                    }
                }
            }
        }

        if (showCameraLab) {
            CameraLabSheet(
                settings = settings,
                strings = strings,
                onDismiss = { showCameraLab = false },
                onUpdatePatch = { transform -> viewModel.updateLibPatcher(transform) },
                onApplyPreset = { preset -> viewModel.applyLibPreset(preset) },
                onSaveSlot = { slot -> viewModel.saveCustomSlot(slot) },
                onLoadSlot = { slot -> viewModel.loadCustomSlot(slot) }
            )
        }

        if (showProcessingSheet) {
            ProcessingStatusDialog(
                jobs = processingJobs,
                strings = strings,
                onDismiss = { showProcessingSheet = false },
                onOpenGallery = {
                    showProcessingSheet = false
                    viewModel.navigateTo(AppDestination.GALLERY)
                }
            )
        }
    }
}

@Composable
private fun ProcessingStatusDialog(
    jobs: List<BackgroundProcessingJob>,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onOpenGallery: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarbonSurface,
        modifier = Modifier.testTag("processing_status_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (jobs.isNotEmpty()) {
                    CircularProgressIndicator(
                        color = AmberFocus,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = EmeraldLock,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = strings.processingInProgressTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = AmberFocus
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = strings.processingInProgressDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (jobs.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MachinedSlate)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = EmeraldLock)
                        Text(
                            text = if (strings.isArabic) "اكتملت المعالجة وتم الحفظ في المعرض" else "Processing complete — Saved to Gallery",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                } else {
                    jobs.forEach { job ->
                        val stageText = when (job.stage) {
                            BackgroundProcessingStage.ALIGNING -> strings.aligningFrames
                            BackgroundProcessingStage.MERGING_HDR -> strings.mergingHdr
                            BackgroundProcessingStage.MERGING_NIGHT -> strings.mergingNight
                            BackgroundProcessingStage.SUPER_RES -> strings.superResReconstructing
                            BackgroundProcessingStage.ENHANCING -> strings.processingStageEnhancing
                            BackgroundProcessingStage.SAVING -> strings.processingStageSaving
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MachinedSlate)
                                .border(1.dp, GraphiteBorder, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val bmp = job.previewBitmap
                            if (bmp != null && !bmp.isRecycled) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = job.modeTag,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = job.modeTag,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = AmberFocus
                                    )
                                    Text(
                                        text = "${(job.progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextPrimary
                                    )
                                }
                                Text(
                                    text = stageText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                LinearProgressIndicator(
                                    progress = { job.progress },
                                    color = AmberFocus,
                                    trackColor = ObsidianVoid,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AmberFocus, contentColor = ObsidianVoid),
                modifier = Modifier.testTag("processing_dialog_continue_button")
            ) {
                Text(strings.continueShootingLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onOpenGallery,
                modifier = Modifier.testTag("processing_dialog_gallery_button")
            ) {
                Text(strings.openGalleryLabel, color = SapphireTelemetry)
            }
        }
    )
}

@Composable
private fun HudBadge(text: String, color: Color) {
    Surface(
        color = ObsidianVoid.copy(alpha = 0.78f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.75f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
