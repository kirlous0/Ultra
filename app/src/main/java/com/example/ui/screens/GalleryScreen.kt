package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.camera.AppDestination
import com.example.camera.BackgroundProcessingJob
import com.example.camera.BackgroundProcessingStage
import com.example.system.AppStrings
import com.example.system.ExifMetadata
import com.example.system.GalleryItem
import com.example.ui.MainViewModel
import androidx.compose.foundation.Image
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import com.example.ui.theme.AmberFocus
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CrimsonRecord
import com.example.ui.theme.GraphiteBorder
import com.example.ui.theme.MachinedSlate
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.SapphireTelemetry
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: MainViewModel,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val items by viewModel.galleryItems.collectAsStateWithLifecycle()
    val processingJobs by viewModel.processingJobs.collectAsStateWithLifecycle()
    val selectedItem by viewModel.selectedGalleryItem.collectAsStateWithLifecycle()
    val selectedExif by viewModel.selectedExif.collectAsStateWithLifecycle()
    var showExifSheet by remember { mutableStateOf(true) }
    var itemPendingDelete by remember { mutableStateOf<GalleryItem?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.openUriInStudio(it) }
    }

    BackHandler {
        if (itemPendingDelete != null) {
            itemPendingDelete = null
        } else if (selectedItem != null) {
            viewModel.selectGalleryItem(null)
            showExifSheet = true
        } else {
            viewModel.navigateTo(AppDestination.CAMERA)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ObsidianVoid,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedItem?.displayName ?: strings.modeGallery,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectedItem != null) {
                                viewModel.selectGalleryItem(null)
                                showExifSheet = true
                            } else {
                                viewModel.navigateTo(AppDestination.CAMERA)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    if (selectedItem == null) {
                        IconButton(
                            onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("import_photo_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddPhotoAlternate,
                                contentDescription = strings.importFromPhotos,
                                tint = AmberFocus
                            )
                        }
                    } else {
                        val cur = selectedItem!!
                        val editableUri = if (cur.isRawDng) cur.pairedUri else if (!cur.isVideo) cur.uri else null
                        if (editableUri != null) {
                            IconButton(
                                onClick = { viewModel.openUriInStudio(editableUri) },
                                modifier = Modifier.testTag("open_in_studio_button")
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = strings.openInStudio, tint = AmberFocus)
                            }
                        }
                        IconButton(onClick = { showExifSheet = !showExifSheet }) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = strings.exifInspector,
                                tint = if (showExifSheet) AmberFocus else SapphireTelemetry
                            )
                        }
                        IconButton(onClick = {
                            runCatching {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = cur.mimeType
                                    putExtra(Intent.EXTRA_STREAM, cur.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, cur.displayName))
                            }
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share", tint = TextPrimary)
                        }
                        IconButton(onClick = { itemPendingDelete = cur }) {
                            Icon(Icons.Filled.Delete, contentDescription = strings.deleteActionLabel, tint = CrimsonRecord)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CarbonSurface)
            )
        }
    ) { padding ->
        val cur = selectedItem
        if (cur != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(CarbonSurface),
                    contentAlignment = Alignment.Center
                ) {
                    if (cur.isRawDng) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.PhotoLibrary,
                                contentDescription = null,
                                tint = AmberFocus,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("RAW DNG Sensor Data", style = MaterialTheme.typography.titleMedium, color = AmberFocus)
                            cur.pairedUri?.let { paired ->
                                Spacer(Modifier.height(8.dp))
                                AsyncImage(
                                    model = paired,
                                    contentDescription = cur.displayName,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    } else {
                        AsyncImage(
                            model = cur.uri,
                            contentDescription = cur.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (cur.isVideo) {
                    Button(
                        onClick = {
                            runCatching {
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(cur.uri, cur.mimeType)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(viewIntent, strings.playVideoLabel))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SapphireTelemetry, contentColor = ObsidianVoid),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PlayCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.playVideoLabel)
                    }
                } else {
                    val studioUri = if (cur.isRawDng) cur.pairedUri else cur.uri
                    if (studioUri != null) {
                        Button(
                            onClick = { viewModel.openUriInStudio(studioUri) },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberFocus, contentColor = ObsidianVoid),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.openInStudio)
                        }
                    }
                }

                AnimatedVisibility(visible = showExifSheet) {
                    selectedExif?.let { exif ->
                        ExifInspectorCard(exif = exif, strings = strings)
                    }
                }
            }
        } else if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp)
            ) {
                if (processingJobs.isNotEmpty()) {
                    GalleryProcessingBanner(jobs = processingJobs, strings = strings)
                    Spacer(Modifier.height(12.dp))
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhotoLibrary,
                            contentDescription = null,
                            tint = AmberFocus,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(strings.emptyGalleryTitle, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                        Text(strings.emptyGallerySubtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Button(
                            onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberFocus, contentColor = ObsidianVoid)
                        ) {
                            Text(strings.importFromPhotos)
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (processingJobs.isNotEmpty()) {
                    Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        GalleryProcessingBanner(jobs = processingJobs, strings = strings)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 10.dp,
                        bottom = 16.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.uri.toString() }) { item ->
                        GalleryGridCell(
                            item = item,
                            onClick = {
                                showExifSheet = true
                                viewModel.selectGalleryItem(item)
                            }
                        )
                    }
                }
            }
        }
    }

    itemPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { itemPendingDelete = null },
            containerColor = CarbonSurface,
            title = { Text(strings.deleteConfirm, color = TextPrimary) },
            text = { Text(pending.displayName, style = MaterialTheme.typography.bodyMedium, color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteGalleryItem(pending)
                        itemPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonRecord, contentColor = TextPrimary)
                ) {
                    Text(strings.deleteActionLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemPendingDelete = null }) {
                    Text(strings.cancelLabel, color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun GalleryGridCell(item: GalleryItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MachinedSlate)
            .border(1.dp, GraphiteBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = item.pairedUri ?: item.uri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (item.isVideo) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = "Video",
                tint = TextPrimary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
            )
        }
        val badge = when {
            item.isRawDng && item.pairedUri != null -> "RAW+JPG"
            item.isRawDng -> "DNG"
            item.isVideo -> "MP4"
            else -> null
        }
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = ObsidianVoid,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(AmberFocus)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun ExifInspectorCard(exif: ExifMetadata, strings: AppStrings) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GraphiteBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(strings.exifInspector, style = MaterialTheme.typography.titleMedium, color = AmberFocus)
            ExifRow("Device", exif.cameraMakeModel)
            if (exif.lensModel != "—") ExifRow("Lens", exif.lensModel)
            ExifRow("Resolution", exif.resolution)
            ExifRow("Size", exif.fileSizeText)
            ExifRow("ISO", exif.iso)
            ExifRow("Shutter", exif.shutterSpeed)
            ExifRow("Aperture", exif.aperture)
            ExifRow("Focal Length", exif.focalLength)
            if (exif.exposureMode != "—") ExifRow("Exposure Mode", exif.exposureMode)
            if (exif.exposureBias != "—") ExifRow("Exposure Bias", exif.exposureBias)
            if (exif.focusDistance != "—") ExifRow("Focus Distance", exif.focusDistance)
            ExifRow("White Balance", exif.whiteBalance)
            if (exif.sceneProfile != "—") ExifRow("Capture Profile", exif.sceneProfile)
            ExifRow("Date", exif.dateTaken)
            ExifRow("GPS", exif.gpsLocation)
            ExifRow("Software", exif.software)
        }
    }
}

@Composable
private fun ExifRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.labelMedium, color = TextPrimary)
    }
}

@Composable
private fun GalleryProcessingBanner(
    jobs: List<BackgroundProcessingJob>,
    strings: AppStrings
) {
    val job = jobs.lastOrNull() ?: return
    val stageText = when (job.stage) {
        BackgroundProcessingStage.ALIGNING -> strings.aligningFrames
        BackgroundProcessingStage.MERGING_HDR -> strings.mergingHdr
        BackgroundProcessingStage.MERGING_NIGHT -> strings.mergingNight
        BackgroundProcessingStage.SUPER_RES -> strings.superResReconstructing
        BackgroundProcessingStage.ENHANCING -> strings.processingStageEnhancing
        BackgroundProcessingStage.SAVING -> strings.processingStageSaving
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AmberFocus.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gallery_processing_banner")
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
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
                        .size(44.dp)
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
                        text = "${job.modeTag} · ${strings.processingInProgressTitle}",
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
                    maxLines = 1
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
