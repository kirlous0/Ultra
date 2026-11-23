package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class CameraSessionManager(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    var cameraDevice: CameraDevice? = null
        private set
    var captureSession: CameraCaptureSession? = null
        private set
    var highSpeedSession: CameraConstrainedHighSpeedCaptureSession? = null
        private set
    var currentCameraId: String? = null
        private set

    fun getHandler(): Handler {
        val existing = cameraHandler
        if (existing != null && cameraThread?.isAlive == true) return existing
        val thread = HandlerThread("UltraCam-Camera2").apply { start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler
        return handler
    }

    @SuppressLint("MissingPermission")
    suspend fun openCamera(cameraId: String): Result<CameraDevice> {
        closeSessionOnly()
        if (cameraDevice != null && currentCameraId == cameraId) {
            return Result.success(cameraDevice!!)
        }
        closeDeviceOnly()

        val handler = getHandler()
        return suspendCancellableCoroutine { cont ->
            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    currentCameraId = cameraId
                    if (cont.isActive) cont.resume(Result.success(camera))
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) {
                        cameraDevice = null
                        currentCameraId = null
                    }
                    if (cont.isActive) cont.resume(Result.failure(IllegalStateException("Camera disconnected")))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) {
                        cameraDevice = null
                        currentCameraId = null
                    }
                    if (cont.isActive) cont.resume(Result.failure(IllegalStateException("Camera error $error")))
                }
            }
            try {
                cameraManager.openCamera(cameraId, callback, handler)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(Result.failure(t))
            }
        }
    }

    suspend fun createSession(
        surfaces: List<Surface>,
        highSpeedMode: Boolean = false
    ): Result<CameraCaptureSession> {
        val device = cameraDevice ?: return Result.failure(IllegalStateException("CameraDevice not open"))
        closeSessionOnly()
        val handler = getHandler()

        return suspendCancellableCoroutine { cont ->
            val stateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    if (highSpeedMode && session is CameraConstrainedHighSpeedCaptureSession) {
                        highSpeedSession = session
                    }
                    if (cont.isActive) cont.resume(Result.success(session))
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (cont.isActive) cont.resume(Result.failure(IllegalStateException("Session configure failed")))
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val configs = surfaces.map { OutputConfiguration(it) }
                    val sessionType = if (highSpeedMode) {
                        SessionConfiguration.SESSION_HIGH_SPEED
                    } else {
                        SessionConfiguration.SESSION_REGULAR
                    }
                    val executor = Executor { cmd -> handler.post(cmd) }
                    val sc = SessionConfiguration(sessionType, configs, executor, stateCallback)
                    device.createCaptureSession(sc)
                } else {
                    @Suppress("DEPRECATION")
                    if (highSpeedMode) {
                        device.createConstrainedHighSpeedCaptureSession(surfaces, stateCallback, handler)
                    } else {
                        device.createCaptureSession(surfaces, stateCallback, handler)
                    }
                }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(Result.failure(t))
            }
        }
    }

    fun startRepeating(
        builder: CaptureRequest.Builder,
        fpsRange: Range<Int>? = null,
        callback: CameraCaptureSession.CaptureCallback? = null
    ) {
        val session = captureSession ?: return
        val handler = getHandler()
        if (fpsRange != null) {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        }
        runCatching {
            val hs = highSpeedSession
            if (hs != null) {
                val burst = hs.createHighSpeedRequestList(builder.build())
                hs.setRepeatingBurst(burst, callback, handler)
            } else {
                session.setRepeatingRequest(builder.build(), callback, handler)
            }
        }
    }

    suspend fun captureSingle(builder: CaptureRequest.Builder): Result<TotalCaptureResult> {
        val session = captureSession ?: return Result.failure(IllegalStateException("No active session"))
        val handler = getHandler()
        return suspendCancellableCoroutine { cont ->
            try {
                session.capture(
                    builder.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            if (cont.isActive) cont.resume(Result.success(result))
                        }

                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: android.hardware.camera2.CaptureFailure
                        ) {
                            if (cont.isActive) {
                                cont.resume(Result.failure(IllegalStateException("Capture failed: ${failure.reason}")))
                            }
                        }
                    },
                    handler
                )
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(Result.failure(t))
            }
        }
    }

    suspend fun captureBurst(builders: List<CaptureRequest.Builder>): Result<List<TotalCaptureResult>> =
        captureBurstWithProgress(builders) { _, _ -> }

    suspend fun captureBurstWithProgress(
        builders: List<CaptureRequest.Builder>,
        onFrameComplete: (completedCount: Int, latestResult: TotalCaptureResult) -> Unit
    ): Result<List<TotalCaptureResult>> {
        val session = captureSession ?: return Result.failure(IllegalStateException("No active session"))
        if (builders.isEmpty()) return Result.success(emptyList())
        val handler = getHandler()
        val requests = builders.map { it.build() }
        return suspendCancellableCoroutine { cont ->
            val collected = ArrayList<TotalCaptureResult>(requests.size)
            var terminalCount = 0
            try {
                session.captureBurst(
                    requests,
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            collected.add(result)
                            terminalCount++
                            onFrameComplete(collected.size, result)
                            if (terminalCount >= requests.size && cont.isActive) {
                                cont.resume(Result.success(collected))
                            }
                        }

                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: android.hardware.camera2.CaptureFailure
                        ) {
                            terminalCount++
                            if (terminalCount >= requests.size && cont.isActive) {
                                if (collected.isNotEmpty()) cont.resume(Result.success(collected))
                                else cont.resume(Result.failure(IllegalStateException("Burst capture failed: ${failure.reason}")))
                            }
                        }

                        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                            if (cont.isActive) {
                                if (collected.isNotEmpty()) cont.resume(Result.success(collected))
                                else cont.resume(Result.failure(IllegalStateException("Burst aborted")))
                            }
                        }
                    },
                    handler
                )
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(Result.failure(t))
            }
        }
    }

    fun closeSessionOnly() {
        runCatching {
            highSpeedSession?.stopRepeating()
            captureSession?.stopRepeating()
            captureSession?.close()
        }
        highSpeedSession = null
        captureSession = null
    }

    fun closeDeviceOnly() {
        closeSessionOnly()
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        currentCameraId = null
    }

    fun shutdown() {
        closeDeviceOnly()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }
}
