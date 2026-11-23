package com.example

import com.example.processing.CameraColorProfile
import com.example.processing.ColorProfileEngine
import com.example.processing.HdrToneStyle
import com.example.processing.LibPatcherConfig
import android.graphics.Bitmap
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("UltraCam", appName)
    }

    @Test
    fun `lib patcher config serializes and deserializes all fields faithfully`() {
        val original = LibPatcherConfig(
            colorProfile = CameraColorProfile.FUJI_CLASSIC_CHROME,
            hdrToneStyle = HdrToneStyle.HIGH_DR,
            sharpness = 0.65f,
            clarity = 0.42f,
            lumaDenoise = 0.33f,
            chromaDenoise = 0.51f,
            contrast = 0.21f,
            saturation = -0.12f,
            vibrance = 0.28f,
            warmth = 0.14f,
            tint = -0.09f,
            shadowLift = 0.24f,
            highlightCompress = 0.30f,
            dehaze = 0.22f,
            vignette = 0.18f,
            skinToneProtect = false
        )
        val restored = LibPatcherConfig.fromJsonString(original.toJsonString())
        assertEquals(original.colorProfile, restored.colorProfile)
        assertEquals(original.hdrToneStyle, restored.hdrToneStyle)
        assertEquals(original.sharpness, restored.sharpness, 0.001f)
        assertEquals(original.clarity, restored.clarity, 0.001f)
        assertEquals(original.lumaDenoise, restored.lumaDenoise, 0.001f)
        assertEquals(original.chromaDenoise, restored.chromaDenoise, 0.001f)
        assertEquals(original.contrast, restored.contrast, 0.001f)
        assertEquals(original.saturation, restored.saturation, 0.001f)
        assertEquals(original.vibrance, restored.vibrance, 0.001f)
        assertEquals(original.warmth, restored.warmth, 0.001f)
        assertEquals(original.tint, restored.tint, 0.001f)
        assertEquals(original.shadowLift, restored.shadowLift, 0.001f)
        assertEquals(original.highlightCompress, restored.highlightCompress, 0.001f)
        assertEquals(original.dehaze, restored.dehaze, 0.001f)
        assertEquals(original.vignette, restored.vignette, 0.001f)
        assertFalse(restored.skinToneProtect)
    }

    @Test
    fun `color profile engine builds live color matrix and grades bitmap`() {
        val patch = LibPatcherConfig.PRESET_CINEMA_FILM
        val matrix = ColorProfileEngine.buildLivePreviewColorMatrix(patch)
        assertEquals(20, matrix.array.size)

        val src = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        src.eraseColor(0xFF886644.toInt())
        val graded = ColorProfileEngine.applyProfileToBitmap(src, patch, fullPower = false)
        assertEquals(16, graded.width)
        assertEquals(16, graded.height)
    }
}
