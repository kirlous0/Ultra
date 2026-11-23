package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.AppDestination
import com.example.processing.CropAspectOption
import com.example.system.AppStrings
import com.example.ui.MainViewModel
import com.example.ui.components.ProPillButton
import com.example.ui.theme.AmberFocus
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.GraphiteBorder
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SapphireTelemetry
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

private enum class StudioCategory {
    LIGHT,
    COLOR,
    DETAIL,
    GEOMETRY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    viewModel: MainViewModel,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    val origBmp by viewModel.studioOriginalBitmap.collectAsStateWithLifecycle()
    val renderedBmp by viewModel.studioRenderedBitmap.collectAsStateWithLifecycle()
    val params by viewModel.studioParams.collectAsStateWithLifecycle()
    val busy by viewModel.studioBusy.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    var activeCategory by remember { mutableStateOf(StudioCategory.LIGHT) }
    var compareMode by remember { mutableStateOf(false) }
    var splitFraction by remember { mutableFloatStateOf(0.5f) }
    var showExportDialog by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.openUriInStudio(it) }
    }

    BackHandler {
        viewModel.navigateTo(AppDestination.CAMERA)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ObsidianVoid,
        topBar = {
            TopAppBar(
                title = { Text(strings.modeStudio, style = MaterialTheme.typography.titleMedium, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(AppDestination.CAMERA) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = strings.importFromPhotos, tint = TextPrimary)
                    }
                    IconButton(onClick = { compareMode = !compareMode }) {
                        Icon(
                            Icons.Filled.Compare,
                            contentDescription = strings.beforeAfterCompare,
                            tint = if (compareMode) AmberFocus else TextPrimary
                        )
                    }
                    IconButton(onClick = { viewModel.resetStudioEdits() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = strings.resetEdits, tint = TextSecondary)
                    }
                    IconButton(
                        onClick = { showExportDialog = true },
                        enabled = origBmp != null,
                        modifier = Modifier.testTag("studio_export_button")
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = strings.exportImage, tint = AmberFocus)
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
        ) {
            // Live GPU-graded Preview Area with Before/After Split Comparison
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(ObsidianVoid)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val displayBmp = renderedBmp ?: origBmp
                if (displayBmp == null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(strings.emptyGallerySubtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Button(
                            onClick = {
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberFocus, contentColor = ObsidianVoid)
                        ) {
                            Text(strings.importFromPhotos)
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (compareMode) {
                                    Modifier.pointerInput(Unit) {
                                        detectHorizontalDragGestures { _, dragAmount ->
                                            splitFraction = (splitFraction + dragAmount / widthPx).coerceIn(0.05f, 0.95f)
                                        }
                                    }
                                } else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = displayBmp.asImageBitmap(),
                            contentDescription = "Edited preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (compareMode && origBmp != null) {
                            Image(
                                bitmap = origBmp!!.asImageBitmap(),
                                contentDescription = "Original preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .drawWithContent {
                                        clipRect(right = size.width * splitFraction) {
                                            this@drawWithContent.drawContent()
                                        }
                                    }
                            )
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset((splitFraction * widthPx).roundToInt(), 0) }
                                    .width(2.5.dp)
                                    .fillMaxHeight()
                                    .background(AmberFocus)
                                    .align(Alignment.CenterStart)
                            )
                        }
                        if (busy) {
                            CircularProgressIndicator(color = AmberFocus)
                        }
                    }
                }
            }

            statusBanner?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = SapphireTelemetry,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )
            }

            // Studio Controls Panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CarbonSurface)
                    .border(1.dp, GraphiteBorder)
                    .navigationBarsPadding()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProPillButton(strings.studioLightTab, activeCategory == StudioCategory.LIGHT) {
                        activeCategory = StudioCategory.LIGHT
                    }
                    ProPillButton(strings.studioColorTab, activeCategory == StudioCategory.COLOR) {
                        activeCategory = StudioCategory.COLOR
                    }
                    ProPillButton(strings.studioDetailTab, activeCategory == StudioCategory.DETAIL) {
                        activeCategory = StudioCategory.DETAIL
                    }
                    ProPillButton(strings.cropRotate, activeCategory == StudioCategory.GEOMETRY) {
                        activeCategory = StudioCategory.GEOMETRY
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    when (activeCategory) {
                        StudioCategory.LIGHT -> {
                            StudioSlider(strings.exposure, params.exposureEv, -2f..2f) { v ->
                                viewModel.updateStudioParams { it.copy(exposureEv = v) }
                            }
                            StudioSlider(strings.contrast, params.contrast, -1f..1f) { v ->
                                viewModel.updateStudioParams { it.copy(contrast = v) }
                            }
                            StudioSlider(strings.highlights, params.highlights, -1f..1f) { v ->
                                viewModel.updateStudioParams { it.copy(highlights = v) }
                            }
                            StudioSlider(strings.shadows, params.shadows, -1f..1f) { v ->
                                viewModel.updateStudioParams { it.copy(shadows = v) }
                            }
                            StudioSlider(strings.whites, params.whites, -1f..1f) { v ->
                                viewModel.updateStudioParams { it.copy(whites = v) }
                            }
                            StudioSlider(strings.blacks, params.blacks, -1f..1f) { v ->
                                viewModel.updateStudioParams { it.copy(blacks = v) }
                            }
                        }
                        StudioCategory.COLOR -> {
                            Text(
                                text = strings.colorProfileLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = AmberFocus
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                com.example.processing.CameraColorProfile.entries.forEach { profile ->
                                    ProPillButton(
                                        label = if (strings.isArabic) profile.labelAr else profile.labelEn,
                                        selected = params.colorProfile == profile,
                                        activeColor = AmberFocus,
                                        onClick = { viewModel.updateStudioParams { it.copy(colorProfile = profile) } }
                                    )
                                }
                            }
                            StudioSlider(strings.saturation, params.saturation, -1f..1f) { v ->
                                viewModel.updateStudioParams { it.copy(saturation = v) }
                            }
                            StudioSlider(strings.vibrance, params.vibrance, -1f..1f) { v ->
                                viewModel.updateStudioParams { it.copy(vibrance = v) }
                            }
                            StudioSlider(strings.temperature, params.temperature, -1f..1f) { v ->
                                viewModel.updateStudioParams { it.copy(temperature = v) }
                            }
                            StudioSlider(strings.tint, params.tint, -1f..1f) { v ->
                                viewModel.updateStudioParams { it.copy(tint = v) }
                            }
                        }
                        StudioCategory.DETAIL -> {
                            StudioSlider(strings.sharpness, params.sharpness, 0f..1f) { v ->
                                viewModel.updateStudioParams { it.copy(sharpness = v) }
                            }
                            StudioSlider(strings.noiseReduction, params.noiseReduction, 0f..1f) { v ->
                                viewModel.updateStudioParams { it.copy(noiseReduction = v) }
                            }
                            StudioSlider(strings.detailEnhance, params.detailEnhance, 0f..1f) { v ->
                                viewModel.updateStudioParams { it.copy(detailEnhance = v) }
                            }
                        }
                        StudioCategory.GEOMETRY -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProPillButton(strings.rotate90Label, selected = false) {
                                    viewModel.updateStudioParams {
                                        it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360)
                                    }
                                }
                                CropAspectOption.entries.forEach { ratio ->
                                    ProPillButton(
                                        label = if (strings.isArabic) ratio.labelAr else ratio.labelEn,
                                        selected = params.cropRatio == ratio,
                                        onClick = { viewModel.updateStudioParams { it.copy(cropRatio = ratio) } }
                                    )
                                }
                            }
                            StudioSlider("${strings.perspective} V", params.keystoneVertical, -0.25f..0.25f) { v ->
                                viewModel.updateStudioParams { it.copy(keystoneVertical = v) }
                            }
                            StudioSlider("${strings.perspective} H", params.keystoneHorizontal, -0.25f..0.25f) { v ->
                                viewModel.updateStudioParams { it.copy(keystoneHorizontal = v) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        var exportPng by remember { mutableStateOf(false) }
        var quality by remember { mutableIntStateOf(95) }
        var scale by remember { mutableFloatStateOf(1.0f) }

        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            containerColor = CarbonSurface,
            title = { Text(strings.exportImage, color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProPillButton("JPEG", !exportPng) { exportPng = false }
                        ProPillButton("PNG", exportPng) { exportPng = true }
                    }
                    if (!exportPng) {
                        Text("${strings.jpegQualityLabel}: $quality%", color = TextSecondary)
                        Slider(
                            value = quality.toFloat(),
                            onValueChange = { quality = it.roundToInt() },
                            valueRange = 70f..100f
                        )
                    }
                    Text(strings.resolutionLabel, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProPillButton("100%", scale == 1f) { scale = 1f }
                        ProPillButton("75%", scale == 0.75f) { scale = 0.75f }
                        ProPillButton("50%", scale == 0.5f) { scale = 0.5f }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportDialog = false
                        viewModel.exportStudioImage(exportPng, quality, scale)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberFocus, contentColor = ObsidianVoid)
                ) {
                    Text(strings.exportImage)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(strings.cancelLabel, color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun StudioSlider(
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
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text("%.2f".format(value), style = MaterialTheme.typography.labelMedium, color = AmberFocus)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = AmberFocus, activeTrackColor = AmberFocus)
        )
    }
}
