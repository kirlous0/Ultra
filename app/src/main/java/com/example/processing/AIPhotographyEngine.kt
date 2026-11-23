package com.example.processing

import android.hardware.camera2.CameraMetadata
import androidx.compose.runtime.Immutable
import com.example.camera.AppDestination
import com.example.camera.WhiteBalancePreset

@Immutable
enum class RecognizedScene {
    PORTRAIT,
    LANDSCAPE,
    FOOD,
    DOCUMENT,
    PET,
    BUILDING,
    NIGHT,
    BACKLIT,
    OUTDOOR,
    INDOOR
}

@Immutable
data class AiProcessingProfile(
    val sharpnessAmount: Float = 0.34f,
    val clarityAmount: Float = 0.22f,
    val chromaDenoiseStrength: Float = 0.30f,
    val vibranceBoost: Float = 0.14f,
    val shadowLift: Float = 0.08f,
    val highlightCompress: Float = 0.10f,
    val ispNrMode: Int = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
    val ispEdgeMode: Int = CameraMetadata.EDGE_MODE_HIGH_QUALITY,
    val videoBitrateMultiplier: Float = 1.15f,
    val recommendedVideoFps: Int = 30,
    val videoStabilizePriority: Boolean = true,
    val contrastAdjust: Float = 0.06f,
    val saturationAdjust: Float = 0.04f,
    val warmthAdjust: Float = 0.0f
)

@Immutable
data class AiRecommendation(
    val scene: RecognizedScene,
    val confidence: Float,
    val recommendedIso: Int,
    val recommendedShutterNs: Long,
    val recommendedEvSteps: Int,
    val recommendedWb: WhiteBalancePreset,
    val recommendHdr: Boolean,
    val recommendedMode: AppDestination,
    val profile: AiProcessingProfile = AiProcessingProfile(),
    val mlKitLabel: String? = null,
    val mlKitConfidence: Float = 0f
)

/**
 * Hybrid AI-driven Scene Recognition Engine fusing:
 * 1. Google ML Kit Image Labeling semantic classification labels
 * 2. Hardware Camera2 AE / AF / Face Detection telemetry
 * 3. Real-time YUV spatial, chromatic & histogram dynamic-range analysis
 *
 * Automatically computes calibrated White Balance, Contrast, Saturation,
 * and ISP presets per recognized content type.
 */
class AIPhotographyEngine {

