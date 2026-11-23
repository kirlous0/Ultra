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

        // Focus Peaking & Zebra GPU/Canvas overlays
        if (zebraBitmap != null) {
            Image(
                bitmap = zebraBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (peakingBitmap != null) {
            Image(
                bitmap = peakingBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Interactive gesture layer (Tap-to-Focus, Long-press AE/AF Lock, Pinch Digital Zoom)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { pos ->
                            if (viewW > 0f && viewH > 0f) {
                                onTapFocus((pos.x / viewW).coerceIn(0f, 1f), (pos.y / viewH).coerceIn(0f, 1f))
                            }
                        },
                        onLongPress = { pos ->
                            if (viewW > 0f && viewH > 0f) {
                                onLongPressLock((pos.x / viewW).coerceIn(0f, 1f), (pos.y / viewH).coerceIn(0f, 1f))
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

            // Grid Overlays
            val gridColor = Color.White.copy(alpha = 0.28f)
            when (gridType) {
                GridOverlayType.RULE_OF_THIRDS -> {
                    drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), 1.2f)
                    drawLine(gridColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), 1.2f)
                    drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), 1.2f)
                    drawLine(gridColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), 1.2f)
                }
                GridOverlayType.GOLDEN_RATIO -> {
                    val p1 = 0.382f
                    val p2 = 0.618f
                    drawLine(gridColor, Offset(w * p1, 0f), Offset(w * p1, h), 1.2f)
                    drawLine(gridColor, Offset(w * p2, 0f), Offset(w * p2, h), 1.2f)
                    drawLine(gridColor, Offset(0f, h * p1), Offset(w, h * p1), 1.2f)
                    drawLine(gridColor, Offset(0f, h * p2), Offset(w, h * p2), 1.2f)
                }
                GridOverlayType.SQUARE -> {
                    val sq = minOf(w, h)
                    val top = (h - sq) / 2f
                    val left = (w - sq) / 2f
                    drawRect(
                        color = AmberFocus.copy(alpha = 0.5f),
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(sq, sq),
                        style = Stroke(width = 2f)
                    )
                }
                GridOverlayType.CROSSHAIR -> {
                    val cx = w / 2f
                    val cy = h / 2f
                    drawLine(gridColor, Offset(cx - 28f, cy), Offset(cx + 28f, cy), 2f)
                    drawLine(gridColor, Offset(cx, cy - 28f), Offset(cx, cy + 28f), 2f)
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
                                ((nx - 0.5f) * viewW).roundToInt(),
                                ((ny - 0.5f) * viewH).roundToInt()
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

private fun configureTransform(tv: TextureView, viewWidth: Int, viewHeight: Int, previewSize: Size) {
    val matrix = Matrix()
    val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    // Sensor buffer in portrait is height x width aspect
    val bufW = previewSize.height.toFloat().coerceAtLeast(1f)
    val bufH = previewSize.width.toFloat().coerceAtLeast(1f)
    val scaleX = viewWidth / bufW
    val scaleY = viewHeight / bufH
    val scale = maxOf(scaleX, scaleY)
    val scaledW = bufW * scale
    val scaledH = bufH * scale
    val sx = scaledW / viewWidth
    val sy = scaledH / viewHeight
    matrix.setScale(sx, sy, viewRect.centerX(), viewRect.centerY())
    tv.setTransform(matrix)
}
