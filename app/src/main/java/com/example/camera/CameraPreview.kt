package com.example.camera

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Size
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.processing.AiProcessingProfile
import com.example.processing.AiRecommendation
import com.example.processing.CameraColorProfile
import com.example.processing.ColorProfileEngine
import com.example.processing.LibPatcherConfig
import com.example.ui.theme.AmberFocus
import com.example.ui.theme.EmeraldLock
import com.example.ui.theme.SapphireTelemetry
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CameraPreview(
    previewSize: Size,
    sensorOrientation: Int,
    isFrontCamera: Boolean,
    telemetry: LiveTelemetry,
    gridType: GridOverlayType,
    gridConfig: GridOverlayConfig = GridOverlayConfig(type = gridType),
    showDigitalLevel: Boolean,
    peakingBitmap: Bitmap?,
    zebraBitmap: Bitmap?,
    focusPointNormalized: Pair<Float, Float>?,
    isFocusLocked: Boolean,
    zoomRatio: Float,
    libPatcher: LibPatcherConfig = LibPatcherConfig(),
    aiRecommendation: AiRecommendation? = null,
    aiAutoAdjustEnabled: Boolean = false,
    onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    onSurfaceTextureDestroyed: (SurfaceTexture) -> Unit,
    onTapFocus: (Float, Float) -> Unit,
    onLongPressLock: (Float, Float) -> Unit,
    onPinchZoomDelta: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    val effectivePatch = remember(libPatcher, aiRecommendation?.scene, aiRecommendation?.profile, aiAutoAdjustEnabled) {
        if (aiAutoAdjustEnabled && aiRecommendation != null) {
            val p: AiProcessingProfile = aiRecommendation.profile
            libPatcher.copy(
                contrast = (libPatcher.contrast + (p.contrastAdjust - 0.06f) * 0.85f).coerceIn(-0.35f, 0.45f),
                saturation = (libPatcher.saturation + (p.saturationAdjust - 0.04f) * 0.85f).coerceIn(-0.40f, 0.50f),
                vibrance = (libPatcher.vibrance + (p.vibranceBoost - 0.14f) * 0.55f).coerceIn(0f, 0.50f),
                warmth = (libPatcher.warmth + p.warmthAdjust * 0.75f).coerceIn(-0.35f, 0.35f)
            )
        } else libPatcher
    }
    val previewColorMatrix = remember(effectivePatch) {
        ColorProfileEngine.buildLivePreviewColorMatrix(effectivePatch)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("camera_preview_container"),
        contentAlignment = Alignment.Center
    ) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val (fittedW, fittedH) = computeFittedViewfinderSize(viewW, viewH, previewSize)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val fittedWDp = with(density) { fittedW.toDp() }
        val fittedHDp = with(density) { fittedH.toDp() }

        Box(
            modifier = Modifier
                .size(fittedWDp, fittedHDp)
                .testTag("camera_preview_fitted_frame"),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                configureTransform(this@apply, w, h, previewSize)
                                onSurfaceTextureReady(st)
                            }

                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                configureTransform(this@apply, w, h, previewSize)
                            }

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                onSurfaceTextureDestroyed(st)
                                return true
                            }

                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                        textureViewRef = this
                    }
                },
                update = { tv ->
                    if (tv.width > 0 && tv.height > 0) {
                        configureTransform(tv, tv.width, tv.height, previewSize)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val isDefaultNatural = effectivePatch.colorProfile == CameraColorProfile.NATURAL_TRUE &&
                            abs(effectivePatch.warmth) < 0.01f &&
                            abs(effectivePatch.tint) < 0.01f &&
                            abs(effectivePatch.contrast - 0.06f) < 0.02f &&
                            abs(effectivePatch.saturation - 0.04f) < 0.02f
                        if (isDefaultNatural) {
                            tv.setRenderEffect(null)
                        } else {
                            tv.setRenderEffect(
                                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(previewColorMatrix))
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Focus Peaking & Zebra GPU/Canvas overlays (1:1 aligned to uncropped viewfinder frame)
            if (zebraBitmap != null) {
                Image(
                    bitmap = zebraBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (peakingBitmap != null) {
                Image(
                    bitmap = peakingBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Interactive gesture layer (Tap-to-Focus, Long-press AE/AF Lock, Pinch Digital Zoom)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(fittedW, fittedH) {
                        detectTapGestures(
                            onTap = { pos ->
                                if (fittedW > 0f && fittedH > 0f) {
                                    onTapFocus(
                                        (pos.x / fittedW).coerceIn(0f, 1f),
                                        (pos.y / fittedH).coerceIn(0f, 1f)
                                    )
                                }
                            },
                            onLongPress = { pos ->
                                if (fittedW > 0f && fittedH > 0f) {
                                    onLongPressLock(
                                        (pos.x / fittedW).coerceIn(0f, 1f),
                                        (pos.y / fittedH).coerceIn(0f, 1f)
                                    )
                                }
                            }
                        )
                    }
                    .pointerInput(zoomRatio) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (abs(zoom - 1f) > 0.015f) {
                                onPinchZoomDelta(zoom)
                            }
                        }
                    }
            )

            // Composition Grids, Face Detection Boxes & Digital Horizon Level
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Configurable Composition Grids (dual-stroke shadow + crisp line for bright and dark scenes)
                val activeGrid = gridConfig.type
                val lineAlpha = gridConfig.opacity.coerceIn(0.20f, 0.95f)
                val baseColor = Color(gridConfig.color.argb)
                val gridShadow = Color.Black.copy(alpha = (lineAlpha * 0.55f).coerceIn(0.14f, 0.52f))
                val gridColor = baseColor.copy(alpha = lineAlpha)
                val strokeWidth = gridConfig.thickness.strokePx
                fun drawDualLine(start: Offset, end: Offset, strokePx: Float = strokeWidth) {
                    drawLine(gridShadow, start, end, strokePx + 1.6f)
                    drawLine(gridColor, start, end, strokePx)
                }
                when (activeGrid) {
                    GridOverlayType.RULE_OF_THIRDS -> {
                        for ((p0, p1) in compute3x3GridLines(w, h, includeDiagonals = false)) {
                            drawDualLine(Offset(p0.first, p0.second), Offset(p1.first, p1.second))
                        }
                        if (gridConfig.showDiagonals) {
                            val diagAlpha = (lineAlpha * 0.65f).coerceAtLeast(0.15f)
                            drawLine(gridShadow, Offset(0f, 0f), Offset(w, h), strokeWidth + 1.0f)
                            drawLine(baseColor.copy(alpha = diagAlpha), Offset(0f, 0f), Offset(w, h), (strokeWidth * 0.8f).coerceAtLeast(1f))
                            drawLine(gridShadow, Offset(w, 0f), Offset(0f, h), strokeWidth + 1.0f)
                            drawLine(baseColor.copy(alpha = diagAlpha), Offset(w, 0f), Offset(0f, h), (strokeWidth * 0.8f).coerceAtLeast(1f))
                        }
                        if (gridConfig.showIntersectionDots) {
                            val dotRadius = (strokeWidth * 1.9f).coerceIn(2.8f, 5.5f)
                            for ((ix, iy) in compute3x3IntersectionPoints(w, h)) {
                                drawCircle(gridShadow, radius = dotRadius + 1.2f, center = Offset(ix, iy))
                                drawCircle(AmberFocus.copy(alpha = (lineAlpha + 0.25f).coerceAtMost(0.95f)), radius = dotRadius, center = Offset(ix, iy))
                            }
                            // Subtle center alignment crosshair tick
                            val cx = w / 2f
                            val cy = h / 2f
                            drawDualLine(Offset(cx - 10f, cy), Offset(cx + 10f, cy), (strokeWidth * 0.9f).coerceAtLeast(1.2f))
                            drawDualLine(Offset(cx, cy - 10f), Offset(cx, cy + 10f), (strokeWidth * 0.9f).coerceAtLeast(1.2f))
                        }
                    }
                    GridOverlayType.GOLDEN_RATIO -> {
                        val p1 = 0.381966f
                        val p2 = 0.618034f
                        drawDualLine(Offset(w * p1, 0f), Offset(w * p1, h))
                        drawDualLine(Offset(w * p2, 0f), Offset(w * p2, h))
                        drawDualLine(Offset(0f, h * p1), Offset(w, h * p1))
                        drawDualLine(Offset(0f, h * p2), Offset(w, h * p2))
                        for (px in listOf(p1, p2)) {
                            for (py in listOf(p1, p2)) {
                                drawCircle(AmberFocus.copy(alpha = (lineAlpha + 0.22f).coerceAtMost(0.95f)), radius = 3.5f, center = Offset(w * px, h * py))
                            }
                        }
                    }
                    GridOverlayType.SQUARE -> {
                        val sq = minOf(w, h)
                        val top = (h - sq) / 2f
                        val left = (w - sq) / 2f
                        drawRect(
                            color = gridShadow,
                            topLeft = Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(sq, sq),
                            style = Stroke(width = strokeWidth + 1.6f)
                        )
                        drawRect(
                            color = baseColor.copy(alpha = (lineAlpha + 0.15f).coerceAtMost(0.95f)),
                            topLeft = Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(sq, sq),
                            style = Stroke(width = strokeWidth)
                        )
                    }
                    GridOverlayType.CROSSHAIR -> {
                        val cx = w / 2f
                        val cy = h / 2f
                        val outer = 34f
                        val gap = 8f
                        drawDualLine(Offset(cx - outer, cy), Offset(cx - gap, cy), strokeWidth.coerceAtLeast(1.8f))
                        drawDualLine(Offset(cx + gap, cy), Offset(cx + outer, cy), strokeWidth.coerceAtLeast(1.8f))
                        drawDualLine(Offset(cx, cy - outer), Offset(cx, cy - gap), strokeWidth.coerceAtLeast(1.8f))
                        drawDualLine(Offset(cx, cy + gap), Offset(cx, cy + outer), strokeWidth.coerceAtLeast(1.8f))
                        drawCircle(AmberFocus.copy(alpha = 0.88f), radius = 3.0f, center = Offset(cx, cy))
                    }
                    GridOverlayType.NONE -> Unit
                }

                // Face Detection Rectangles
                for (f in telemetry.faceBoundsNormalized) {
                    val rectLeft = (f.left * w).coerceIn(0f, w)
                    val rectTop = (f.top * h).coerceIn(0f, h)
                    val rectW = ((f.right - f.left) * w).coerceAtLeast(24f)
                    val rectH = ((f.bottom - f.top) * h).coerceAtLeast(24f)
                    drawRect(
                        color = SapphireTelemetry.copy(alpha = 0.8f),
                        topLeft = Offset(rectLeft, rectTop),
                        size = androidx.compose.ui.geometry.Size(rectW, rectH),
                        style = Stroke(width = 2.5f)
                    )
                }

                // Digital Horizon Level
                if (showDigitalLevel) {
                    val cx = w / 2f
                    val cy = h / 2f
                    val levelColor = if (telemetry.isLevel) EmeraldLock else Color.White.copy(alpha = 0.65f)
                    // Reference ticks
                    drawLine(levelColor.copy(alpha = 0.45f), Offset(cx - 72f, cy), Offset(cx - 48f, cy), 2f)
                    drawLine(levelColor.copy(alpha = 0.45f), Offset(cx + 48f, cy), Offset(cx + 72f, cy), 2f)
                    rotate(degrees = -telemetry.rollDegrees, pivot = Offset(cx, cy)) {
                        drawLine(levelColor, Offset(cx - 42f, cy), Offset(cx + 42f, cy), 3f)
                    }
                }
            }

            // Tap-to-Focus Reticle
            AnimatedVisibility(
                visible = focusPointNormalized != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                focusPointNormalized?.let { (nx, ny) ->
                    val ringColor = if (isFocusLocked) EmeraldLock else AmberFocus
                    Canvas(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    ((nx - 0.5f) * fittedW).roundToInt(),
                                    ((ny - 0.5f) * fittedH).roundToInt()
                                )
                            }
                            .size(64.dp)
                    ) {
                        drawCircle(
                            color = ringColor,
                            radius = size.minDimension / 2f,
                            style = Stroke(width = 3f)
                        )
                        drawCircle(
                            color = ringColor,
                            radius = 4f
                        )
                    }
                }
            }
        }
    }
}