    fun classifyScene(
        analysis: FrameAnalysisResult,
        currentIso: Int,
        currentExposureNs: Long,
        faceCount: Int,
        focusDistanceDiopters: Float,
        isoRange: IntRange?,
        exposureRangeNs: LongRange?,
        aeCompRange: IntRange,
        fullPower: Boolean = false,
        mlKitLabels: List<SceneLabelObservation> = emptyList()
    ): AiRecommendation {
        val scores = FloatArray(RecognizedScene.entries.size)

        val dynamicSpread = computeHistogramDynamicSpread(analysis.lumaHistogram)
        val isHighContrast = dynamicSpread > 0.62f ||
            (analysis.highlightClipPercent > 2.2f && analysis.shadowClipPercent > 3.0f)

        val isLowLight = currentIso >= 640 || currentExposureNs >= 33_000_000L || analysis.meanLuma < 58f
        val isBrightOutdoor = currentIso <= 125 && currentExposureNs <= 5_000_000L && analysis.meanLuma > 105f
        val isBacklit = (analysis.topBottomLumaRatio > 1.85f || isHighContrast) &&
            (analysis.highlightClipPercent > 3.2f || analysis.shadowClipPercent > 5.5f)
        val isCloseFocus = focusDistanceDiopters > 3.5f

        // --- Signal A: Camera2 AE/AF + YUV Photometric Heuristics ---
        if (faceCount > 0) {
            scores[RecognizedScene.PORTRAIT.ordinal] += 0.72f + (faceCount * 0.08f).coerceAtMost(0.24f)
        }
        if (isLowLight) {
            scores[RecognizedScene.NIGHT.ordinal] += 0.65f + ((80f - analysis.meanLuma).coerceAtLeast(0f) / 100f)
        }
        if (isBacklit) {
            scores[RecognizedScene.BACKLIT.ordinal] += 0.70f
        }
        if (analysis.edgeDensity > 0.14f && analysis.meanLuma > 120f && analysis.warmColorRatio < 0.25f) {
            scores[RecognizedScene.DOCUMENT.ordinal] += 0.58f + (analysis.edgeDensity * 0.8f).coerceAtMost(0.32f)
        }
        if (analysis.warmColorRatio > 0.22f && (isCloseFocus || analysis.meanLuma in 65f..185f) && faceCount == 0) {
            scores[RecognizedScene.FOOD.ordinal] += 0.52f + analysis.warmColorRatio * 0.6f
        }
        if ((analysis.blueSkyRatio > 0.18f || analysis.greenColorRatio > 0.18f) && focusDistanceDiopters < 1.0f && faceCount == 0) {
            scores[RecognizedScene.LANDSCAPE.ordinal] += 0.55f + (analysis.blueSkyRatio + analysis.greenColorRatio) * 0.45f
        }
        if (analysis.edgeDensity > 0.13f && focusDistanceDiopters < 1.5f && faceCount == 0 && !isLowLight) {
            scores[RecognizedScene.BUILDING.ordinal] += 0.52f + analysis.edgeDensity * 0.7f
        }
        if (faceCount == 0 && isCloseFocus && analysis.edgeDensity > 0.10f && analysis.warmColorRatio in 0.08f..0.28f) {
            scores[RecognizedScene.PET.ordinal] += 0.54f
        }
        if (isBrightOutdoor) {
            scores[RecognizedScene.OUTDOOR.ordinal] += 0.48f
        }
        if (!isBrightOutdoor && !isLowLight) {
            scores[RecognizedScene.INDOOR.ordinal] += 0.44f
        }

        // --- Signal B: ML Kit Semantic Classification Boost ---
        var topMatchedLabel: SceneLabelObservation? = mlKitLabels.maxByOrNull { it.confidence }
        for (obs in mlKitLabels) {
            val mapped = mapMlKitLabelToScene(obs.label) ?: continue
            val boost = (0.50f + obs.confidence * 0.55f).coerceIn(0.45f, 1.05f)
            scores[mapped.ordinal] += boost
            if (topMatchedLabel == null || obs.confidence >= topMatchedLabel.confidence) {
                topMatchedLabel = obs
            }
        }

        // Low-light override protection: if sensor exposure strongly confirms dim scene, boost NIGHT cleanly
        if (analysis.meanLuma < 44f && currentIso >= 800) {
            scores[RecognizedScene.NIGHT.ordinal] += 0.35f
        }

        var bestIdx = RecognizedScene.INDOOR.ordinal
        var bestScore = -1f
        for (i in scores.indices) {
            if (scores[i] > bestScore) {
                bestScore = scores[i]
                bestIdx = i
            }
        }
        val scene = RecognizedScene.entries[bestIdx]
        val confidence = bestScore.coerceIn(0.42f, 0.99f)

        val rec = when (scene) {
            RecognizedScene.PORTRAIT -> RecTuple(
                200, 8_333_333L, 0, WhiteBalancePreset.AUTO, true, AppDestination.CAMERA,
                AiProcessingProfile(
                    sharpnessAmount = 0.28f, clarityAmount = 0.15f, chromaDenoiseStrength = 0.36f,
                    vibranceBoost = 0.12f, shadowLift = 0.10f, highlightCompress = 0.10f,
                    ispNrMode = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    ispEdgeMode = CameraMetadata.EDGE_MODE_HIGH_QUALITY,
                    videoBitrateMultiplier = 1.20f, recommendedVideoFps = 30, videoStabilizePriority = true,
                    contrastAdjust = 0.02f, saturationAdjust = -0.02f, warmthAdjust = 0.03f
                )
            )
            RecognizedScene.LANDSCAPE -> RecTuple(
                100, 4_000_000L, 0, WhiteBalancePreset.DAYLIGHT, true, AppDestination.HDR,
                AiProcessingProfile(
                    sharpnessAmount = 0.46f, clarityAmount = 0.32f, chromaDenoiseStrength = 0.24f,
                    vibranceBoost = 0.24f, shadowLift = 0.08f, highlightCompress = 0.18f,
                    ispNrMode = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    ispEdgeMode = CameraMetadata.EDGE_MODE_HIGH_QUALITY,
                    videoBitrateMultiplier = 1.30f, recommendedVideoFps = 30, videoStabilizePriority = true,
                    contrastAdjust = 0.12f, saturationAdjust = 0.16f, warmthAdjust = 0.01f
                )
            )
            RecognizedScene.FOOD -> RecTuple(
                250, 16_666_666L, +1, WhiteBalancePreset.AUTO, false, AppDestination.CAMERA,
                AiProcessingProfile(
                    sharpnessAmount = 0.42f, clarityAmount = 0.28f, chromaDenoiseStrength = 0.28f,
                    vibranceBoost = 0.22f, shadowLift = 0.08f, highlightCompress = 0.08f,
                    ispNrMode = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    ispEdgeMode = CameraMetadata.EDGE_MODE_HIGH_QUALITY,
                    videoBitrateMultiplier = 1.20f, recommendedVideoFps = 30, videoStabilizePriority = true,
                    contrastAdjust = 0.10f, saturationAdjust = 0.20f, warmthAdjust = 0.06f
                )
            )
            RecognizedScene.DOCUMENT -> RecTuple(
                160, 10_000_000L, +1, WhiteBalancePreset.FLUORESCENT, false, AppDestination.CAMERA,
                AiProcessingProfile(
                    sharpnessAmount = 0.58f, clarityAmount = 0.36f, chromaDenoiseStrength = 0.18f,
                    vibranceBoost = 0.04f, shadowLift = 0.04f, highlightCompress = 0.06f,
                    ispNrMode = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    ispEdgeMode = CameraMetadata.EDGE_MODE_HIGH_QUALITY,
                    videoBitrateMultiplier = 1.15f, recommendedVideoFps = 30, videoStabilizePriority = true,
                    contrastAdjust = 0.22f, saturationAdjust = -0.18f, warmthAdjust = 0.0f
                )
            )
            RecognizedScene.PET -> RecTuple(
                400, 4_000_000L, 0, WhiteBalancePreset.AUTO, false, AppDestination.CAMERA,
                AiProcessingProfile(
                    sharpnessAmount = 0.48f, clarityAmount = 0.26f, chromaDenoiseStrength = 0.32f,
                    vibranceBoost = 0.16f, shadowLift = 0.09f, highlightCompress = 0.10f,
                    ispNrMode = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    ispEdgeMode = CameraMetadata.EDGE_MODE_HIGH_QUALITY,
                    videoBitrateMultiplier = 1.25f, recommendedVideoFps = 60, videoStabilizePriority = true,
                    contrastAdjust = 0.08f, saturationAdjust = 0.10f, warmthAdjust = 0.02f
                )
            )
            RecognizedScene.BUILDING -> RecTuple(
                100, 5_000_000L, 0, WhiteBalancePreset.DAYLIGHT, true, AppDestination.HDR,
                AiProcessingProfile(
                    sharpnessAmount = 0.50f, clarityAmount = 0.34f, chromaDenoiseStrength = 0.24f,
                    vibranceBoost = 0.16f, shadowLift = 0.10f, highlightCompress = 0.16f,
                    ispNrMode = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    ispEdgeMode = CameraMetadata.EDGE_MODE_HIGH_QUALITY,
                    videoBitrateMultiplier = 1.25f, recommendedVideoFps = 30, videoStabilizePriority = true,
                    contrastAdjust = 0.14f, saturationAdjust = 0.06f, warmthAdjust = -0.01f
                )
            )
            RecognizedScene.NIGHT -> RecTuple(
                800, 33_333_333L, 0, WhiteBalancePreset.AUTO, false, AppDestination.NIGHT,
                AiProcessingProfile(
                    sharpnessAmount = 0.32f, clarityAmount = 0.20f, chromaDenoiseStrength = 0.56f,
                    vibranceBoost = 0.14f, shadowLift = 0.18f, highlightCompress = 0.22f,
                    ispNrMode = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    ispEdgeMode = CameraMetadata.EDGE_MODE_HIGH_QUALITY,
                    videoBitrateMultiplier = 1.25f, recommendedVideoFps = 30, videoStabilizePriority = true,
                    contrastAdjust = 0.04f, saturationAdjust = 0.06f, warmthAdjust = -0.02f
                )
            )
            RecognizedScene.BACKLIT -> RecTuple(
                160, 8_000_000L, +1, WhiteBalancePreset.CLOUDY, true, AppDestination.HDR,
                AiProcessingProfile(
                    sharpnessAmount = 0.40f, clarityAmount = 0.28f, chromaDenoiseStrength = 0.34f,
                    vibranceBoost = 0.18f, shadowLift = 0.22f, highlightCompress = 0.24f,
                    ispNrMode = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    ispEdgeMode = CameraMetadata.EDGE_MODE_HIGH_QUALITY,
                    videoBitrateMultiplier = 1.25f, recommendedVideoFps = 30, videoStabilizePriority = true,
                    contrastAdjust = 0.03f, saturationAdjust = 0.08f, warmthAdjust = 0.02f
                )
            )
            RecognizedScene.OUTDOOR -> RecTuple(
                100, 2_500_000L, 0, WhiteBalancePreset.DAYLIGHT, true, AppDestination.CAMERA,
                AiProcessingProfile(
                    sharpnessAmount = 0.44f, clarityAmount = 0.28f, chromaDenoiseStrength = 0.22f,
                    vibranceBoost = 0.18f, shadowLift = 0.07f, highlightCompress = 0.14f,
                    ispNrMode = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    ispEdgeMode = CameraMetadata.EDGE_MODE_HIGH_QUALITY,
                    videoBitrateMultiplier = 1.30f, recommendedVideoFps = 60, videoStabilizePriority = true,
                    contrastAdjust = 0.09f, saturationAdjust = 0.10f, warmthAdjust = 0.01f
                )
            )
            RecognizedScene.INDOOR -> RecTuple(
                400, 16_666_666L, 0, WhiteBalancePreset.AUTO, false, AppDestination.CAMERA,
                AiProcessingProfile(
                    sharpnessAmount = 0.38f, clarityAmount = 0.24f, chromaDenoiseStrength = 0.36f,
                    vibranceBoost = 0.14f, shadowLift = 0.11f, highlightCompress = 0.10f,
                    ispNrMode = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    ispEdgeMode = CameraMetadata.EDGE_MODE_HIGH_QUALITY,
                    videoBitrateMultiplier = 1.18f, recommendedVideoFps = 30, videoStabilizePriority = true,
                    contrastAdjust = 0.06f, saturationAdjust = 0.05f, warmthAdjust = 0.01f
                )
            )
        }

        // Adapt profile dynamically based on live histogram clipping & ISO noise
        val highIsoBonus = if (currentIso >= 800) 0.12f else 0f
        val clipShadowExtra = (analysis.shadowClipPercent / 100f * 0.6f).coerceIn(0f, 0.08f)
        val clipHighExtra = (analysis.highlightClipPercent / 100f * 0.6f).coerceIn(0f, 0.08f)

        val baseProfile = rec.profile
        val adaptedProfile = baseProfile.copy(
            sharpnessAmount = (baseProfile.sharpnessAmount + if (fullPower) 0.10f else 0f).coerceIn(0.18f, 0.72f),
            clarityAmount = (baseProfile.clarityAmount + if (fullPower) 0.08f else 0f).coerceIn(0.10f, 0.44f),
            chromaDenoiseStrength = (baseProfile.chromaDenoiseStrength + highIsoBonus + if (fullPower) 0.08f else 0f).coerceIn(0.15f, 0.70f),
            vibranceBoost = (baseProfile.vibranceBoost + if (fullPower) 0.05f else 0f).coerceIn(0.02f, 0.30f),
            shadowLift = (baseProfile.shadowLift + clipShadowExtra + if (fullPower) 0.03f else 0f).coerceIn(0.02f, 0.28f),
            highlightCompress = (baseProfile.highlightCompress + clipHighExtra + if (fullPower) 0.03f else 0f).coerceIn(0.04f, 0.28f),
            videoBitrateMultiplier = (baseProfile.videoBitrateMultiplier * if (fullPower) 1.25f else 1.0f).coerceIn(1.0f, 1.65f)
        )

        val clampedIso = isoRange?.let { rec.iso.coerceIn(it.first, it.last) } ?: rec.iso
        val clampedShutter = exposureRangeNs?.let { rec.shutterNs.coerceIn(it.first, it.last) } ?: rec.shutterNs
        val clampedEv = rec.evSteps.coerceIn(aeCompRange.first, aeCompRange.last)

        return AiRecommendation(
            scene = scene,
            confidence = confidence,
            recommendedIso = clampedIso,
            recommendedShutterNs = clampedShutter,
            recommendedEvSteps = clampedEv,
            recommendedWb = rec.wb,
            recommendHdr = rec.hdr || isHighContrast,
            recommendedMode = rec.mode,
            profile = adaptedProfile,
            mlKitLabel = topMatchedLabel?.label,
            mlKitConfidence = topMatchedLabel?.confidence ?: 0f
        )
    }

