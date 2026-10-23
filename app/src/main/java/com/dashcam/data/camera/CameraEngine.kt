package com.dashcam.data.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.MainThread
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.dashcam.domain.model.CameraFacing
import com.dashcam.domain.model.CameraState
import com.dashcam.domain.model.VideoQuality
import com.dashcam.domain.repository.CameraController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.resume

/** Screen-facing camera operations used by the record screen. */
interface CameraPreviewHost {
    suspend fun configure(facing: CameraFacing, quality: VideoQuality)
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider?)
    fun setPreviewVisible(visible: Boolean)
    fun setTargetRotation(rotation: Int)
}

/**
 * Owns the CameraX use cases. It is its own [LifecycleOwner] so the camera stays open while
 * either the preview is on screen or a background recording session is active.
 */
@MainThread
class CameraEngine(private val context: Context) : CameraController, CameraPreviewHost, LifecycleOwner {

    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val preview = Preview.Builder().build()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var activeRecording: Recording? = null
    private var boundConfig: Pair<CameraFacing, VideoQuality>? = null
    private var targetRotation: Int? = null
    private var previewVisible = false
    private var sessionActive = false
    private val configureMutex = Mutex()

    private val _state = MutableStateFlow(CameraState())
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    val isRecordingSegment: Boolean get() = activeRecording != null

    override suspend fun configure(facing: CameraFacing, quality: VideoQuality) = configureMutex.withLock {
        val config = facing to quality
        if ((config == boundConfig && camera != null) || activeRecording != null) return@withLock
        try {
            val cameraProvider = provider ?: ProcessCameraProvider.awaitInstance(context).also { provider = it }
            val selector = cameraProvider.selectorFor(facing)
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(quality.toCameraX(), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
                )
                .build()
            val video = VideoCapture.withOutput(recorder)
            val image = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            targetRotation?.let {
                video.targetRotation = it
                image.targetRotation = it
            }
            cameraProvider.unbindAll()
            // Some devices can't stream preview + video + photo at once; fall back to no photos.
            val (boundCamera, photoSupported) = try {
                cameraProvider.bindToLifecycle(this, selector, preview, video, image) to true
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Photo capture not supported alongside video", e)
                cameraProvider.bindToLifecycle(this, selector, preview, video) to false
            }
            camera = boundCamera
            videoCapture = video
            imageCapture = image.takeIf { photoSupported }
            boundConfig = config
            _state.value = CameraState(
                isReady = true,
                hasFlash = boundCamera.cameraInfo.hasFlashUnit(),
                photoSupported = photoSupported,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Camera configuration failed", e)
            camera = null
            boundConfig = null
            _state.value = CameraState(error = e.message ?: e.javaClass.simpleName)
        }
    }

    override fun attachPreview(surfaceProvider: Preview.SurfaceProvider?) {
        preview.surfaceProvider = surfaceProvider
    }

    override fun setPreviewVisible(visible: Boolean) {
        previewVisible = visible
        updateLifecycle()
    }

    override fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        if (activeRecording == null) videoCapture?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
    }

    fun setSessionActive(active: Boolean) {
        sessionActive = active
        updateLifecycle()
    }

    /**
     * Starts writing one video segment to [file]. [onFinalized] receives an error message,
     * or null when the segment was closed cleanly.
     */
    @SuppressLint("MissingPermission")
    fun startSegment(file: File, withAudio: Boolean, muted: Boolean, onFinalized: (String?) -> Unit): Boolean {
        val output = videoCapture?.output ?: return false
        var pending = output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        if (withAudio && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pending = pending.withAudioEnabled()
        }
        return try {
            activeRecording = pending.start(mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    val error = when (event.error) {
                        VideoRecordEvent.Finalize.ERROR_NONE -> null
                        else -> event.cause?.message ?: "Recording error ${event.error}"
                    }
                    onFinalized(error)
                }
            }.also { it.mute(muted) }
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to start recording", e)
            false
        }
    }

    fun stopSegment() {
        activeRecording?.stop()
        activeRecording = null
    }

    fun setMuted(muted: Boolean) {
        activeRecording?.mute(muted)
    }

    suspend fun takePhoto(file: File): Boolean {
        val capture = imageCapture ?: return false
        return suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        continuation.resume(true)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed", exception)
                        continuation.resume(false)
                    }
                },
            )
        }
    }

    override fun setTorch(enabled: Boolean) {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        cam.cameraControl.enableTorch(enabled)
        _state.update { it.copy(torchOn = enabled) }
    }

    override fun scaleZoom(factor: Float) {
        val cam = camera ?: return
        val zoom = cam.cameraInfo.zoomState.value ?: return
        val ratio = (zoom.zoomRatio * factor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        cam.cameraControl.setZoomRatio(ratio)
        _state.update { it.copy(zoomRatio = ratio) }
    }

    private fun updateLifecycle() {
        registry.currentState = if (previewVisible || sessionActive) {
            Lifecycle.State.RESUMED
        } else {
            _state.update { it.copy(torchOn = false) }
            Lifecycle.State.CREATED
        }
    }

    private fun ProcessCameraProvider.selectorFor(facing: CameraFacing): CameraSelector {
        val preferred = when (facing) {
            CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val fallback = when (facing) {
            CameraFacing.BACK -> CameraSelector.DEFAULT_FRONT_CAMERA
            CameraFacing.FRONT -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        return if (runCatching { hasCamera(preferred) }.getOrDefault(false)) preferred else fallback
    }

    private fun VideoQuality.toCameraX(): Quality = when (this) {
        VideoQuality.SD -> Quality.SD
        VideoQuality.HD -> Quality.HD
        VideoQuality.FHD -> Quality.FHD
        VideoQuality.UHD -> Quality.UHD
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "CameraEngine"
    }
}