/**
 * Computes the uncropped 1x aspect-fitted container size inside [containerW] x [containerH]
 * matching the camera buffer aspect ratio so neither photo (4:3) nor video (16:9) is center-cropped to 20:9.
 */
fun computeFittedViewfinderSize(
    containerW: Float,
    containerH: Float,
    previewSize: Size
): Pair<Float, Float> {
    if (containerW <= 0f || containerH <= 0f) return containerW to containerH
    val shortSide = minOf(previewSize.width, previewSize.height).toFloat().coerceAtLeast(1f)
    val longSide = maxOf(previewSize.width, previewSize.height).toFloat().coerceAtLeast(1f)
    val isLandscape = containerW > containerH
    val bufW = if (isLandscape) longSide else shortSide
    val bufH = if (isLandscape) shortSide else longSide
    val scale = minOf(containerW / bufW, containerH / bufH)
    return (bufW * scale) to (bufH * scale)
}

/**
 * Pure geometry helper computing 3x3 Rule-of-Thirds lines (and optional diagonals) for [width] x [height].
 */
fun compute3x3GridLines(
    width: Float,
    height: Float,
    includeDiagonals: Boolean = false
): List<Pair<Pair<Float, Float>, Pair<Float, Float>>> {
    if (width <= 0f || height <= 0f) return emptyList()
    val lines = mutableListOf(
        (width / 3f to 0f) to (width / 3f to height),
        (2f * width / 3f to 0f) to (2f * width / 3f to height),
        (0f to height / 3f) to (width to height / 3f),
        (0f to 2f * height / 3f) to (width to 2f * height / 3f)
    )
    if (includeDiagonals) {
        lines.add((0f to 0f) to (width to height))
        lines.add((width to 0f) to (0f to height))
    }
    return lines
}

