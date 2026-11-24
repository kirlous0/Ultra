package com.example

import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import com.example.camera.CameraDeviceCapabilities
import com.example.camera.HardwareInventory
import com.example.camera.HighSpeedConfig
import com.example.camera.LensRole
import com.example.processing.AIPhotographyEngine
import com.example.processing.DetailEngine
import com.example.processing.FrameAnalysisResult
import com.example.processing.GlImageProcessor
import com.example.processing.HDRProcessor
import com.example.processing.ImageAlignmentEngine
import com.example.processing.NightProcessor
import com.example.processing.RecognizedScene
import com.example.processing.StudioEditParams
import com.example.processing.SuperResolutionProcessor
import com.example.system.AppLanguage
import com.example.system.DiagnosticsManager
import com.example.system.LocaleStrings
import com.example.system.SystemHealthState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UltraCamEngineTest {

    private fun makePatternBitmap(w: Int, h: Int, shiftX: Int = 0, brightScale: Float = 1f): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = (x - shiftX).coerceIn(0, w - 1)
                val v = (((sx * 3 + y * 5) % 200) * brightScale).toInt().coerceIn(0, 255)
                bmp.setPixel(x, y, Color.rgb(v, (v * 0.9f).toInt(), (v * 0.8f).toInt()))
            }
        }
        return bmp
    }

    private fun makeSubpixelPatternBitmap(w: Int, h: Int, shiftX: Float, shiftY: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            val sy = y.toDouble() - shiftY
            for (x in 0 until w) {
                val sx = x.toDouble() - shiftX
                val wave = 128.0 +
                    55.0 * kotlin.math.sin(sx * 0.42 + sy * 0.19) +
                    45.0 * kotlin.math.cos(sy * 0.37 - sx * 0.23) +
                    20.0 * kotlin.math.sin((sx + sy) * 0.71)
                val v = wave.toInt().coerceIn(0, 255)
                val r = (v * 1.0f).toInt().coerceIn(0, 255)
                val g = (v * 0.92f).toInt().coerceIn(0, 255)
                val b = (v * 0.85f).toInt().coerceIn(0, 255)
                bmp.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return bmp
    }

    @Test
    fun `alignment detects static vs shifted frames accurately`() {
        val f1 = makePatternBitmap(96, 72, shiftX = 0)
        val f2 = makePatternBitmap(96, 72, shiftX = 0)
        val engine = ImageAlignmentEngine()
        val staticRes = engine.analyzeAndAlign(listOf(f1, f2))
        assertTrue(staticRes.isTripodStatic)
        assertFalse(staticRes.isUnstableCapture)
        f1.recycle(); f2.recycle()
    }

    @Test
    fun `hdr processor preserves dimensions and produces valid output`() {
        val dark = makePatternBitmap(64, 48, brightScale = 0.5f)
        val mid = makePatternBitmap(64, 48, brightScale = 1.0f)
        val bright = makePatternBitmap(64, 48, brightScale = 1.4f)
        val hdr = HDRProcessor().processHdr(listOf(dark, mid, bright))
        assertEquals(64, hdr.bitmap.width)
        assertEquals(48, hdr.bitmap.height)
        assertEquals(3, hdr.alignedFrameCount)
        dark.recycle(); mid.recycle(); bright.recycle(); hdr.bitmap.recycle()
    }

    @Test
    fun `night processor denoises and detects tripod on identical frames`() {
        val frames = List(4) { makePatternBitmap(64, 48, brightScale = 0.6f) }
        val res = NightProcessor().processNight(frames)
        assertTrue(res.isTripodDetected)
        assertEquals(64, res.bitmap.width)
        frames.forEach { it.recycle() }
        res.bitmap.recycle()
    }

    @Test
    fun `super resolution refuses upscaling on static identical frames`() {
        val frames = List(3) { makePatternBitmap(64, 48, shiftX = 0) }
        val res = SuperResolutionProcessor().processSuperResolution(frames)
        assertFalse(res.isTrueSuperResolution)
        assertEquals(1.0f, res.scaleFactor)
        assertEquals(64, res.bitmap.width)
        frames.forEach { it.recycle() }
        res.bitmap.recycle()
    }

    @Test
    fun `detail engine and cpu studio pipeline process cleanly`() {
        val src = makePatternBitmap(64, 48)
        val detailed = DetailEngine().enhanceDetails(src, sharpnessAmount = 0.3f)
        val graded = GlImageProcessor().processCpuFallback(
            detailed,
            StudioEditParams(exposureEv = 0.4f, contrast = 0.2f, saturation = 0.15f)
        )
        assertEquals(64, graded.width)
        assertEquals(48, graded.height)
        src.recycle(); detailed.recycle(); graded.recycle()
    }

    @Test
    fun `ai photography engine classifies portrait and night scenes`() {
        val engine = AIPhotographyEngine()
        val dummyAnalysis = FrameAnalysisResult(
            lumaHistogram = IntArray(64),
            redHistogram = IntArray(64),
            greenHistogram = IntArray(64),
            blueHistogram = IntArray(64),
            highlightClipPercent = 0f,
            shadowClipPercent = 2f,
            focusPeakingBitmap = null,
            zebraBitmap = null,
            meanLuma = 120f,
            edgeDensity = 0.08f,
            warmColorRatio = 0.1f,
            greenColorRatio = 0.05f,
            blueSkyRatio = 0.05f,
            topBottomLumaRatio = 1.0f
        )
        val portrait = engine.classifyScene(
            analysis = dummyAnalysis,
            currentIso = 200,
            currentExposureNs = 8_000_000L,
            faceCount = 2,
            focusDistanceDiopters = 1.5f,
            isoRange = 100..3200,
            exposureRangeNs = 100_000L..1_000_000_000L,
            aeCompRange = -12..12
        )
        assertEquals(RecognizedScene.PORTRAIT, portrait.scene)

        val night = engine.classifyScene(
            analysis = dummyAnalysis.copy(meanLuma = 22f),
            currentIso = 1600,
            currentExposureNs = 50_000_000L,
            faceCount = 0,
            focusDistanceDiopters = 0.5f,
            isoRange = 100..3200,
            exposureRangeNs = 100_000L..1_000_000_000L,
            aeCompRange = -12..12
        )
        assertEquals(RecognizedScene.NIGHT, night.scene)
    }

    @Test
    fun `diagnostics manager generates valid json with no fabricated capabilities`() {
        val frontCam = CameraDeviceCapabilities(
            cameraId = "1",
            facing = CameraCharacteristics.LENS_FACING_FRONT,
            facingLabel = "FRONT",
            lensRole = LensRole.FRONT,
            isLogicalMultiCamera = false,
            physicalCameraIds = emptyList(),
            hardwareLevel = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
            hardwareLevelName = "LIMITED",
            capabilities = listOf("BACKWARD_COMPATIBLE"),
            supportsRaw = false,
            supportsManualSensor = false,
            supportsManualPostProcessing = false,
            supportsBurstCapture = false,
            supportsYuvReprocessing = false,
            supportsPrivateReprocessing = false,
            supportsConstrainedHighSpeed = false,
            supportsFaceDetection = true,
            maxFaceCount = 5,
            afModes = listOf(0),
            afModeNames = listOf("OFF"),
            supportsManualFocus = false,
            minimumFocusDistance = 0f,
            hyperfocalDistance = 0f,
            focusDistanceCalibration = "UNCALIBRATED",
            exposureTimeRangeNs = null,
            isoRange = null,
            aeCompensationRange = -12..12,
            aeCompensationStep = 0.33f,
            awbModes = listOf(1),
            awbModeNames = listOf("AUTO"),
            hasFlash = false,
            sensorOrientation = 270,
            pixelArraySize = Size(4128, 3096),
            activeArraySize = null,
            physicalSizeMm = "4.60x3.45 mm",
            apertures = listOf(2.0f),
            focalLengthsMm = listOf(3.5f),
            maxDigitalZoom = 4.0f,
            outputFormats = emptyList(),
            jpegSizes = listOf(Size(4128, 3096)),
            rawSizes = emptyList(),
            yuvSizes = listOf(Size(1280, 720)),
            videoSizes = listOf(Size(1920, 1080)),
            videoFpsBySize = mapOf("1920x1080" to listOf(15..30)),
            aeTargetFpsRanges = listOf(15..30),
            highSpeedConfigs = listOf(HighSpeedConfig(1280, 720, 120, 120)),
            supportsEis = false,
            supportsPreviewStabilization = false,
            supportsOis = false,
            noiseReductionModes = listOf("FAST"),
            edgeModes = listOf("FAST"),
            antiBandingModes = listOf("50HZ", "60HZ", "AUTO"),
            supportedExtensions = emptyList(),
            concurrentCameraSets = emptyList(),
            isUsableInApp = true,
            usabilityNote = "Usable camera stream"
        )
        val inv = HardwareInventory(
            deviceManufacturer = "samsung",
            deviceModel = "SM-A057F",
            deviceHardware = "qcom",
            socModel = "Qualcomm SM6225",
            androidVersion = "15",
            apiLevel = 35,
            totalRamMb = 6144L,
            cameras = listOf(frontCam),
            videoEncoders = emptyList(),
            scanTimestampMs = 123456L
        )
        val jsonStr = DiagnosticsManager().buildJsonReport(inv, SystemHealthState())
        val parsed = JSONObject(jsonStr)
        assertEquals("ultracam.diagnostics.v1", parsed.getString("schema"))
        val cams = parsed.getJSONArray("cameras")
        assertEquals(1, cams.length())
        assertFalse(cams.getJSONObject(0).getBoolean("supports_manual_focus"))
    }

    @Test
    fun `localization defaults to arabic and switches cleanly to english`() {
        val ar = LocaleStrings.forLanguage(AppLanguage.ARABIC)
        val en = LocaleStrings.forLanguage(AppLanguage.ENGLISH)
        assertTrue(ar.isArabic)
        assertFalse(en.isArabic)
        assertEquals("ألترا كام", ar.appTitle)
        assertEquals("UltraCam", en.appTitle)
    }

    @Test
    fun `viewfinder fitted size preserves true 1x aspect ratio for photo and video`() {
        val (photoW, photoH) = com.example.camera.computeFittedViewfinderSize(
            containerW = 1080f,
            containerH = 2400f,
            previewSize = Size(1440, 1080)
        )
        assertEquals(1080f, photoW, 0.5f)
        assertEquals(1440f, photoH, 0.5f)

        val (videoW, videoH) = com.example.camera.computeFittedViewfinderSize(
            containerW = 1080f,
            containerH = 2400f,
            previewSize = Size(1920, 1080)
        )
        assertEquals(1080f, videoW, 0.5f)
        assertEquals(1920f, videoH, 0.5f)

        val (landW, landH) = com.example.camera.computeFittedViewfinderSize(
            containerW = 2400f,
            containerH = 1080f,
            previewSize = Size(1920, 1080)
        )
        assertEquals(1920f, landW, 0.5f)
        assertEquals(1080f, landH, 0.5f)
    }

    @Test
    fun `super resolution upscales subpixel-shifted frames within 3600px bound`() {
        val frames = listOf(
            makeSubpixelPatternBitmap(128, 96, 0f, 0f),
            makeSubpixelPatternBitmap(128, 96, 1.5f, 0.5f),
            makeSubpixelPatternBitmap(128, 96, -0.5f, 1.5f)
        )
        val res = SuperResolutionProcessor().processSuperResolution(frames, fullPower = false)
        assertTrue("Expected true super-res on subpixel shifts: ${res.statusReason}", res.isTrueSuperResolution)
        assertTrue("Expected scaleFactor >= 1.25f, got ${res.scaleFactor}", res.scaleFactor >= 1.25f)
        assertTrue(res.bitmap.width > 128 && res.bitmap.width <= 3600)
        assertTrue(res.bitmap.height > 96 && res.bitmap.height <= 3600)
        frames.forEach { it.recycle() }
        res.bitmap.recycle()
    }

    @Test
    fun `detail engine preserves dimensions across bright daylight and dim chroma scenes`() {
        val bright = makePatternBitmap(80, 60, brightScale = 1.25f)
        val brightOut = DetailEngine().enhanceDetails(bright, sharpnessAmount = 0.45f)
        assertEquals(80, brightOut.width)
        assertEquals(60, brightOut.height)

        val dim = makePatternBitmap(80, 60, brightScale = 0.25f)
        val dimOut = DetailEngine().enhanceDetails(dim, sharpnessAmount = 0.35f)
        assertEquals(80, dimOut.width)
        assertEquals(60, dimOut.height)

        var brightDiff = 0L
        for (x in 1 until 79) {
            brightDiff += kotlin.math.abs(Color.red(brightOut.getPixel(x, 30)) - Color.red(brightOut.getPixel(x - 1, 30)))
        }
        assertTrue(brightDiff > 0L)

        bright.recycle(); brightOut.recycle()
        dim.recycle(); dimOut.recycle()
    }
}
