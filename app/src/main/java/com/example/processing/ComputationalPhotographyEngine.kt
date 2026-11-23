package com.example.processing

import android.graphics.Bitmap
import android.graphics.RectF

class ComputationalPhotographyEngine(
    private val alignmentEngine: ImageAlignmentEngine = ImageAlignmentEngine(),
    val hdrProcessor: HDRProcessor = HDRProcessor(alignmentEngine),
    val nightProcessor: NightProcessor = NightProcessor(alignmentEngine),
    val superResolutionProcessor: SuperResolutionProcessor = SuperResolutionProcessor(alignmentEngine),
    val detailEngine: DetailEngine = DetailEngine(),
    val motionEngine: MotionEngine = MotionEngine(),
    val portraitProcessor: PortraitProcessor = PortraitProcessor(),
    val panoramaEngine: PanoramaEngine = PanoramaEngine(),
    val documentScannerEngine: DocumentScannerEngine = DocumentScannerEngine()
) {
    private fun mergeEffectivePatch(
        patch: LibPatcherConfig,
        aiProfile: AiProcessingProfile?,
        fullPower: Boolean,
        liveShadowBias: Float,
        liveHighlightBias: Float
    ): LibPatcherConfig {
        val aiSharpDelta = if (aiProfile != null) (aiProfile.sharpnessAmount - 0.34f) * 0.45f else 0f
        val aiClarityDelta = if (aiProfile != null) (aiProfile.clarityAmount - 0.22f) * 0.45f else 0f
        val aiChromaDelta = if (aiProfile != null) (aiProfile.chromaDenoiseStrength - 0.30f) * 0.50f else 0f
        val aiVibDelta = if (aiProfile != null) (aiProfile.vibranceBoost - 0.14f) * 0.55f else 0f
        val aiShadowDelta = if (aiProfile != null) (aiProfile.shadowLift - 0.08f) * 0.55f else 0f
        val aiHighDelta = if (aiProfile != null) (aiProfile.highlightCompress - 0.10f) * 0.55f else 0f
        val aiContrastDelta = if (aiProfile != null) (aiProfile.contrastAdjust - 0.06f) * 0.80f else 0f
        val aiSatDelta = if (aiProfile != null) (aiProfile.saturationAdjust - 0.04f) * 0.80f else 0f
        val aiWarmthDelta = if (aiProfile != null) aiProfile.warmthAdjust * 0.70f else 0f

        val turboBonus = if (fullPower) 0.07f else 0f
        return patch.copy(
            sharpness = (patch.sharpness + aiSharpDelta + turboBonus).coerceIn(0f, 1.0f),
            clarity = (patch.clarity + aiClarityDelta + turboBonus).coerceIn(0f, 0.8f),
            lumaDenoise = (patch.lumaDenoise + if (fullPower) 0.05f else 0f).coerceIn(0f, 0.8f),
            chromaDenoise = (patch.chromaDenoise + aiChromaDelta + if (fullPower) 0.06f else 0f).coerceIn(0f, 0.85f),
            contrast = (patch.contrast + aiContrastDelta).coerceIn(-0.35f, 0.45f),
            saturation = (patch.saturation + aiSatDelta).coerceIn(-0.40f, 0.50f),
            vibrance = (patch.vibrance + aiVibDelta + if (fullPower) 0.04f else 0f).coerceIn(0f, 0.50f),
            warmth = (patch.warmth + aiWarmthDelta).coerceIn(-0.35f, 0.35f),
            shadowLift = (patch.shadowLift + aiShadowDelta + liveShadowBias).coerceIn(0f, 0.42f),
            highlightCompress = (patch.highlightCompress + aiHighDelta - liveHighlightBias).coerceIn(0f, 0.42f)
        )
    }

    fun refineStandardPhoto(
        src: Bitmap,
        sharpnessAmount: Float = 0.34f,
        aiProfile: AiProcessingProfile? = null,
        fullPower: Boolean = false,
        libPatcher: LibPatcherConfig = LibPatcherConfig(),
        liveShadowBias: Float = 0f,
        liveHighlightBias: Float = 0f
    ): MotionAssessment {
        val merged = mergeEffectivePatch(libPatcher, aiProfile, fullPower, liveShadowBias, liveHighlightBias)
        val config = EnhanceConfig(
            sharpnessAmount = if (aiProfile == null && sharpnessAmount != 0.34f) sharpnessAmount else merged.sharpness,
            clarityAmount = merged.clarity,
            lumaDenoiseStrength = merged.lumaDenoise,
            chromaDenoiseStrength = merged.chromaDenoise,
            vibranceBoost = merged.vibrance,
            shadowLift = merged.shadowLift,
            highlightCompress = merged.highlightCompress,
            fullPower = fullPower,
            libPatcher = merged
        )
        val detailed = detailEngine.enhanceDetails(src, config = config)
        return motionEngine.assessAndStabilize(detailed)
    }

    fun finishComputationalFrame(
        src: Bitmap,
        sharpnessAmount: Float,
        aiProfile: AiProcessingProfile? = null,
        fullPower: Boolean = false,
        libPatcher: LibPatcherConfig = LibPatcherConfig(),
        liveShadowBias: Float = 0f,
        liveHighlightBias: Float = 0f
    ): Bitmap {
        val merged = mergeEffectivePatch(libPatcher, aiProfile, fullPower, liveShadowBias, liveHighlightBias)
        val config = EnhanceConfig(
            sharpnessAmount = ((merged.sharpness + sharpnessAmount) * 0.5f + if (fullPower) 0.05f else 0f).coerceIn(0.10f, 1.0f),
            clarityAmount = merged.clarity,
            lumaDenoiseStrength = merged.lumaDenoise * 0.85f,
            chromaDenoiseStrength = merged.chromaDenoise * 0.90f,
            vibranceBoost = merged.vibrance,
            shadowLift = merged.shadowLift * 0.55f,
            highlightCompress = merged.highlightCompress * 0.55f,
            fullPower = fullPower,
            libPatcher = merged
        )
        return detailEngine.enhanceDetails(src, config = config)
    }

    fun processPortrait(
        src: Bitmap,
        facesNormalized: List<RectF>,
        blurIntensity: Float = 0.74f,
        aiProfile: AiProcessingProfile? = null,
        fullPower: Boolean = false,
        libPatcher: LibPatcherConfig = LibPatcherConfig(),
        liveShadowBias: Float = 0f,
        liveHighlightBias: Float = 0f
    ): Bitmap {
        val bokeh = portraitProcessor.applyPortraitBokeh(src, facesNormalized, blurIntensity, fullPower = fullPower)
        val merged = mergeEffectivePatch(libPatcher, aiProfile, fullPower, liveShadowBias, liveHighlightBias)
        val config = EnhanceConfig(
            sharpnessAmount = (merged.sharpness * 0.80f).coerceIn(0.15f, 0.55f),
            clarityAmount = (merged.clarity * 0.65f).coerceIn(0.08f, 0.30f),
            lumaDenoiseStrength = merged.lumaDenoise,
            chromaDenoiseStrength = merged.chromaDenoise,
            vibranceBoost = merged.vibrance,
            shadowLift = merged.shadowLift,
            highlightCompress = merged.highlightCompress,
            correctChromaticAberration = false,
            fullPower = fullPower,
            libPatcher = merged
        )
        val finished = detailEngine.enhanceDetails(bokeh, config = config)
        if (finished !== bokeh) bokeh.recycle()
        return finished
    }
}
