package com.example.video

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Size
import android.view.Surface
import com.example.camera.AudioSourceOption
import com.example.camera.VideoCodecType
import com.example.system.SavedMediaResult
import com.example.system.StorageManager
import com.example.system.UltraCamSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

data class VideoRecordingConfig(
    val size: Size,
    val fps: Int,
    val bitrateBps: Int,
    val codec: VideoCodecType,
    val recordAudio: Boolean,
    val audioSource: AudioSourceOption,
    val eisEnabled: Boolean,
    val slowMotionFactor: Int = 1, // 1 = normal, 4 = 120fps->30fps slow-mo
    val timeLapseSpeedFactor: Int = 1, // 1 = normal, >1 = time-lapse
    val focusPowerMode: Boolean = false,
    val aiBitrateMultiplier: Float = 1.0f,
    val encoderMaxBitrateBps: Int = 60_000_000
)

data class VideoRecordingUiState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val audioLevelNormalized: Float = 0f
)

/**
 * Genuine MediaCodec + AudioRecord + MediaMuxer video recording engine supporting
 * H.264 / HEVC, BT.709 colorimetry, High/Main profile, AI & Focus Turbo bitrate boost,
 * 1080p60, 720p120 slow-motion timestamp scaling, time-lapse decimation, and 48kHz AAC audio.
 */
