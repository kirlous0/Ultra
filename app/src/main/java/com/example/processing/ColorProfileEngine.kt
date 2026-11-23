package com.example.processing

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import androidx.compose.runtime.Immutable
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

@Immutable
enum class CameraColorProfile(
    val labelEn: String,
    val labelAr: String,
    val badge: String
) {
    NATURAL_TRUE("True Natural", "طبيعي حقيقي", "TRUE"),
    LEICA_VIBRANT("Leica Vibrant", "ألوان Leica الغنية", "LEICA-V"),
    LEICA_AUTHENTIC("Leica Auth", "كلاسيك Leica", "LEICA-A"),
    HASSELBLAD_NATURAL("Hasselblad", "Hasselblad طبيعي", "HASS"),
    FUJI_CLASSIC_CHROME("Classic Chrome", "كلاسيك كروم", "CHROME"),
    CINEMA_TEAL_ORANGE("Cinema T&O", "سينمائي", "CINE"),
    GOLDEN_PORTRA("Portra Gold", "بورتريه دافئ", "PORTRA"),
    MONOCHROME_NOIR("Mono Noir", "أبيض وأسود", "MONO")
}

@Immutable
enum class HdrToneStyle(val labelEn: String, val labelAr: String) {
    FILMIC_BALANCED("Filmic", "سينمائي متوازن"),
    PIXEL_PUNCH("HDR+ Punch", "تباين Pixel+"),
    SOFT_CINEMA("Soft Cinema", "إضاءة ناعمة"),
    HIGH_DR("Max DR", "أقصى نطاق")
}

