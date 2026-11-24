package com.example

import com.example.camera.CameraDeviceCapabilities
import com.example.camera.CaptureFormat
import com.example.camera.FlashMode
import com.example.camera.LensRole
import com.example.camera.ProControlsState
import com.example.camera.WhiteBalancePreset
import com.example.camera.clampProControlsToCamera
import com.example.processing.AIPhotographyEngine
import com.example.processing.EnhanceConfig
import com.example.processing.FrameAnalysisResult
import com.example.processing.ParallelCompute
import com.example.processing.RecognizedScene
import com.example.processing.StudioEditParams
import com.example.processing.YuvUtils
import com.example.system.AppLanguage
import com.example.system.LocaleStrings
import com.example.ui.components.formatShutterNs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `laplacian sharpness distinguishes sharp edges from flat surface`() {
        val w = 32
        val h = 32
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        val sharp = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if ((x + y) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        val flatScore = YuvUtils.computeLaplacianSharpness(flat, w, h)
        val sharpScore = YuvUtils.computeLaplacianSharpness(sharp, w, h)
        assertEquals(0.0, flatScore, 0.01)
        assertTrue(sharpScore > 100.0)
    }

    @Test
    fun `ai engine classifies portrait, night, document, and clamps hardware ranges`() {
        val engine = AIPhotographyEngine()
        val base = FrameAnalysisResult(
            lumaHistogram = IntArray(64),
            redHistogram = IntArray(64),
            greenHistogram = IntArray(64),
            blueHistogram = IntArray(64),
            highlightClipPercent = 0f,
            shadowClipPercent = 1f,
            focusPeakingBitmap = null,
            zebraBitmap = null,
            meanLuma = 120f,
            edgeDensity = 0.06f,
            warmColorRatio = 0.1f,
            greenColorRatio = 0.05f,
            blueSkyRatio = 0.05f,
            topBottomLumaRatio = 1.0f
        )

        val portrait = engine.classifyScene(
            analysis = base,
            currentIso = 200,
            currentExposureNs = 8_000_000L,
            faceCount = 2,
            focusDistanceDiopters = 1.5f,
            isoRange = 100..1600,
            exposureRangeNs = 100_000L..500_000_000L,
            aeCompRange = -6..6
        )
        assertEquals(RecognizedScene.PORTRAIT, portrait.scene)

        val night = engine.classifyScene(
            analysis = base.copy(meanLuma = 18f),
            currentIso = 3200,
            currentExposureNs = 60_000_000L,
            faceCount = 0,
            focusDistanceDiopters = 0.3f,
            isoRange = 100..640, // tighter sensor limit
            exposureRangeNs = 100_000L..10_000_000L,
            aeCompRange = -6..6
        )
        assertEquals(RecognizedScene.NIGHT, night.scene)
        assertEquals(640, night.recommendedIso)
        assertEquals(10_000_000L, night.recommendedShutterNs)

        val doc = engine.classifyScene(
            analysis = base.copy(meanLuma = 165f, edgeDensity = 0.25f, warmColorRatio = 0.05f),
            currentIso = 160,
            currentExposureNs = 10_000_000L,
            faceCount = 0,
            focusDistanceDiopters = 4.5f,
            isoRange = 100..3200,
            exposureRangeNs = 100_000L..1_000_000_000L,
            aeCompRange = -12..12
        )
        assertEquals(RecognizedScene.DOCUMENT, doc.scene)
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
    fun `shutter speed formatting and studio identity checks work`() {
        assertEquals("1/1000s", formatShutterNs(1_000_000L))
        assertEquals("1/60s", formatShutterNs(16_666_666L))
        assertEquals("1.0s", formatShutterNs(1_000_000_000L))
        assertTrue(StudioEditParams().isIdentity)
        assertFalse(StudioEditParams(exposureEv = 0.5f).isIdentity)
    }

    @Test
    fun `clampProControlsToCamera strips manual focus, afLock, flash, and RAW on fixed-focus front camera`() {
        val frontFixedFocusCam = CameraDeviceCapabilities(
            cameraId = "1",
            facing = 0,
            facingLabel = "FRONT",
            lensRole = LensRole.FRONT,
            isLogicalMultiCamera = false,
            physicalCameraIds = emptyList(),
            hardwareLevel = 1,
            hardwareLevelName = "FULL",
            capabilities = listOf("BACKWARD_COMPATIBLE", "MANUAL_SENSOR"),
            supportsRaw = false,
            supportsManualSensor = true,
            supportsManualPostProcessing = false,
            supportsBurstCapture = true,
            supportsYuvReprocessing = false,
            supportsPrivateReprocessing = false,
            supportsConstrainedHighSpeed = false,
            supportsFaceDetection = true,
            maxFaceCount = 10,
            afModes = listOf(0),
            afModeNames = listOf("OFF"),
            supportsManualFocus = false,
            minimumFocusDistance = 0f,
            hyperfocalDistance = 0f,
            focusDistanceCalibration = "UNCALIBRATED",
            exposureTimeRangeNs = 100_000L..250_000_000L,
            isoRange = 50..1600,
            aeCompensationRange = -6..6,
            aeCompensationStep = 0.5f,
            awbModes = listOf(1, 5),
            awbModeNames = listOf("AUTO", "DAYLIGHT"),
            hasFlash = false,
            sensorOrientation = 270,
            pixelArraySize = null,
            activeArraySize = null,
            physicalSizeMm = null,
            apertures = listOf(2.0f),
            focalLengthsMm = listOf(3.4f),
            maxDigitalZoom = 4.0f,
            outputFormats = emptyList(),
            jpegSizes = emptyList(),
            rawSizes = emptyList(),
            yuvSizes = emptyList(),
            videoSizes = emptyList(),
            videoFpsBySize = emptyMap(),
            aeTargetFpsRanges = emptyList(),
            highSpeedConfigs = emptyList(),
            supportsEis = false,
            supportsPreviewStabilization = false,
            supportsOis = false,
            supportsAeLock = true,
            supportsAwbLock = true,
            colorFilterArrangement = "RGGB",
            noiseReductionModeIds = listOf(0, 2),
            noiseReductionModes = listOf("OFF", "HIGH_QUALITY"),
            edgeModeIds = listOf(0, 2),
            edgeModes = listOf("OFF", "HIGH_QUALITY"),
            antiBandingModes = listOf("50HZ", "60HZ"),
            supportedExtensions = emptyList(),
            concurrentCameraSets = emptyList(),
            isUsableInApp = true,
            usabilityNote = "Usable"
        )

        val aggressiveInput = ProControlsState(
            isManualMode = true,
            manualExposureEnabled = true,
            iso = 6400,
            exposureTimeNs = 2_000_000_000L,
            exposureCompensationSteps = 20,
            manualFocusEnabled = true,
            focusDistanceDiopters = 8.0f,
            whiteBalancePreset = WhiteBalancePreset.MANUAL_KELVIN,
            aeLocked = true,
            afLocked = true,
            flashMode = FlashMode.TORCH,
            captureFormat = CaptureFormat.RAW_PLUS_JPEG,
            digitalZoomRatio = 10.0f
        )

        val clamped = clampProControlsToCamera(aggressiveInput, frontFixedFocusCam)
        assertFalse(clamped.manualFocusEnabled)
        assertFalse(clamped.afLocked)
        assertEquals(0f, clamped.focusDistanceDiopters, 0.001f)
        assertEquals(FlashMode.OFF, clamped.flashMode)
        assertEquals(CaptureFormat.JPEG, clamped.captureFormat)
        assertEquals(WhiteBalancePreset.AUTO, clamped.whiteBalancePreset)
        assertEquals(1600, clamped.iso)
        assertEquals(250_000_000L, clamped.exposureTimeNs)
        assertEquals(6, clamped.exposureCompensationSteps)
        assertEquals(4.0f, clamped.digitalZoomRatio, 0.001f)
        assertTrue(clamped.aeLocked)
    }

    @Test
    fun `parallel compute visits every row exactly once in standard and full power modes`() {
        val height = 257
        val countsStd = IntArray(height)
        ParallelCompute.parallelRows(height, fullPower = false) { startY, endY ->
            for (y in startY until endY) {
                countsStd[y] += 1
            }
        }
        assertTrue(countsStd.all { it == 1 })

        val countsFull = IntArray(height)
        ParallelCompute.parallelRows(height, fullPower = true) { startY, endY ->
            for (y in startY until endY) {
                countsFull[y] += 1
            }
        }
        assertTrue(countsFull.all { it == 1 })
    }

    @Test
    fun `ai engine fullPower profile boosts detail clarity vibrance and video bitrate safely`() {
        val engine = AIPhotographyEngine()
        val hist = IntArray(64) { idx -> if (idx in 8..56) 20 else 0 }
        val analysis = FrameAnalysisResult(
            lumaHistogram = hist,
            redHistogram = hist,
            greenHistogram = hist,
            blueHistogram = hist,
            highlightClipPercent = 2.0f,
            shadowClipPercent = 1.5f,
            focusPeakingBitmap = null,
            zebraBitmap = null,
            meanLuma = 128f,
            edgeDensity = 0.16f,
            warmColorRatio = 0.12f,
            greenColorRatio = 0.25f,
            blueSkyRatio = 0.30f,
            topBottomLumaRatio = 1.1f
        )

        val standard = engine.classifyScene(
            analysis = analysis,
            currentIso = 100,
            currentExposureNs = 4_000_000L,
            faceCount = 0,
            focusDistanceDiopters = 0.4f,
            isoRange = 50..1600,
            exposureRangeNs = 100_000L..500_000_000L,
            aeCompRange = -6..6,
            fullPower = false
        )
        val turbo = engine.classifyScene(
            analysis = analysis,
            currentIso = 100,
            currentExposureNs = 4_000_000L,
            faceCount = 0,
            focusDistanceDiopters = 0.4f,
            isoRange = 50..1600,
            exposureRangeNs = 100_000L..500_000_000L,
            aeCompRange = -6..6,
            fullPower = true
        )

        assertEquals(RecognizedScene.LANDSCAPE, turbo.scene)
        assertTrue(turbo.profile.sharpnessAmount > standard.profile.sharpnessAmount)
        assertTrue(turbo.profile.clarityAmount > standard.profile.clarityAmount)
        assertTrue(turbo.profile.vibranceBoost > standard.profile.vibranceBoost)
        assertTrue(turbo.profile.videoBitrateMultiplier > standard.profile.videoBitrateMultiplier)
        assertTrue(turbo.profile.sharpnessAmount <= 0.72f)
        assertTrue(turbo.profile.videoBitrateMultiplier <= 1.65f)
        assertTrue(EnhanceConfig().fullPower.not())
    }

    @Test
    fun `color profile engine gradePixel produces valid bounded ARGB and monochrome noir grayscale`() {
        val patch = com.example.processing.LibPatcherConfig.PRESET_GCAM_PUNCH
        for (profile in com.example.processing.CameraColorProfile.entries) {
            for (tone in com.example.processing.HdrToneStyle.entries) {
                val p = patch.copy(colorProfile = profile, hdrToneStyle = tone)
                val argb = com.example.processing.ColorProfileEngine.gradePixel(
                    rIn = 0.62f,
                    gIn = 0.44f,
                    bIn = 0.31f,
                    normRadius = 0.55f,
                    patch = p,
                    isSkin = false
                )
                val a = (argb ushr 24) and 0xFF
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                assertEquals(0xFF, a)
                assertTrue(r in 0..255 && g in 0..255 && b in 0..255)
                if (profile == com.example.processing.CameraColorProfile.MONOCHROME_NOIR) {
                    assertEquals(r, g)
                    assertEquals(g, b)
                }
            }
        }
    }

    @Test
    fun `skin tone protect restrains excessive vibrance boost on skin pixels`() {
        val base = com.example.processing.LibPatcherConfig(
            colorProfile = com.example.processing.CameraColorProfile.LEICA_VIBRANT,
            vibrance = 0.50f,
            saturation = 0.0f
        )
        val protectedArgb = com.example.processing.ColorProfileEngine.gradePixel(
            rIn = 0.72f, gIn = 0.52f, bIn = 0.42f,
            normRadius = 0.2f,
            patch = base.copy(skinToneProtect = true),
            isSkin = true
        )
        val unprotectedArgb = com.example.processing.ColorProfileEngine.gradePixel(
            rIn = 0.72f, gIn = 0.52f, bIn = 0.42f,
            normRadius = 0.2f,
            patch = base.copy(skinToneProtect = false),
            isSkin = true
        )
        val pSpread = ((protectedArgb ushr 16) and 0xFF) - (protectedArgb and 0xFF)
        val uSpread = ((unprotectedArgb ushr 16) and 0xFF) - (unprotectedArgb and 0xFF)
        assertTrue(pSpread <= uSpread)
    }

    @Test
    fun `camera lab presets and new localization keys are configured cleanly`() {
        assertEquals(
            com.example.processing.CameraColorProfile.LEICA_VIBRANT,
            com.example.processing.LibPatcherConfig.PRESET_GCAM_PUNCH.colorProfile
        )
        assertEquals(
            com.example.processing.CameraColorProfile.LEICA_AUTHENTIC,
            com.example.processing.LibPatcherConfig.PRESET_LEICA_CLASSIC.colorProfile
        )
        assertEquals(
            com.example.processing.CameraColorProfile.HASSELBLAD_NATURAL,
            com.example.processing.LibPatcherConfig.PRESET_NIGHT_CRISP.colorProfile
        )
        assertEquals(
            com.example.processing.CameraColorProfile.CINEMA_TEAL_ORANGE,
            com.example.processing.LibPatcherConfig.PRESET_CINEMA_FILM.colorProfile
        )

        val ar = LocaleStrings.forLanguage(AppLanguage.ARABIC)
        val en = LocaleStrings.forLanguage(AppLanguage.ENGLISH)
        assertTrue(ar.astroLabel.isNotBlank() && en.astroLabel.isNotBlank())
        assertTrue(ar.cameraLabTitle.isNotBlank() && en.cameraLabTitle.isNotBlank())
        assertTrue(ar.watermarkFrameLabel.isNotBlank() && en.watermarkFrameLabel.isNotBlank())
        assertTrue(ar.studioLightTab.isNotBlank() && en.studioLightTab.isNotBlank())
        assertTrue(ar.studioColorTab.isNotBlank() && en.studioColorTab.isNotBlank())
        assertTrue(ar.studioDetailTab.isNotBlank() && en.studioDetailTab.isNotBlank())
        assertTrue(ar.rotate90Label.isNotBlank() && en.rotate90Label.isNotBlank())
        assertTrue(ar.cancelLabel.isNotBlank() && en.cancelLabel.isNotBlank())
        assertTrue(ar.deleteActionLabel.isNotBlank() && en.deleteActionLabel.isNotBlank())
        assertTrue(ar.superResToggleLabel.isNotBlank() && en.superResToggleLabel.isNotBlank())
        assertTrue(ar.playVideoLabel.isNotBlank() && en.playVideoLabel.isNotBlank())
        assertTrue(ar.quickMenuLabel.isNotBlank() && en.quickMenuLabel.isNotBlank())
        assertTrue(ar.stitchLabel.isNotBlank() && en.stitchLabel.isNotBlank())
        assertTrue(ar.stopLabel.isNotBlank() && en.stopLabel.isNotBlank())
    }

    @Test
    fun `exif processor computes 35mm equivalent focal length, rationals, flash bitmask, and scene type`() {
        val eq = com.example.processing.ExifProcessor.compute35mmEquivalentFocalLength(4.21f, "5.60x4.20 mm")
        assertEquals(26, eq)
        assertEquals(null, com.example.processing.ExifProcessor.compute35mmEquivalentFocalLength(null, "5.60x4.20 mm"))
        assertEquals(null, com.example.processing.ExifProcessor.compute35mmEquivalentFocalLength(4.2f, "invalid"))

        assertEquals(1 to 4, com.example.processing.ExifProcessor.rationalPair(0.25, 100))
        assertEquals(0 to 1, com.example.processing.ExifProcessor.rationalPair(0.0, 100))
        assertEquals("7/2", com.example.processing.ExifProcessor.toUnsignedRational(3.5, 100))
        assertEquals("-3/2", com.example.processing.ExifProcessor.toSignedRational(-1.5, 100))

        assertEquals(2, com.example.processing.ExifProcessor.computeSceneCaptureType("PORTRAIT", null))
        assertEquals(3, com.example.processing.ExifProcessor.computeSceneCaptureType("NIGHT", null))
        assertEquals(3, com.example.processing.ExifProcessor.computeSceneCaptureType("ASTRO", null))
        assertEquals(1, com.example.processing.ExifProcessor.computeSceneCaptureType("PANORAMA", null))
        assertEquals(0, com.example.processing.ExifProcessor.computeSceneCaptureType("PRO", "FOOD"))

        val payload = com.example.processing.CaptureExifPayload(
            deviceMake = "Samsung",
            deviceModel = "SM-A057F",
            software = "UltraCam 1.0 · VIVID",
            lensMake = "Samsung",
            lensModel = "Rear Main 4.21mm f/1.8 (50MP · ID 0)",
            cameraId = "0",
            lensRole = LensRole.MAIN,
            hardwareLevelName = "LEVEL_3",
            imageWidth = 4080,
            imageHeight = 3060,
            exifOrientation = 1,
            iso = 200,
            exposureTimeNs = 8_000_000L,
            aperture = 1.8f,
            focalLengthMm = 4.21f,
            focalLength35mm = 26,
            focusDistanceDiopters = 2.0f,
            exposureBiasEv = -0.5f,
            isManualMode = true,
            manualExposureEnabled = true,
            manualFocusEnabled = true,
            aeLocked = false,
            afLocked = true,
            whiteBalancePreset = WhiteBalancePreset.MANUAL_KELVIN,
            manualKelvin = 5200,
            flashMode = FlashMode.ON,
            flashFired = true,
            hasFlash = true,
            digitalZoomRatio = 1.5f,
            modeTag = "PRO",
            frameCount = 1,
            focusPowerMode = true,
            aiSceneName = "LANDSCAPE",
            libPatcher = com.example.processing.LibPatcherConfig.PRESET_GCAM_PUNCH,
            tapMetered = true,
            captureTimestampMs = 1700000000000L,
            location = null,
            uniqueId = "AABBCCDD11223344"
        )
        assertEquals(9, com.example.processing.ExifProcessor.computeFlashExifBitmask(payload))
        assertEquals(32, com.example.processing.ExifProcessor.computeFlashExifBitmask(payload.copy(hasFlash = false)))
        assertEquals(16, com.example.processing.ExifProcessor.computeFlashExifBitmask(payload.copy(flashFired = false, flashMode = FlashMode.OFF)))
    }

    @Test
    fun `patchDngBytes non-destructively enriches LE and BE TIFF headers while preserving StripOffsets and ascending tag order`() {
        val payload = com.example.processing.CaptureExifPayload(
            deviceMake = "Samsung",
            deviceModel = "SM-A057F",
            software = "UltraCam 1.0",
            lensMake = "Samsung",
            lensModel = "Rear Main 4.21mm f/1.8",
            cameraId = "0",
            lensRole = LensRole.MAIN,
            hardwareLevelName = "LEVEL_3",
            imageWidth = 4080,
            imageHeight = 3060,
            exifOrientation = 1,
            iso = 320,
            exposureTimeNs = 10_000_000L,
            aperture = 1.8f,
            focalLengthMm = 4.21f,
            focalLength35mm = 26,
            focusDistanceDiopters = 1.25f,
            exposureBiasEv = 0.0f,
            isManualMode = true,
            manualExposureEnabled = true,
            manualFocusEnabled = true,
            aeLocked = true,
            afLocked = false,
            whiteBalancePreset = WhiteBalancePreset.DAYLIGHT,
            manualKelvin = 5500,
            flashMode = FlashMode.OFF,
            flashFired = false,
            hasFlash = true,
            digitalZoomRatio = 1.0f,
            modeTag = "PRO_RAW",
            frameCount = 1,
            focusPowerMode = true,
            aiSceneName = null,
            libPatcher = com.example.processing.LibPatcherConfig.PRESET_GCAM_PUNCH,
            tapMetered = false,
            captureTimestampMs = 1700000000000L,
            location = null,
            uniqueId = "1234567890ABCDEF"
        )

        // Invalid bytes are returned untouched
        val invalid = byteArrayOf(1, 2, 3, 4)
        assertEquals(4, com.example.processing.ExifProcessor.patchDngBytes(invalid, payload).size)

        for (order in listOf(java.nio.ByteOrder.LITTLE_ENDIAN, java.nio.ByteOrder.BIG_ENDIAN)) {
            // Minimal valid synthetic TIFF with 1 IFD0 entry: StripOffsets (0x0111, LONG, 1, 1024) at offset 8
            val raw = ByteArray(128)
            val buf = java.nio.ByteBuffer.wrap(raw).order(order)
            if (order == java.nio.ByteOrder.LITTLE_ENDIAN) {
                raw[0] = 'I'.code.toByte(); raw[1] = 'I'.code.toByte()
            } else {
                raw[0] = 'M'.code.toByte(); raw[1] = 'M'.code.toByte()
            }
            buf.putShort(2, 42.toShort())
            buf.putInt(4, 8) // IFD0 offset
            buf.putShort(8, 1.toShort()) // 1 entry
            buf.putShort(10, 0x0111.toShort()) // StripOffsets
            buf.putShort(12, 4.toShort()) // LONG
            buf.putInt(14, 1) // count
            buf.putInt(18, 1024) // preserved raw offset value
            buf.putInt(22, 0) // next IFD = 0

            val patched = com.example.processing.ExifProcessor.patchDngBytes(raw, payload)
            assertTrue(patched.size > raw.size)

            val outBuf = java.nio.ByteBuffer.wrap(patched).order(order)
            val newIfd0Offset = outBuf.getInt(4)
            assertTrue(newIfd0Offset >= raw.size)

            val ifd0Count = outBuf.getShort(newIfd0Offset).toInt() and 0xFFFF
            assertTrue(ifd0Count >= 6)
            var prevTag = -1
            var foundStripOffsetsVal: Int? = null
            var exifIfdPointer: Int? = null
            for (i in 0 until ifd0Count) {
                val off = newIfd0Offset + 2 + i * 12
                val tag = outBuf.getShort(off).toInt() and 0xFFFF
                assertTrue("IFD0 tags must be ascending", tag > prevTag)
                prevTag = tag
                if (tag == 0x0111) foundStripOffsetsVal = outBuf.getInt(off + 8)
                if (tag == 0x8769) exifIfdPointer = outBuf.getInt(off + 8)
            }
            assertEquals(1024, foundStripOffsetsVal)
            assertTrue(exifIfdPointer != null && exifIfdPointer >= raw.size)

            // Validate ExifIFD ascending tag order and expected ISO / ExposureMode tags
            val exifPos = exifIfdPointer!!
            val exifCount = outBuf.getShort(exifPos).toInt() and 0xFFFF
            assertTrue(exifCount >= 10)
            var prevExifTag = -1
            var isoFound = false
            var expModeFound = false
            for (i in 0 until exifCount) {
                val off = exifPos + 2 + i * 12
                val tag = outBuf.getShort(off).toInt() and 0xFFFF
                assertTrue("ExifIFD tags must be ascending", tag > prevExifTag)
                prevExifTag = tag
                if (tag == 0x8827) { // ISO
                    isoFound = true
                    assertEquals(320, outBuf.getShort(off + 8).toInt() and 0xFFFF)
                }
                if (tag == 0xA402) { // ExposureMode (1 = Manual)
                    expModeFound = true
                    assertEquals(1, outBuf.getShort(off + 8).toInt() and 0xFFFF)
                }
            }
            assertTrue(isoFound)
            assertTrue(expModeFound)
        }
    }

    @Test
    fun `histogram exposure stats detect balanced midtones and per-channel RGB and shadow clipping`() {
        val midtoneHist = IntArray(64) { idx -> if (idx in 20..44) 100 else 0 }
        val balanced = com.example.camera.LiveTelemetry(
            lumaHistogram = midtoneHist,
            redHistogram = midtoneHist,
            greenHistogram = midtoneHist,
            blueHistogram = midtoneHist,
            highlightClippingPercent = 0.4f,
            shadowClippingPercent = 1.2f,
            redHighlightClipPercent = 0.2f,
            greenHighlightClipPercent = 0.1f,
            blueHighlightClipPercent = 0.3f
        )
        val balancedStats = com.example.ui.components.computeHistogramExposureStats(balanced)
        assertFalse(balancedStats.redClipping)
        assertFalse(balancedStats.greenClipping)
        assertFalse(balancedStats.blueClipping)
        assertFalse(balancedStats.shadowClipping)
        assertFalse(balancedStats.highlightClipping)

        // Detect via explicit clip percentages & shadow crush
        val explicitClip = balanced.copy(
            redHighlightClipPercent = 3.0f,
            blueHighlightClipPercent = 2.6f,
            highlightClippingPercent = 3.1f,
            shadowClippingPercent = 6.2f
        )
        val explicitStats = com.example.ui.components.computeHistogramExposureStats(explicitClip)
        assertTrue(explicitStats.redClipping)
        assertFalse(explicitStats.greenClipping)
        assertTrue(explicitStats.blueClipping)
        assertTrue(explicitStats.shadowClipping)
        assertTrue(explicitStats.highlightClipping)

        // Detect via top histogram bins (bins 62 + 63 >= 2.5%) even when explicit percent is 0
        val spikedGreen = IntArray(64) { idx -> if (idx == 63) 10 else if (idx == 30) 90 else 0 }
        val spikeStats = com.example.ui.components.computeHistogramExposureStats(
            balanced.copy(greenHistogram = spikedGreen)
        )
        assertTrue(spikeStats.greenClipping)
        assertFalse(spikeStats.redClipping)
    }

    @Test
    fun `pro burst controls clamp frame counts, respect JPEG format, and expose localized strings`() {
        val rearCam = CameraDeviceCapabilities(
            cameraId = "0",
            facing = 1,
            facingLabel = "BACK",
            lensRole = LensRole.MAIN,
            isLogicalMultiCamera = false,
            physicalCameraIds = emptyList(),
            hardwareLevel = 1,
            hardwareLevelName = "FULL",
            capabilities = listOf("BACKWARD_COMPATIBLE", "RAW", "BURST_CAPTURE"),
            supportsRaw = true,
            supportsManualSensor = true,
            supportsManualPostProcessing = true,
            supportsBurstCapture = true,
            supportsYuvReprocessing = false,
            supportsPrivateReprocessing = false,
            supportsConstrainedHighSpeed = false,
            supportsFaceDetection = true,
            maxFaceCount = 10,
            afModes = listOf(1, 4),
            afModeNames = listOf("AUTO", "CONTINUOUS_PICTURE"),
            supportsManualFocus = true,
            minimumFocusDistance = 10f,
            hyperfocalDistance = 0.5f,
            focusDistanceCalibration = "APPROXIMATE",
            exposureTimeRangeNs = 100_000L..1_000_000_000L,
            isoRange = 50..3200,
            aeCompensationRange = -6..6,
            aeCompensationStep = 0.5f,
            awbModes = listOf(1),
            awbModeNames = listOf("AUTO"),
            hasFlash = true,
            sensorOrientation = 90,
            pixelArraySize = null,
            activeArraySize = null,
            physicalSizeMm = null,
            apertures = listOf(1.8f),
            focalLengthsMm = listOf(4.2f),
            maxDigitalZoom = 8f,
            outputFormats = emptyList(),
            jpegSizes = emptyList(),
            rawSizes = emptyList(),
            yuvSizes = emptyList(),
            videoSizes = emptyList(),
            videoFpsBySize = emptyMap(),
            aeTargetFpsRanges = emptyList(),
            highSpeedConfigs = emptyList(),
            supportsEis = true,
            supportsPreviewStabilization = false,
            supportsOis = false,
            noiseReductionModes = emptyList(),
            edgeModes = emptyList(),
            antiBandingModes = emptyList(),
            supportedExtensions = emptyList(),
            concurrentCameraSets = emptyList(),
            isUsableInApp = true,
            usabilityNote = "Usable"
        )

        val jpegBurst = clampProControlsToCamera(
            ProControlsState(
                captureFormat = CaptureFormat.JPEG,
                burstEnabledInPro = true,
                burstFrameCount = 99
            ),
            rearCam
        )
        assertTrue(jpegBurst.burstEnabledInPro)
        assertEquals(12, jpegBurst.burstFrameCount)

        val rawClearsBurst = clampProControlsToCamera(
            ProControlsState(
                captureFormat = CaptureFormat.RAW_DNG,
                burstEnabledInPro = true,
                burstFrameCount = 1
            ),
            rearCam
        )
        assertFalse(rawClearsBurst.burstEnabledInPro)
        assertEquals(3, rawClearsBurst.burstFrameCount)

        val progress = com.example.camera.BurstCaptureProgress(
            capturedCount = 4,
            targetCount = 10,
            achievedFps = 18.5f,
            isContinuousHold = true
        )
        assertEquals(4, progress.capturedCount)
        assertEquals(10, progress.targetCount)
        assertTrue(progress.isContinuousHold)

        val ar = LocaleStrings.forLanguage(AppLanguage.ARABIC)
        val en = LocaleStrings.forLanguage(AppLanguage.ENGLISH)
        assertTrue(ar.burstDriveLabel.isNotBlank() && en.burstDriveLabel.isNotBlank())
        assertTrue(ar.singleDriveLabel.isNotBlank() && en.singleDriveLabel.isNotBlank())
        assertTrue(ar.continuousBurstLabel.isNotBlank() && en.continuousBurstLabel.isNotBlank())
        assertTrue(ar.burstFramesLabel.isNotBlank() && en.burstFramesLabel.isNotBlank())
        assertTrue(ar.holdForBurstHint.isNotBlank() && en.holdForBurstHint.isNotBlank())
        assertTrue(ar.burstHardwareSupported.isNotBlank() && en.burstHardwareSupported.isNotBlank())
        assertTrue(ar.burstSoftwareFallback.isNotBlank() && en.burstSoftwareFallback.isNotBlank())
        assertTrue(ar.rgbOverlayLabel.isNotBlank() && en.rgbOverlayLabel.isNotBlank())
        assertTrue(ar.lumaOverlayLabel.isNotBlank() && en.lumaOverlayLabel.isNotBlank())
    }

    @Test
    fun `focus peaking edge response and display coordinate mapping respect thresholds and orientation`() {
        val flatResp = YuvUtils.computeFocusEdgeResponse(120, 120, 120, 120, 120)
        val softEdgeResp = YuvUtils.computeFocusEdgeResponse(120, 108, 132, 120, 120) // sobel = 24
        val crispEdgeResp = YuvUtils.computeFocusEdgeResponse(200, 100, 100, 100, 100) // lap = 400 -> 300

        assertEquals(0, flatResp)
        assertTrue(softEdgeResp >= com.example.camera.FocusPeakingSensitivity.HIGH.threshold)
        assertTrue(softEdgeResp < com.example.camera.FocusPeakingSensitivity.MEDIUM.threshold)
        assertTrue(crispEdgeResp >= com.example.camera.FocusPeakingSensitivity.LOW.threshold)

        // Colors carry opaque high-contrast alpha
        for (color in com.example.camera.FocusPeakingColor.entries) {
            assertEquals(0xFF, (color.argb ushr 24) and 0xFF)
        }

        // Sensor 200x150 rotated 90 deg -> portrait 150x200
        val (rx90, ry90) = YuvUtils.mapOverlayCoord(
            gx = 10, gy = 5, gridW = 200, gridH = 150,
            displayRotationDeg = 90, mirrorHorizontal = false
        )
        assertEquals(150 - 1 - 5, rx90)
        assertEquals(10, ry90)

        // Front 270 deg + horizontal mirror flips X cleanly within 150-wide display grid
        val (rx270Unmirrored, ry270) = YuvUtils.mapOverlayCoord(
            gx = 10, gy = 5, gridW = 200, gridH = 150,
            displayRotationDeg = 270, mirrorHorizontal = false
        )
        val (rx270Mirrored, ry270Mirrored) = YuvUtils.mapOverlayCoord(
            gx = 10, gy = 5, gridW = 200, gridH = 150,
            displayRotationDeg = 270, mirrorHorizontal = true
        )
        assertEquals(5, rx270Unmirrored)
        assertEquals(150 - 1 - 5, rx270Mirrored)
        assertEquals(200 - 1 - 10, ry270)
        assertEquals(ry270, ry270Mirrored)
    }

    @Test
    fun `ml kit scene labels fuse cleanly into scene presets and mergeAiPresetToPatcher respects toggle`() {
        val engine = AIPhotographyEngine()
        assertEquals(RecognizedScene.PORTRAIT, engine.mapMlKitLabelToScene("Person"))
        assertEquals(RecognizedScene.FOOD, engine.mapMlKitLabelToScene("Cuisine"))
        assertEquals(RecognizedScene.LANDSCAPE, engine.mapMlKitLabelToScene("Mountain"))
        assertEquals(RecognizedScene.NIGHT, engine.mapMlKitLabelToScene("Night"))
        assertEquals(RecognizedScene.DOCUMENT, engine.mapMlKitLabelToScene("Receipt"))

        val neutralAnalysis = FrameAnalysisResult(
            lumaHistogram = IntArray(64) { if (it in 16..48) 30 else 0 },
            redHistogram = IntArray(64),
            greenHistogram = IntArray(64),
            blueHistogram = IntArray(64),
            highlightClipPercent = 0.5f,
            shadowClipPercent = 0.5f,
            focusPeakingBitmap = null,
            zebraBitmap = null,
            meanLuma = 128f,
            edgeDensity = 0.08f,
            warmColorRatio = 0.14f,
            greenColorRatio = 0.08f,
            blueSkyRatio = 0.08f,
            topBottomLumaRatio = 1.0f
        )

        val foodRec = engine.classifyScene(
            analysis = neutralAnalysis,
            currentIso = 200,
            currentExposureNs = 8_000_000L,
            faceCount = 0,
            focusDistanceDiopters = 2.0f,
            isoRange = 50..3200,
            exposureRangeNs = 100_000L..500_000_000L,
            aeCompRange = -6..6,
            mlKitLabels = listOf(com.example.processing.SceneLabelObservation("Dish", 0.91f))
        )
        assertEquals(RecognizedScene.FOOD, foodRec.scene)
        assertEquals("Dish", foodRec.mlKitLabel)
        assertTrue(foodRec.profile.saturationAdjust > 0.10f)
        assertTrue(foodRec.profile.warmthAdjust > 0f)

        val landscapeRec = engine.classifyScene(
            analysis = neutralAnalysis,
            currentIso = 100,
            currentExposureNs = 4_000_000L,
            faceCount = 0,
            focusDistanceDiopters = 0.2f,
            isoRange = 50..3200,
            exposureRangeNs = 100_000L..500_000_000L,
            aeCompRange = -6..6,
            mlKitLabels = listOf(com.example.processing.SceneLabelObservation("Mountain", 0.88f))
        )
        assertEquals(RecognizedScene.LANDSCAPE, landscapeRec.scene)
        assertEquals(WhiteBalancePreset.DAYLIGHT, landscapeRec.recommendedWb)
        assertTrue(landscapeRec.profile.contrastAdjust > 0.08f)

        // Dim scene override protects NIGHT even if ML Kit returns a misleading bright-scene tag
        val dimRec = engine.classifyScene(
            analysis = neutralAnalysis.copy(meanLuma = 28f),
            currentIso = 1600,
            currentExposureNs = 45_000_000L,
            faceCount = 0,
            focusDistanceDiopters = 0.3f,
            isoRange = 50..3200,
            exposureRangeNs = 100_000L..500_000_000L,
            aeCompRange = -6..6,
            mlKitLabels = listOf(com.example.processing.SceneLabelObservation("Building", 0.56f))
        )
        assertEquals(RecognizedScene.NIGHT, dimRec.scene)

        val basePatch = com.example.processing.LibPatcherConfig()
        val untouched = engine.mergeAiPresetToPatcher(basePatch, foodRec, aiAutoAdjustEnabled = false)
        assertEquals(basePatch, untouched)

        val autoMerged = engine.mergeAiPresetToPatcher(basePatch, foodRec, aiAutoAdjustEnabled = true)
        assertTrue(autoMerged.saturation > basePatch.saturation)
        assertTrue(autoMerged.contrast > basePatch.contrast)
        assertTrue(autoMerged.warmth > basePatch.warmth)

        val ar = LocaleStrings.forLanguage(AppLanguage.ARABIC)
        val en = LocaleStrings.forLanguage(AppLanguage.ENGLISH)
        assertTrue(ar.focusPeakingColorLabel.isNotBlank() && en.focusPeakingColorLabel.isNotBlank())
        assertTrue(ar.focusPeakingSensitivityLabel.isNotBlank() && en.focusPeakingSensitivityLabel.isNotBlank())
        assertTrue(ar.focusSharpnessPeakLabel.isNotBlank() && en.focusSharpnessPeakLabel.isNotBlank())
        assertTrue(ar.aiAutoAdjustLabel.isNotBlank() && en.aiAutoAdjustLabel.isNotBlank())
        assertTrue(ar.aiAutoAdjustDesc.isNotBlank() && en.aiAutoAdjustDesc.isNotBlank())
        assertTrue(ar.aiAutoPresetActiveBadge.isNotBlank() && en.aiAutoPresetActiveBadge.isNotBlank())
    }

    @Test
    fun `video pts time scale computes normal slow-mo and time-lapse accurately`() {
        assertEquals(1.0, com.example.video.VideoRecordingEngine.computePtsTimeScale(1, 1), 1e-6)
        assertEquals(4.0, com.example.video.VideoRecordingEngine.computePtsTimeScale(4, 1), 1e-6)
        assertEquals(0.1, com.example.video.VideoRecordingEngine.computePtsTimeScale(1, 10), 1e-6)
        // Slow-motion takes priority over time-lapse if both exceed 1
        assertEquals(4.0, com.example.video.VideoRecordingEngine.computePtsTimeScale(4, 10), 1e-6)
    }

    @Test
    fun `configurable 3x3 grid lines and power points compute exact composition geometry`() {
        val baseLines = com.example.camera.compute3x3GridLines(900f, 1200f, includeDiagonals = false)
        assertEquals(4, baseLines.size)
        // Vertical 1/3 and 2/3
        assertEquals(300f, baseLines[0].first.first, 0.01f)
        assertEquals(600f, baseLines[1].first.first, 0.01f)
        // Horizontal 1/3 and 2/3
        assertEquals(400f, baseLines[2].first.second, 0.01f)
        assertEquals(800f, baseLines[3].first.second, 0.01f)

        val withDiagonals = com.example.camera.compute3x3GridLines(900f, 1200f, includeDiagonals = true)
        assertEquals(6, withDiagonals.size)

        val dots = com.example.camera.compute3x3IntersectionPoints(900f, 1200f)
        assertEquals(4, dots.size)
        assertEquals(300f to 400f, dots[0])
        assertEquals(600f to 800f, dots[3])

        val cfg = com.example.camera.GridOverlayConfig(
            type = com.example.camera.GridOverlayType.RULE_OF_THIRDS,
            opacity = 0.75f,
            thickness = com.example.camera.GridLineThickness.BOLD,
            color = com.example.camera.GridLineColor.AMBER,
            showDiagonals = true,
            showIntersectionDots = true
        )
        assertEquals(3.2f, cfg.thickness.strokePx, 0.01f)
        assertTrue(cfg.showDiagonals && cfg.showIntersectionDots)
    }

    @Test
    fun `iso priority shutter and night exposure helpers balance low light within hardware bounds`() {
        // Raising ISO from 400 -> 1600 at 0 EV reduces shutter from 40ms to 10ms
        val isoPriority = com.example.camera.computeIsoPriorityShutterNs(
            targetIso = 1600,
            liveIso = 400,
            liveExposureNs = 40_000_000L,
            evSteps = 0,
            evStepSize = 0.1f,
            exposureRange = 100_000L..500_000_000L
        )
        assertEquals(10_000_000L, isoPriority)

        // +1.0 EV bias (10 steps of 0.1 EV) doubles exposure within handheld cap
        val boostedIsoPriority = com.example.camera.computeIsoPriorityShutterNs(
            targetIso = 1600,
            liveIso = 400,
            liveExposureNs = 40_000_000L,
            evSteps = 10,
            evStepSize = 0.1f,
            exposureRange = 100_000L..500_000_000L
        )
        assertEquals(20_000_000L, boostedIsoPriority)

        // Night frame exposure honors targetMaxIso ceiling (800 vs 3200) and positive EV bias
        val (cleanExp, cleanIso) = com.example.camera.PhotoCaptureEngine.computeNightFrameExposure(
            baseExposureNs = 30_000_000L,
            baseIso = 1200,
            astroMode = false,
            exposureRange = 100_000L..400_000_000L,
            isoRange = 100..3200,
            targetMaxIso = 800,
            evBiasSteps = 3,
            aeCompensationStep = 0.1f
        )
        assertTrue(cleanExp in 75_000_000L..135_000_000L)
        assertEquals(800, cleanIso)

        val (_, brightIso) = com.example.camera.PhotoCaptureEngine.computeNightFrameExposure(
            baseExposureNs = 30_000_000L,
            baseIso = 1600,
            astroMode = false,
            exposureRange = 100_000L..400_000_000L,
            isoRange = 100..3200,
            targetMaxIso = 3200,
            evBiasSteps = 6,
            aeCompensationStep = 0.1f
        )
        assertTrue(brightIso > 1600 && brightIso <= 3200)

        val ar = LocaleStrings.forLanguage(AppLanguage.ARABIC)
        val en = LocaleStrings.forLanguage(AppLanguage.ENGLISH)
        assertTrue(ar.grid3x3QuickLabel.isNotBlank() && en.grid3x3QuickLabel.isNotBlank())
        assertTrue(ar.gridOpacityLabel.isNotBlank() && en.gridOpacityLabel.isNotBlank())
        assertTrue(ar.gridThicknessLabel.isNotBlank() && en.gridThicknessLabel.isNotBlank())
        assertTrue(ar.gridColorLabel.isNotBlank() && en.gridColorLabel.isNotBlank())
        assertTrue(ar.gridDiagonalsLabel.isNotBlank() && en.gridDiagonalsLabel.isNotBlank())
        assertTrue(ar.gridIntersectionDotsLabel.isNotBlank() && en.gridIntersectionDotsLabel.isNotBlank())
        assertTrue(ar.exposureIsoTitle.isNotBlank() && en.exposureIsoTitle.isNotBlank())
        assertTrue(ar.lowLightMaxIsoLabel.isNotBlank() && en.lowLightMaxIsoLabel.isNotBlank())
        assertTrue(ar.lowLightEvBiasLabel.isNotBlank() && en.lowLightEvBiasLabel.isNotBlank())
        assertTrue(ar.isoAutoShutterLabel.isNotBlank() && en.isoAutoShutterLabel.isNotBlank())
        assertTrue(ar.shutterLockedLabel.isNotBlank() && en.shutterLockedLabel.isNotBlank())
        assertTrue(ar.manualIsoUnavailableHint.isNotBlank() && en.manualIsoUnavailableHint.isNotBlank())
        assertTrue(ar.resetAutoLabel.isNotBlank() && en.resetAutoLabel.isNotBlank())
    }
}