class VideoRecordingEngine(
    private val storageManager: StorageManager
) {
    private val _uiState = MutableStateFlow(VideoRecordingUiState())
    val uiState: StateFlow<VideoRecordingUiState> = _uiState.asStateFlow()

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private val muxerStarted = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val muxerLock = Any()

    private var tempOutputFile: File? = null
    private var outputFilename: String = ""
    private var activeConfig: VideoRecordingConfig? = null

    private var videoDrainJob: Job? = null
    private var audioDrainJob: Job? = null
    private var timerJob: Job? = null

    fun prepare(config: VideoRecordingConfig, tempFile: File, displayName: String): Result<Surface> {
        releaseInternal()
        return runCatching {
            activeConfig = config
            tempOutputFile = tempFile
            outputFilename = displayName
            stopRequested.set(false)
            muxerStarted.set(false)
            videoTrackIndex = -1
            audioTrackIndex = -1

            val turboBoost = if (config.focusPowerMode) 1.25f else 1.0f
            val effectiveBitrate = (config.bitrateBps.toFloat() * config.aiBitrateMultiplier * turboBoost)
                .toInt()
                .coerceIn(config.bitrateBps, config.encoderMaxBitrateBps.coerceAtLeast(config.bitrateBps))

            val outFps = if (config.slowMotionFactor > 1) 30 else config.fps
            val vFormat = buildVideoMediaFormat(config, effectiveBitrate, outFps, includeHighProfile = true)

            val vEnc = MediaCodec.createEncoderByType(config.codec.mimeType)
            val configuredWithProfile = runCatching {
                vEnc.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }.isSuccess
            if (!configuredWithProfile) {
                val safeFormat = buildVideoMediaFormat(config, effectiveBitrate, outFps, includeHighProfile = false)
                vEnc.configure(safeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }

            val surface = vEnc.createInputSurface()
            videoEncoder = vEnc
            inputSurface = surface

            val enableAudio = config.recordAudio &&
                config.slowMotionFactor == 1 &&
                config.timeLapseSpeedFactor == 1

            if (enableAudio) {
                prepareAudioPipeline(
                    source = config.audioSource,
                    highQualityAudio = config.focusPowerMode || config.aiBitrateMultiplier > 1.05f
                )
            }

            muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            surface
        }
    }

    private fun buildVideoMediaFormat(
        config: VideoRecordingConfig,
        bitrateBps: Int,
        outFps: Int,
        includeHighProfile: Boolean
    ): MediaFormat = MediaFormat.createVideoFormat(
        config.codec.mimeType,
        config.size.width,
        config.size.height
    ).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        setInteger(MediaFormat.KEY_FRAME_RATE, outFps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        )
        if (includeHighProfile) {
            if (config.codec == VideoCodecType.H264) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            } else if (config.codec == VideoCodecType.HEVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun prepareAudioPipeline(source: AudioSourceOption, highQualityAudio: Boolean) {
        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            .coerceAtLeast(4096)

        val recorder = AudioRecord(source.mediaRecorderSource, sampleRate, channelConfig, encoding, minBuf * 2)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }

        val aacBitrate = if (highQualityAudio) 192_000 else 160_000
        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, aacBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf * 2)
        }
        val aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aEnc.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        audioRecord = recorder
        audioEncoder = aEnc
    }

    fun start(scope: CoroutineScope) {
        val config = activeConfig ?: return
        val vEnc = videoEncoder ?: return
        val hasAudio = audioEncoder != null && audioRecord != null
        val startMs = System.currentTimeMillis()

        vEnc.start()
        audioEncoder?.start()
        audioRecord?.startRecording()

        _uiState.value = VideoRecordingUiState(isRecording = true, elapsedMs = 0L, audioLevelNormalized = 0f)

        timerJob = scope.launch(Dispatchers.Default) {
            while (isActive && !stopRequested.get()) {
                _uiState.value = _uiState.value.copy(elapsedMs = System.currentTimeMillis() - startMs)
                delay(200L)
            }
        }

        videoDrainJob = scope.launch(Dispatchers.IO) {
            drainVideoEncoder(vEnc, hasAudio, config)
        }

        if (hasAudio) {
            audioDrainJob = scope.launch(Dispatchers.IO) {
                runAudioLoop()
            }
        }
    }

    suspend fun stopAndPublish(settings: UltraCamSettings): SavedMediaResult? = withContext(Dispatchers.IO) {
        stopRequested.set(true)
        runCatching { videoEncoder?.signalEndOfInputStream() }
        videoDrainJob?.join()
        audioDrainJob?.join()
        timerJob?.cancel()

        val tempFile = tempOutputFile
        val name = outputFilename
        releaseInternal()
        _uiState.value = VideoRecordingUiState(isRecording = false, elapsedMs = 0L, audioLevelNormalized = 0f)

        if (tempFile != null && name.isNotBlank()) {
            storageManager.publishVideoFile(tempFile, name, settings)
        } else null
    }

    private fun drainVideoEncoder(
        encoder: MediaCodec,
        expectAudioTrack: Boolean,
        config: VideoRecordingConfig
    ) {
        val info = MediaCodec.BufferInfo()
        var firstPtsUs = -1L
        var lastWrittenPtsUs = -1L
        val timeScale = when {
            config.slowMotionFactor > 1 -> config.slowMotionFactor.toDouble()
            config.timeLapseSpeedFactor > 1 -> 1.0 / config.timeLapseSpeedFactor.toDouble()
            else -> 1.0
        }

        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(info, 12_000L)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (videoTrackIndex == -1) {
                            videoTrackIndex = muxer?.addTrack(encoder.outputFormat) ?: -1
                            maybeStartMuxer(expectAudioTrack)
                        }
                    }
                }
                outIndex >= 0 -> {
                    val outBuf = encoder.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        if (firstPtsUs < 0L) firstPtsUs = info.presentationTimeUs
                        val scaledPts = ((info.presentationTimeUs - firstPtsUs) * timeScale).toLong()
                        // Skip duplicate timestamps during fast time-lapse
                        if (scaledPts > lastWrittenPtsUs || lastWrittenPtsUs < 0L) {
                            info.presentationTimeUs = scaledPts
                            waitForMuxerStart(expectAudioTrack)
                            synchronized(muxerLock) {
                                if (muxerStarted.get() && videoTrackIndex >= 0) {
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    muxer?.writeSampleData(videoTrackIndex, outBuf, info)
                                    lastWrittenPtsUs = scaledPts
                                }
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (stopRequested.get()) {
                        // Continue draining until EOS
                    }
                }
            }
        }
    }

    private fun runAudioLoop() {
        val recorder = audioRecord ?: return
        val encoder = audioEncoder ?: return
        val pcmBuf = ByteArray(2048)
        val info = MediaCodec.BufferInfo()
        val startNs = System.nanoTime()

        while (!stopRequested.get()) {
            val read = recorder.read(pcmBuf, 0, pcmBuf.size)
            if (read > 0) {
                updateAudioMeter(pcmBuf, read)
                val inIdx = encoder.dequeueInputBuffer(5_000L)
                if (inIdx >= 0) {
                    val inBuf = encoder.getInputBuffer(inIdx)
                    if (inBuf != null) {
                        inBuf.clear()
                        val toPut = minOf(read, inBuf.remaining())
                        inBuf.put(pcmBuf, 0, toPut)
                        val ptsUs = (System.nanoTime() - startNs) / 1000L
                        encoder.queueInputBuffer(inIdx, 0, toPut, ptsUs, 0)
                    }
                }
            }
            drainAudioOutput(encoder, info)
        }

        // Send EOS to audio encoder
        val eosIdx = encoder.dequeueInputBuffer(10_000L)
        if (eosIdx >= 0) {
            val ptsUs = (System.nanoTime() - startNs) / 1000L
            encoder.queueInputBuffer(eosIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drainAudioOutput(encoder, info, drainUntilEos = true)
    }

    private fun drainAudioOutput(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        drainUntilEos: Boolean = false
    ) {
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(info, if (drainUntilEos) 10_000L else 0L)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (audioTrackIndex == -1) {
                            audioTrackIndex = muxer?.addTrack(encoder.outputFormat) ?: -1
                            maybeStartMuxer(expectAudioTrack = true)
                        }
                    }
                }
                outIdx >= 0 -> {
                    val outBuf = encoder.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        waitForMuxerStart(expectAudioTrack = true)
                        synchronized(muxerLock) {
                            if (muxerStarted.get() && audioTrackIndex >= 0) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                muxer?.writeSampleData(audioTrackIndex, outBuf, info)
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                else -> if (!drainUntilEos) break
            }
        }
    }

    private fun updateAudioMeter(pcm16: ByteArray, length: Int) {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm16[i].toInt() and 0xFF) or (pcm16[i + 1].toInt() shl 8)
            val absVal = abs(sample.toShort().toInt())
            if (absVal > peak) peak = absVal
            i += 4
        }
        val norm = (peak / 32767f).coerceIn(0f, 1f)
        _uiState.value = _uiState.value.copy(audioLevelNormalized = norm)
    }

    private fun maybeStartMuxer(expectAudioTrack: Boolean) {
        if (muxerStarted.get()) return
        val ready = videoTrackIndex >= 0 && (!expectAudioTrack || audioTrackIndex >= 0)
        if (ready) {
            muxer?.start()
            muxerStarted.set(true)
        }
    }

    private fun waitForMuxerStart(expectAudioTrack: Boolean) {
        var attempts = 0
        while (!muxerStarted.get() && attempts < 80 && !stopRequested.get()) {
            synchronized(muxerLock) { maybeStartMuxer(expectAudioTrack) }
            if (muxerStarted.get()) break
            Thread.sleep(15)
            attempts++
        }
    }

    fun releaseInternal() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        audioEncoder = null

        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null

        runCatching { inputSurface?.release() }
        inputSurface = null

        synchronized(muxerLock) {
            if (muxerStarted.get()) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted.set(false)
        }
    }
}
