package com.example.processing

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.compose.runtime.Immutable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

@Immutable
data class StudioEditParams(
    val colorProfile: CameraColorProfile = CameraColorProfile.NATURAL_TRUE,
    val exposureEv: Float = 0f,        // -2.0 .. +2.0
    val contrast: Float = 0f,          // -1.0 .. +1.0
    val highlights: Float = 0f,        // -1.0 .. +1.0
    val shadows: Float = 0f,           // -1.0 .. +1.0
    val whites: Float = 0f,            // -1.0 .. +1.0
    val blacks: Float = 0f,            // -1.0 .. +1.0
    val saturation: Float = 0f,        // -1.0 .. +1.0
    val vibrance: Float = 0f,          // -1.0 .. +1.0
    val temperature: Float = 0f,       // -1.0 (cool) .. +1.0 (warm)
    val tint: Float = 0f,              // -1.0 (green) .. +1.0 (magenta)
    val sharpness: Float = 0f,         // 0.0 .. 1.0
    val noiseReduction: Float = 0f,    // 0.0 .. 1.0
    val detailEnhance: Float = 0f,     // 0.0 .. 1.0
    val rotationDegrees: Int = 0,      // 0, 90, 180, 270
    val cropRatio: CropAspectOption = CropAspectOption.ORIGINAL,
    val keystoneVertical: Float = 0f,  // -0.25 .. +0.25
    val keystoneHorizontal: Float = 0f // -0.25 .. +0.25
) {
    val isIdentity: Boolean
        get() = colorProfile == CameraColorProfile.NATURAL_TRUE &&
            exposureEv == 0f && contrast == 0f && highlights == 0f && shadows == 0f &&
            whites == 0f && blacks == 0f && saturation == 0f && vibrance == 0f &&
            temperature == 0f && tint == 0f && sharpness == 0f && noiseReduction == 0f &&
            detailEnhance == 0f && rotationDegrees == 0 && cropRatio == CropAspectOption.ORIGINAL &&
            keystoneVertical == 0f && keystoneHorizontal == 0f
}

@Immutable
enum class CropAspectOption(val labelEn: String, val labelAr: String, val ratio: Float?) {
    ORIGINAL("Original", "الأصلي", null),
    RATIO_1_1("1:1", "1:1", 1f),
    RATIO_4_3("4:3", "4:3", 4f / 3f),
    RATIO_16_9("16:9", "16:9", 16f / 9f)
}

/**
 * Adreno 610-friendly OpenGL ES 2.0 offscreen GPU shader processor using EGL14 Pbuffer,
 * with automatic deterministic CPU pixel fallback when EGL is unavailable (e.g., JVM tests).
 */
class GlImageProcessor {

    fun processColorAndTone(src: Bitmap, params: StudioEditParams): Bitmap {
        // Try GPU shader path first for real-time Adreno 610 acceleration
        val baseGraded = runCatching { processWithOpenGlEs(src, params) }.getOrNull()
            ?: processCpuFallback(src, params)
        if (params.colorProfile == CameraColorProfile.NATURAL_TRUE) return baseGraded

        val profilePatch = LibPatcherConfig(
            colorProfile = params.colorProfile,
            hdrToneStyle = HdrToneStyle.FILMIC_BALANCED,
            vibrance = 0.10f,
            dehaze = 0.04f,
            sharpness = 0f,
            clarity = 0f,
            lumaDenoise = 0f,
            chromaDenoise = 0f,
            contrast = 0f,
            saturation = 0f,
            shadowLift = 0f,
            highlightCompress = 0f
        )
        val profiled = ColorProfileEngine.applyProfileToBitmap(baseGraded, profilePatch)
        if (baseGraded !== src) baseGraded.recycle()
        return profiled
    }