@Immutable
data class LibPatcherConfig(
    val colorProfile: CameraColorProfile = CameraColorProfile.LEICA_VIBRANT,
    val hdrToneStyle: HdrToneStyle = HdrToneStyle.PIXEL_PUNCH,
    val sharpness: Float = 0.38f,         // 0.0 .. 1.0
    val clarity: Float = 0.24f,           // 0.0 .. 0.8
    val lumaDenoise: Float = 0.20f,       // 0.0 .. 0.8
    val chromaDenoise: Float = 0.34f,     // 0.0 .. 0.85
    val contrast: Float = 0.06f,          // -0.35 .. +0.45
    val saturation: Float = 0.04f,        // -0.40 .. +0.50
    val vibrance: Float = 0.16f,          // 0.0 .. 0.50
    val warmth: Float = 0.0f,             // -0.35 .. +0.35
    val tint: Float = 0.0f,               // -0.35 .. +0.35
    val shadowLift: Float = 0.10f,        // 0.0 .. 0.40
    val highlightCompress: Float = 0.14f, // 0.0 .. 0.40
    val dehaze: Float = 0.10f,            // 0.0 .. 0.45
    val vignette: Float = 0.0f,           // 0.0 .. 0.45
    val skinToneProtect: Boolean = true
) {
    fun toJsonString(): String = JSONObject().apply {
        put("colorProfile", colorProfile.name)
        put("hdrToneStyle", hdrToneStyle.name)
        put("sharpness", sharpness.toDouble())
        put("clarity", clarity.toDouble())
        put("lumaDenoise", lumaDenoise.toDouble())
        put("chromaDenoise", chromaDenoise.toDouble())
        put("contrast", contrast.toDouble())
        put("saturation", saturation.toDouble())
        put("vibrance", vibrance.toDouble())
        put("warmth", warmth.toDouble())
        put("tint", tint.toDouble())
        put("shadowLift", shadowLift.toDouble())
        put("highlightCompress", highlightCompress.toDouble())
        put("dehaze", dehaze.toDouble())
        put("vignette", vignette.toDouble())
        put("skinToneProtect", skinToneProtect)
    }.toString()

    companion object {
        fun fromJsonString(raw: String?): LibPatcherConfig {
            if (raw.isNullOrBlank()) return LibPatcherConfig()
            return runCatching {
                val o = JSONObject(raw)
                LibPatcherConfig(
                    colorProfile = runCatching {
                        CameraColorProfile.valueOf(o.optString("colorProfile"))
                    }.getOrDefault(CameraColorProfile.LEICA_VIBRANT),
                    hdrToneStyle = runCatching {
                        HdrToneStyle.valueOf(o.optString("hdrToneStyle"))
                    }.getOrDefault(HdrToneStyle.PIXEL_PUNCH),
                    sharpness = o.optDouble("sharpness", 0.38).toFloat().coerceIn(0f, 1f),
                    clarity = o.optDouble("clarity", 0.24).toFloat().coerceIn(0f, 0.8f),
                    lumaDenoise = o.optDouble("lumaDenoise", 0.20).toFloat().coerceIn(0f, 0.8f),
                    chromaDenoise = o.optDouble("chromaDenoise", 0.34).toFloat().coerceIn(0f, 0.85f),
                    contrast = o.optDouble("contrast", 0.06).toFloat().coerceIn(-0.35f, 0.45f),
                    saturation = o.optDouble("saturation", 0.04).toFloat().coerceIn(-0.40f, 0.50f),
                    vibrance = o.optDouble("vibrance", 0.16).toFloat().coerceIn(0f, 0.50f),
                    warmth = o.optDouble("warmth", 0.0).toFloat().coerceIn(-0.35f, 0.35f),
                    tint = o.optDouble("tint", 0.0).toFloat().coerceIn(-0.35f, 0.35f),
                    shadowLift = o.optDouble("shadowLift", 0.10).toFloat().coerceIn(0f, 0.40f),
                    highlightCompress = o.optDouble("highlightCompress", 0.14).toFloat().coerceIn(0f, 0.40f),
                    dehaze = o.optDouble("dehaze", 0.10).toFloat().coerceIn(0f, 0.45f),
                    vignette = o.optDouble("vignette", 0.0).toFloat().coerceIn(0f, 0.45f),
                    skinToneProtect = o.optBoolean("skinToneProtect", true)
                )
            }.getOrDefault(LibPatcherConfig())
        }

        val PRESET_GCAM_PUNCH = LibPatcherConfig(
            colorProfile = CameraColorProfile.LEICA_VIBRANT,
            hdrToneStyle = HdrToneStyle.PIXEL_PUNCH,
            sharpness = 0.44f,
            clarity = 0.28f,
            lumaDenoise = 0.24f,
            chromaDenoise = 0.36f,
            contrast = 0.10f,
            saturation = 0.06f,
            vibrance = 0.20f,
            shadowLift = 0.14f,
            highlightCompress = 0.18f,
            dehaze = 0.12f
        )

        val PRESET_LEICA_CLASSIC = LibPatcherConfig(
            colorProfile = CameraColorProfile.LEICA_AUTHENTIC,
            hdrToneStyle = HdrToneStyle.FILMIC_BALANCED,
            sharpness = 0.36f,
            clarity = 0.26f,
            lumaDenoise = 0.16f,
            chromaDenoise = 0.28f,
            contrast = 0.14f,
            saturation = -0.02f,
            vibrance = 0.12f,
            warmth = 0.04f,
            shadowLift = 0.06f,
            highlightCompress = 0.16f,
            dehaze = 0.14f,
            vignette = 0.16f
        )

        val PRESET_NIGHT_CRISP = LibPatcherConfig(
            colorProfile = CameraColorProfile.HASSELBLAD_NATURAL,
            hdrToneStyle = HdrToneStyle.HIGH_DR,
            sharpness = 0.34f,
            clarity = 0.22f,
            lumaDenoise = 0.42f,
            chromaDenoise = 0.54f,
            contrast = 0.08f,
            saturation = 0.04f,
            vibrance = 0.15f,
            shadowLift = 0.20f,
            highlightCompress = 0.22f,
            dehaze = 0.10f
        )

        val PRESET_CINEMA_FILM = LibPatcherConfig(
            colorProfile = CameraColorProfile.CINEMA_TEAL_ORANGE,
            hdrToneStyle = HdrToneStyle.SOFT_CINEMA,
            sharpness = 0.32f,
            clarity = 0.22f,
            lumaDenoise = 0.20f,
            chromaDenoise = 0.32f,
            contrast = 0.12f,
            saturation = 0.02f,
            vibrance = 0.14f,
            shadowLift = 0.08f,
            highlightCompress = 0.20f,
            dehaze = 0.10f,
            vignette = 0.14f
        )
    }
}