/**
 * Computes the four 3x3 rule-of-thirds power-point intersections for [width] x [height].
 */
fun compute3x3IntersectionPoints(width: Float, height: Float): List<Pair<Float, Float>> {
    if (width <= 0f || height <= 0f) return emptyList()
    return listOf(
        width / 3f to height / 3f,
        2f * width / 3f to height / 3f,
        width / 3f to 2f * height / 3f,
        2f * width / 3f to 2f * height / 3f
    )
}

private fun configureTransform(tv: TextureView, viewWidth: Int, viewHeight: Int, previewSize: Size) {
    val matrix = Matrix()
    val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val shortSide = minOf(previewSize.width, previewSize.height).toFloat().coerceAtLeast(1f)
    val longSide = maxOf(previewSize.width, previewSize.height).toFloat().coerceAtLeast(1f)
    val isLandscape = viewWidth > viewHeight
    val bufW = if (isLandscape) longSide else shortSide
    val bufH = if (isLandscape) shortSide else longSide
    val scaleX = viewWidth / bufW
    val scaleY = viewHeight / bufH
    val scale = minOf(scaleX, scaleY)
    val scaledW = bufW * scale
    val scaledH = bufH * scale
    val sx = scaledW / viewWidth.coerceAtLeast(1)
    val sy = scaledH / viewHeight.coerceAtLeast(1)
    matrix.setScale(sx, sy, viewRect.centerX(), viewRect.centerY())
    tv.setTransform(matrix)
}