    fun mapMlKitLabelToScene(rawLabel: String): RecognizedScene? {
        val k = rawLabel.trim().lowercase()
        return when {
            k in setOf("person", "face", "selfie", "smile", "human", "man", "woman", "child", "portrait", "head", "hair") ->
                RecognizedScene.PORTRAIT
            k in setOf("food", "dish", "cuisine", "meal", "fruit", "vegetable", "dessert", "cake", "bread", "pizza", "burger", "coffee", "drink", "beverage", "salad", "breakfast", "lunch", "dinner", "fast food", "baked goods") ->
                RecognizedScene.FOOD
            k in setOf("sky", "cloud", "mountain", "nature", "forest", "tree", "beach", "ocean", "sea", "sunset", "sunrise", "landscape", "plant", "flower", "garden", "grass", "lake", "river", "hill", "valley", "field") ->
                RecognizedScene.LANDSCAPE
            k in setOf("cat", "dog", "pet", "animal", "bird", "puppy", "kitten", "mammal", "wildlife", "horse") ->
                RecognizedScene.PET
            k in setOf("building", "architecture", "skyscraper", "city", "house", "tower", "bridge", "facade", "interior design", "room", "street") ->
                RecognizedScene.BUILDING
            k in setOf("text", "paper", "document", "book", "handwriting", "receipt", "newspaper", "page", "magazine", "label", "screenshot") ->
                RecognizedScene.DOCUMENT
            k in setOf("night", "dark", "darkness", "fireworks", "neon", "midnight", "moon", "star") ->
                RecognizedScene.NIGHT
            else -> null
        }
    }