object ColorProfileEngine {

    /**
     * Builds a hardware-accelerated Android ColorMatrix for live TextureView previewing
     * so selected color science, warmth, contrast, and saturation appear smoothly in real time.
     */
    fun buildLivePreviewColorMatrix(patch: LibPatcherConfig): ColorMatrix {
        val out = ColorMatrix()

        // 1. Base profile matrix / monochrome conversion
        val base = when (patch.colorProfile) {
            CameraColorProfile.NATURAL_TRUE -> ColorMatrix()
            CameraColorProfile.LEICA_VIBRANT -> ColorMatrix(
                floatArrayOf(
                    1.07f, -0.03f, -0.02f, 0f, 2f,
                    -0.02f, 1.04f, -0.01f, 0f, 0f,
                    -0.02f, -0.02f, 1.08f, 0f, 1f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            CameraColorProfile.LEICA_AUTHENTIC -> ColorMatrix(
                floatArrayOf(
                    1.03f, 0.01f, -0.03f, 0f, -2f,
                    0.00f, 1.00f, -0.01f, 0f, -2f,
                    -0.03f, 0.01f, 0.96f, 0f, -3f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            CameraColorProfile.HASSELBLAD_NATURAL -> ColorMatrix(
                floatArrayOf(
                    1.02f, 0.02f, -0.02f, 0f, 1f,
                    -0.01f, 1.03f, 0.01f, 0f, 1f,
                    -0.01f, -0.01f, 1.04f, 0f, 1f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            CameraColorProfile.FUJI_CLASSIC_CHROME -> ColorMatrix(
                floatArrayOf(
                    0.94f, 0.05f, 0.01f, 0f, 0f,
                    0.02f, 0.97f, 0.03f, 0f, 2f,
                    0.00f, 0.06f, 0.98f, 0f, 4f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            CameraColorProfile.CINEMA_TEAL_ORANGE -> ColorMatrix(
                floatArrayOf(
                    1.08f, -0.04f, -0.06f, 0f, 2f,
                    0.00f, 1.01f, 0.02f, 0f, 1f,
                    -0.06f, 0.05f, 1.06f, 0f, 3f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            CameraColorProfile.GOLDEN_PORTRA -> ColorMatrix(
                floatArrayOf(
                    1.05f, 0.03f, -0.03f, 0f, 5f,
                    0.01f, 1.02f, -0.01f, 0f, 3f,
                    -0.02f, 0.00f, 0.94f, 0f, 2f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            CameraColorProfile.MONOCHROME_NOIR -> ColorMatrix().apply { setSaturation(0f) }
        }
        out.postConcat(base)

        // 2. Contrast & slight brightness compensation
        val profileContrast = when (patch.colorProfile) {
            CameraColorProfile.LEICA_AUTHENTIC -> 0.08f
            CameraColorProfile.FUJI_CLASSIC_CHROME -> 0.07f
            CameraColorProfile.MONOCHROME_NOIR -> 0.16f
            CameraColorProfile.GOLDEN_PORTRA -> -0.03f
            else -> 0.03f
        }
        val cScale = (1f + patch.contrast * 0.7f + profileContrast).coerceIn(0.65f, 1.45f)
        val cBias = (-0.5f * cScale + 0.5f) * 255f
        out.postConcat(
            ColorMatrix(
                floatArrayOf(
                    cScale, 0f, 0f, 0f, cBias,
                    0f, cScale, 0f, 0f, cBias,
                    0f, 0f, cScale, 0f, cBias,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        // 3. Saturation (skip if Mono)
        if (patch.colorProfile != CameraColorProfile.MONOCHROME_NOIR) {
            val profileSat = when (patch.colorProfile) {
                CameraColorProfile.LEICA_VIBRANT -> 0.12f
                CameraColorProfile.FUJI_CLASSIC_CHROME -> -0.10f
                CameraColorProfile.LEICA_AUTHENTIC -> -0.04f
                else -> 0.04f
            }
            val sat = (1f + patch.saturation + profileSat + patch.vibrance * 0.35f).coerceIn(0.4f, 1.65f)
            val satMat = ColorMatrix().apply { setSaturation(sat) }
            out.postConcat(satMat)
        }

        // 4. Warmth & Tint
        if (abs(patch.warmth) > 0.005f || abs(patch.tint) > 0.005f) {
            val rShift = patch.warmth * 26f + patch.tint * 12f
            val gShift = -patch.tint * 16f
            val bShift = -patch.warmth * 26f + patch.tint * 12f
            out.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, rShift,
                        0f, 1f, 0f, 0f, gShift,
                        0f, 0f, 1f, 0f, bShift,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        return out
    }

    /**
     * Standalone multi-core grading pass used by Studio color presets or quick bitmap grading.
     */
    fun applyProfileToBitmap(
        src: Bitmap,
        patch: LibPatcherConfig,
        fullPower: Boolean = false
    ): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val cx = w * 0.5f
        val cy = h * 0.5f
        val invMaxR = 1f / kotlin.math.hypot(cx, cy).coerceAtLeast(1f)

        ParallelCompute.parallelRows(h, fullPower) { startY, endY ->
            for (y in startY until endY) {
                val dy = y - cy
                val rowOff = y * w
                for (x in 0 until w) {
                    val i = rowOff + x
                    val c = px[i]
                    val rNorm = kotlin.math.hypot(x - cx, dy) * invMaxR
                    out[i] = gradePixel(
                        rIn = ((c ushr 16) and 0xFF) / 255f,
                        gIn = ((c ushr 8) and 0xFF) / 255f,
                        bIn = (c and 0xFF) / 255f,
                        normRadius = rNorm,
                        patch = patch,
                        isSkin = false
                    )
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Inline per-pixel computational color & tone science:
     * 1. Dehaze / dark-channel micro-contrast
     * 2. HDR+ / Filmic Tone Style curve
     * 3. Warmth + Tint white-balance fine trim
     * 4. Split-toning & 3x3 Color Profile science (Leica / Hasselblad / Fuji / Cinema / Portra / Noir)
     * 5. Contrast S-curve + Skin-safe Vibrance & Saturation
     * 6. Optional natural optical vignette
     */
    fun gradePixel(
        rIn: Float,
        gIn: Float,
        bIn: Float,
        normRadius: Float,
        patch: LibPatcherConfig,
        isSkin: Boolean
    ): Int {
        var r = rIn.coerceIn(0f, 1f)
        var g = gIn.coerceIn(0f, 1f)
        var b = bIn.coerceIn(0f, 1f)

        // 1. Dehaze (reduces atmospheric veil while preserving midtones)
        if (patch.dehaze > 0.005f) {
            val minChannel = min(r, min(g, b))
            val hazeLift = minChannel * patch.dehaze * 0.22f
            val gain = 1f / (1f - patch.dehaze * 0.16f).coerceAtLeast(0.75f)
            r = ((r - hazeLift) * gain).coerceIn(0f, 1f)
            g = ((g - hazeLift) * gain).coerceIn(0f, 1f)
            b = ((b - hazeLift) * gain).coerceIn(0f, 1f)
        }

        // 2. Warmth & Tint fine trim
        if (abs(patch.warmth) > 0.005f || abs(patch.tint) > 0.005f) {
            r = (r + patch.warmth * 0.10f + patch.tint * 0.045f).coerceIn(0f, 1f)
            g = (g - patch.tint * 0.06f).coerceIn(0f, 1f)
            b = (b - patch.warmth * 0.10f + patch.tint * 0.045f).coerceIn(0f, 1f)
        }

        // 3. Luminance-ratio HDR Tone Style (Pixel HDR+ / Filmic / Soft Cinema / Max DR)
        val luma = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
        val mappedLuma = applyToneCurve(luma, patch.hdrToneStyle, patch.contrast)
        val lumaScale = if (luma > 0.004f) (mappedLuma / luma).coerceIn(0.68f, 1.85f) else 1f
        r = (r * lumaScale).coerceIn(0f, 1f)
        g = (g * lumaScale).coerceIn(0f, 1f)
        b = (b * lumaScale).coerceIn(0f, 1f)

        // 4. Camera Color Science Profile (split toning + selective hue response)
        val newL = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
        when (patch.colorProfile) {
            CameraColorProfile.NATURAL_TRUE -> Unit
            CameraColorProfile.LEICA_VIBRANT -> {
                val rr = (1.06f * r - 0.03f * g - 0.02f * b).coerceIn(0f, 1f)
                val gg = (-0.01f * r + 1.03f * g - 0.01f * b).coerceIn(0f, 1f)
                val bb = (-0.02f * r - 0.02f * g + 1.07f * b).coerceIn(0f, 1f)
                r = rr; g = gg; b = bb
            }
            CameraColorProfile.LEICA_AUTHENTIC -> {
                // Slightly deeper shadows + warm mid-highs + organic vignette feel
                val darkBoost = (1f - newL) * (1f - newL)
                r = (r * 1.02f - 0.012f * darkBoost).coerceIn(0f, 1f)
                g = (g * 0.99f - 0.015f * darkBoost).coerceIn(0f, 1f)
                b = (b * 0.95f - 0.010f * darkBoost).coerceIn(0f, 1f)
            }
            CameraColorProfile.HASSELBLAD_NATURAL -> {
                // Clean natural color separation + pure sky blues & gentle reds
                val rr = (1.01f * r + 0.02f * g - 0.02f * b).coerceIn(0f, 1f)
                val gg = (-0.01f * r + 1.02f * g + 0.01f * b).coerceIn(0f, 1f)
                val bb = (-0.01f * r - 0.01f * g + 1.04f * b).coerceIn(0f, 1f)
                r = rr; g = gg; b = bb
            }
            CameraColorProfile.FUJI_CLASSIC_CHROME -> {
                // Cyan-tinted sky/highlights + warm-neutral shadows + restrained chroma
                val hiW = newL * newL
                val shW = (1f - newL) * (1f - newL)
                r = (r * 0.95f + g * 0.04f - hiW * 0.015f + shW * 0.012f).coerceIn(0f, 1f)
                g = (g * 0.98f + b * 0.02f + hiW * 0.010f).coerceIn(0f, 1f)
                b = (b * 0.98f + g * 0.03f + hiW * 0.025f).coerceIn(0f, 1f)
            }
            CameraColorProfile.CINEMA_TEAL_ORANGE -> {
                val shW = (1f - newL).pow(1.6f)
                val hiW = newL.pow(1.4f)
                // Shadows toward cool teal, highlights/skin toward golden amber
                val skinMult = if (isSkin) 0.45f else 1f
                r = (r - shW * 0.045f * skinMult + hiW * 0.055f).coerceIn(0f, 1f)
                g = (g + shW * 0.015f * skinMult + hiW * 0.015f).coerceIn(0f, 1f)
                b = (b + shW * 0.055f * skinMult - hiW * 0.045f).coerceIn(0f, 1f)
            }
            CameraColorProfile.GOLDEN_PORTRA -> {
                val airyLift = (1f - newL) * 0.035f
                r = (r * 1.03f + airyLift + 0.018f).coerceIn(0f, 1f)
                g = (g * 1.01f + airyLift + 0.008f).coerceIn(0f, 1f)
                b = (b * 0.94f + airyLift).coerceIn(0f, 1f)
            }
            CameraColorProfile.MONOCHROME_NOIR -> {
                // Dramatic red-filtered B&W silver-gelatin luminance
                val mono = (0.38f * r + 0.50f * g + 0.12f * b).coerceIn(0f, 1f)
                val noirCurve = ((mono - 0.5f) * 1.18f + 0.5f).coerceIn(0f, 1f)
                r = noirCurve; g = noirCurve; b = noirCurve
            }
        }

        // 5. Saturation & Highlight/Skin-safe Vibrance
        if (patch.colorProfile != CameraColorProfile.MONOCHROME_NOIR) {
            val lumFinal = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val curSat = maxC - minC
            val highGuard = if (lumFinal > 0.84f) (1f - (lumFinal - 0.84f) / 0.16f).coerceIn(0.25f, 1f) else 1f
            val skinGuard = if (isSkin && patch.skinToneProtect) 0.30f else 1f
            val profileSatBias = when (patch.colorProfile) {
                CameraColorProfile.LEICA_VIBRANT -> 0.10f
                CameraColorProfile.FUJI_CLASSIC_CHROME -> -0.10f
                CameraColorProfile.LEICA_AUTHENTIC -> -0.04f
                else -> 0.02f
            }
            val satMult = (1f + patch.saturation + profileSatBias + patch.vibrance * (1f - curSat) * highGuard * skinGuard)
                .coerceIn(0.35f, 1.95f)
            r = (lumFinal + (r - lumFinal) * satMult).coerceIn(0f, 1f)
            g = (lumFinal + (g - lumFinal) * satMult).coerceIn(0f, 1f)
            b = (lumFinal + (b - lumFinal) * satMult).coerceIn(0f, 1f)
        }

        // 6. Optional Optical Vignette
        val vigAmount = when {
            patch.vignette > 0.01f -> patch.vignette
            patch.colorProfile == CameraColorProfile.MONOCHROME_NOIR -> 0.18f
            else -> 0f
        }
        if (vigAmount > 0.01f && normRadius > 0.35f) {
            val t = ((normRadius - 0.35f) / 0.65f).coerceIn(0f, 1f)
            val vGain = (1f - vigAmount * (t * t)).coerceIn(0.45f, 1f)
            r *= vGain; g *= vGain; b *= vGain
        }

        return (0xFF shl 24) or
            ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or
            (b * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun applyToneCurve(luma: Float, style: HdrToneStyle, userContrast: Float): Float {
        val x = luma.coerceIn(0f, 1f)
        val styled = when (style) {
            HdrToneStyle.FILMIC_BALANCED -> {
                // Smooth ACES-like shoulder + gentle shadow toe
                val lifted = x.pow(0.93f)
                (lifted * (1f + 0.14f * (1f - lifted)))
            }
            HdrToneStyle.PIXEL_PUNCH -> {
                // Classic Pixel HDR+: crisp local shadow readability + protected bright highlights + rich midtone S
                val lifted = x.pow(0.88f)
                val sCurve = lifted * lifted * (3f - 2f * lifted)
                lifted * 0.72f + sCurve * 0.28f
            }
            HdrToneStyle.SOFT_CINEMA -> {
                // Lifted blacks (0.025 floor) + soft highlight roll-off
                val compressed = x * 0.92f + 0.025f
                compressed.pow(0.95f)
            }
            HdrToneStyle.HIGH_DR -> {
                // Maximum shadow recovery & highlight compression
                val lifted = x.pow(0.82f)
                (lifted * (1f + 0.22f * (1f - lifted)))
            }
        }
        // Apply user S-curve contrast around 0.5
        val contrastGain = (1f + userContrast * 0.70f).coerceIn(0.65f, 1.50f)
        val midContrasted = 0.5f + (styled - 0.5f) * contrastGain
        return (styled * 0.35f + midContrasted * 0.65f).coerceIn(0f, 1f)
    }
}