    private fun processWithOpenGlEs(src: Bitmap, params: StudioEditParams): Bitmap? {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null

        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            EGL14.eglTerminate(display)
            return null
        }
        val config = configs[0]!!

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context: EGLContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(display)
            return null
        }

        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
        val surface: EGLSurface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
        if (surface == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            return null
        }

        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            return null
        }

        return try {
            val program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (program == 0) return null

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, src, 0)

            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(program)

            val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

            val quadBuf = createFloatBuffer(QUAD_COORDS)
            val uvBuf = createFloatBuffer(UV_COORDS)

            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuf)
            GLES20.glEnableVertexAttribArray(texHandle)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, uvBuf)

            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uExposure"), params.exposureEv)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uContrast"), params.contrast)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uHighlights"), params.highlights)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uShadows"), params.shadows)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uWhites"), params.whites)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uBlacks"), params.blacks)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSaturation"), params.saturation)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uVibrance"), params.vibrance)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTemp"), params.temperature)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTint"), params.tint)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDetail"), params.detailEnhance)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTexelX"), 1f / w)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTexelY"), 1f / h)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val readBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuf)
            readBuf.rewind()

            val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outBmp.copyPixelsFromBuffer(readBuf)

            GLES20.glDeleteTextures(1, texIds, 0)
            GLES20.glDeleteProgram(program)
            outBmp
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    fun processCpuFallback(src: Bitmap, params: StudioEditParams, fullPower: Boolean = false): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        val expMult = 2f.pow(params.exposureEv)
        val contrastMult = 1f + params.contrast * 0.75f

        ParallelCompute.parallelRows(h, fullPower) { startY, endY ->
            for (y in startY until endY) {
                val off = y * w
                for (x in 0 until w) {
                    val i = off + x
                    val c = px[i]
                    var r = (((c ushr 16) and 0xFF) / 255f) * expMult
                    var g = (((c ushr 8) and 0xFF) / 255f) * expMult
                    var b = ((c and 0xFF) / 255f) * expMult

                    r += params.temperature * 0.12f + params.tint * 0.06f
                    g -= params.tint * 0.08f
                    b -= params.temperature * 0.12f - params.tint * 0.06f

                    r = r.coerceIn(0f, 1f)
                    g = g.coerceIn(0f, 1f)
                    b = b.coerceIn(0f, 1f)

                    val luma = 0.299f * r + 0.587f * g + 0.114f * b
                    val shadowMask = (1f - luma).pow(2f)
                    val highMask = luma.pow(2f)
                    val toneShift = params.shadows * 0.28f * shadowMask +
                        params.highlights * 0.28f * highMask +
                        params.whites * 0.18f * luma +
                        params.blacks * 0.18f * (1f - luma)
                    r = (r + toneShift).coerceIn(0f, 1f)
                    g = (g + toneShift).coerceIn(0f, 1f)
                    b = (b + toneShift).coerceIn(0f, 1f)

                    r = ((r - 0.5f) * contrastMult + 0.5f).coerceIn(0f, 1f)
                    g = ((g - 0.5f) * contrastMult + 0.5f).coerceIn(0f, 1f)
                    b = ((b - 0.5f) * contrastMult + 0.5f).coerceIn(0f, 1f)

                    val lum2 = 0.299f * r + 0.587f * g + 0.114f * b
                    val maxC = max(r, max(g, b))
                    val minC = min(r, min(g, b))
                    val curSat = maxC - minC
                    val satFactor = (1f + params.saturation + params.vibrance * (1f - curSat)).coerceIn(0f, 2.2f)
                    r = (lum2 + (r - lum2) * satFactor).coerceIn(0f, 1f)
                    g = (lum2 + (g - lum2) * satFactor).coerceIn(0f, 1f)
                    b = (lum2 + (b - lum2) * satFactor).coerceIn(0f, 1f)

                    out[i] = (0xFF shl 24) or
                        ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                        ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                        (b * 255f).roundToInt().coerceIn(0, 255)
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun createProgram(vSrc: String, fSrc: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vSrc)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fSrc)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linked[0] == 0) {
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }

    companion object {
        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        private val UV_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uExposure;
            uniform float uContrast;
            uniform float uHighlights;
            uniform float uShadows;
            uniform float uWhites;
            uniform float uBlacks;
            uniform float uSaturation;
            uniform float uVibrance;
            uniform float uTemp;
            uniform float uTint;
            uniform float uDetail;
            uniform float uTexelX;
            uniform float uTexelY;
            varying vec2 vTexCoord;

            void main() {
                vec4 tex = texture2D(uTexture, vTexCoord);
                vec3 c = tex.rgb * pow(2.0, uExposure);

                if (uDetail > 0.001) {
                    vec3 n1 = texture2D(uTexture, vTexCoord + vec2(uTexelX, 0.0)).rgb;
                    vec3 n2 = texture2D(uTexture, vTexCoord - vec2(uTexelX, 0.0)).rgb;
                    vec3 n3 = texture2D(uTexture, vTexCoord + vec2(0.0, uTexelY)).rgb;
                    vec3 n4 = texture2D(uTexture, vTexCoord - vec2(0.0, uTexelY)).rgb;
                    vec3 blur = (n1 + n2 + n3 + n4) * 0.25 * pow(2.0, uExposure);
                    vec3 delta = clamp((c - blur) * (uDetail * 1.15), -0.14, 0.14);
                    c = clamp(c + delta, 0.0, 1.0);
                }

                c.r += uTemp * 0.12 + uTint * 0.06;
                c.g -= uTint * 0.08;
                c.b -= uTemp * 0.12 - uTint * 0.06;
                c = clamp(c, 0.0, 1.0);

                float luma = dot(c, vec3(0.299, 0.587, 0.114));
                float shadowM = (1.0 - luma) * (1.0 - luma);
                float highM = luma * luma;
                float shift = uShadows * 0.28 * shadowM + uHighlights * 0.28 * highM + uWhites * 0.18 * luma + uBlacks * 0.18 * (1.0 - luma);
                c = clamp(c + vec3(shift), 0.0, 1.0);

                float cm = 1.0 + uContrast * 0.75;
                c = clamp((c - 0.5) * cm + 0.5, 0.0, 1.0);

                float l2 = dot(c, vec3(0.299, 0.587, 0.114));
                float maxC = max(c.r, max(c.g, c.b));
                float minC = min(c.r, min(c.g, c.b));
                float sat = maxC - minC;
                float sf = clamp(1.0 + uSaturation + uVibrance * (1.0 - sat), 0.0, 2.2);
                c = clamp(mix(vec3(l2), c, sf), 0.0, 1.0);

                gl_FragColor = vec4(c, tex.a);
            }
        """
    }
}