    fun mergeAiPresetToPatcher(
        base: LibPatcherConfig,
        recommendation: AiRecommendation?,
        aiAutoAdjustEnabled: Boolean
    ): LibPatcherConfig {
        if (recommendation == null || !aiAutoAdjustEnabled) return base
        val p = recommendation.profile
        val contrastDelta = (p.contrastAdjust - 0.06f) * 0.85f
        val satDelta = (p.saturationAdjust - 0.04f) * 0.85f
        val vibDelta = (p.vibranceBoost - 0.14f) * 0.55f
        val shadowDelta = (p.shadowLift - 0.08f) * 0.55f
        val highDelta = (p.highlightCompress - 0.10f) * 0.55f
        return base.copy(
            contrast = (base.contrast + contrastDelta).coerceIn(-0.35f, 0.45f),
            saturation = (base.saturation + satDelta).coerceIn(-0.40f, 0.50f),
            vibrance = (base.vibrance + vibDelta).coerceIn(0f, 0.50f),
            warmth = (base.warmth + p.warmthAdjust * 0.75f).coerceIn(-0.35f, 0.35f),
            shadowLift = (base.shadowLift + shadowDelta).coerceIn(0f, 0.42f),
            highlightCompress = (base.highlightCompress + highDelta).coerceIn(0f, 0.42f)
        )
    }

    private fun computeHistogramDynamicSpread(lumaHist: IntArray): Float {
        if (lumaHist.isEmpty()) return 0.5f
        val total = lumaHist.sum()
        if (total <= 0) return 0.5f
        val p10Target = total * 0.10f
        val p90Target = total * 0.90f
        var acc = 0
        var p10Idx = 0
        var p90Idx = lumaHist.lastIndex
        var foundP10 = false
        for (i in lumaHist.indices) {
            acc += lumaHist[i]
            if (!foundP10 && acc >= p10Target) {
                p10Idx = i
                foundP10 = true
            }
            if (acc >= p90Target) {
                p90Idx = i
                break
            }
        }
        return ((p90Idx - p10Idx).coerceAtLeast(0).toFloat() / lumaHist.lastIndex.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    private data class RecTuple(
        val iso: Int,
        val shutterNs: Long,
        val evSteps: Int,
        val wb: WhiteBalancePreset,
        val hdr: Boolean,
        val mode: AppDestination,
        val profile: AiProcessingProfile
    )
}
