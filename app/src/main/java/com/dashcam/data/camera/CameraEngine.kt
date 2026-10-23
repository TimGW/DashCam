package com.dashcam.data.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CompositionSettings
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
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
    /** Whether the device can stream the front and back cameras at once ([CameraFacing.BOTH]). */
    val supportsDualCamera: Boolean
    /** [separateFiles] only applies to [CameraFacing.BOTH]: one video per camera instead of an inset. */
    suspend fun configure(facing: CameraFacing, quality: VideoQuality, separateFiles: Boolean)
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider?)
    /** Front camera viewfinder, shown while both cameras record to separate files. */
    fun attachSecondaryPreview(surfaceProvider: Preview.SurfaceProvider?)
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
    private val secondaryPreview = Preview.Builder().build()
    private val roundedInsetEffect by lazy { RoundedInsetEffect() }
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var secondaryVideoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var activeSecondaryRecording: Recording? = null
    private var boundConfig: BoundConfig? = null
    private var targetRotation: Int? = null
    private var previewVisible = false
    private var sessionActive = false
    private val configureMutex = Mutex()

    private val _state = MutableStateFlow(CameraState())
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    val isRecordingSegment: Boolean get() = activeRecording != null

    /** True when the front camera records its own file next to the main one. */
    val recordsSecondaryVideo: Boolean get() = secondaryVideoCapture != null

    override val supportsDualCamera: Boolean = context.supportsDualCamera()

    override suspend fun configure(facing: CameraFacing, quality: VideoQuality, separateFiles: Boolean) = configureMutex.withLock {
        val separate = facing == CameraFacing.BOTH && separateFiles
        // The inset's corner is fixed in the composited frame when binding; rotating afterwards
        // only changes the video's rotation flag and would carry the inset to another corner.
        val compositedRotation = targetRotation.takeIf { facing == CameraFacing.BOTH && !separate }
        val config = BoundConfig(facing, quality, separate, compositedRotation)
        if ((config == boundConfig && camera != null) || activeRecording != null) return@withLock
        try {
            val cameraProvider = provider ?: ProcessCameraProvider.awaitInstance(context).also { provider = it }
            secondaryVideoCapture = null
            if (facing == CameraFacing.BOTH && cameraProvider.bindDual(quality, separateFiles)) {
                boundConfig = config
                return@withLock
            }
            val selector = cameraProvider.selectorFor(facing)
            val video = videoCaptureFor(quality)
            val image = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            targetRotation?.let { image.targetRotation = it }
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

    override fun attachSecondaryPreview(surfaceProvider: Preview.SurfaceProvider?) {
        secondaryPreview.surfaceProvider = surfaceProvider
    }

    override fun setPreviewVisible(visible: Boolean) {
        previewVisible = visible
        updateLifecycle()
    }

    override fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        // Needed for dual-camera mode: the composited preview has no camera transform, so
        // PreviewView rotates it by the Preview's target rotation instead of the display's.
        preview.targetRotation = rotation
        if (activeRecording == null) {
            videoCapture?.targetRotation = rotation
            secondaryVideoCapture?.targetRotation = rotation
        }
        imageCapture?.targetRotation = rotation
    }

    fun setSessionActive(active: Boolean) {
        sessionActive = active
        updateLifecycle()
    }

    /**
     * Starts writing one video segment to [file], and the front camera to [secondaryFile] when
     * [recordsSecondaryVideo]. [onFinalized] runs once every file is closed and receives the
     * first error message, or null when the segment was closed cleanly.
     */
    @SuppressLint("MissingPermission")
    fun startSegment(
        file: File,
        secondaryFile: File?,
        withAudio: Boolean,
        muted: Boolean,
        onFinalized: (String?) -> Unit,
    ): Boolean {
        val output = videoCapture?.output ?: return false
        val secondaryOutput = secondaryFile?.let { secondaryVideoCapture?.output }
        var pending = output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        if (withAudio && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pending = pending.withAudioEnabled()
        }
        var remaining = if (secondaryOutput != null) 2 else 1
        var firstError: String? = null
        val onFileFinalized = { error: String? ->
            firstError = firstError ?: error
            if (--remaining == 0) onFinalized(firstError)
        }
        return try {
            activeRecording = pending.start(mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) onFileFinalized(event.errorMessage())
            }.also { it.mute(muted) }
            // Audio goes into the main file only; two recordings can't share the microphone.
            activeSecondaryRecording = secondaryOutput
                ?.prepareRecording(context, FileOutputOptions.Builder(secondaryFile).build())
                ?.start(mainExecutor) { event ->
                    if (event is VideoRecordEvent.Finalize) onFileFinalized(event.errorMessage())
                }
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to start recording", e)
            stopSegment()
            false
        }
    }

    fun stopSegment() {
        activeRecording?.stop()
        activeRecording = null
        activeSecondaryRecording?.stop()
        activeSecondaryRecording = null
    }

    private fun VideoRecordEvent.Finalize.errorMessage(): String? = when (error) {
        VideoRecordEvent.Finalize.ERROR_NONE -> null
        else -> cause?.message ?: "Recording error $error"
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

    /**
     * Binds the back and front cameras at once. By default the front camera is composited as a
     * picture-in-picture inset, so both end up in the same preview and video file; with
     * [separateFiles] each camera gets its own preview and video. Returns false when the device
     * can't stream both cameras at once; the caller then binds the back camera alone.
     */
    private fun ProcessCameraProvider.bindDual(quality: VideoQuality, separateFiles: Boolean): Boolean {
        val pair = availableConcurrentCameraInfos.firstOrNull { infos ->
            infos.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                infos.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
        } ?: run {
            Log.w(TAG, "Concurrent front + back cameras not supported")
            return false
        }
        val primary = pair.first { it.lensFacing == CameraSelector.LENS_FACING_BACK }.cameraSelector
        val secondary = pair.first { it.lensFacing == CameraSelector.LENS_FACING_FRONT }.cameraSelector
        // Concurrent streaming is only guaranteed up to 720p, so retry lower before giving up.
        val qualities = listOf(quality, VideoQuality.HD).distinct().filter { it <= quality }
        for (q in qualities) {
            val video = videoCaptureFor(q)
            // Sharing one use case group makes CameraX composite; separate groups stay independent.
            val secondaryVideo = if (separateFiles) videoCaptureFor(q) else null
            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(video)
                .apply { if (secondaryVideo == null) addEffect(roundedInsetEffect) }
                .build()
            val configs = if (secondaryVideo != null) {
                val secondaryGroup = UseCaseGroup.Builder()
                    .addUseCase(secondaryPreview)
                    .addUseCase(secondaryVideo)
                    .build()
                listOf(
                    SingleCameraConfig(primary, group, this@CameraEngine),
                    SingleCameraConfig(secondary, secondaryGroup, this@CameraEngine),
                )
            } else {
                listOf(
                    SingleCameraConfig(primary, group, CompositionSettings.DEFAULT, this@CameraEngine),
                    SingleCameraConfig(secondary, group, InsetComposition, this@CameraEngine),
                )
            }
            try {
                unbindAll()
                val boundCamera = bindToLifecycle(configs).cameras.first()
                camera = boundCamera
                videoCapture = video
                secondaryVideoCapture = secondaryVideo
                imageCapture = null
                _state.value = CameraState(
                    isReady = true,
                    hasFlash = boundCamera.cameraInfo.hasFlashUnit(),
                    photoSupported = false,
                    hasSecondaryPreview = secondaryVideo != null,
                    hasCompositedPreview = secondaryVideo == null,
                )
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Dual camera binding failed at $q", e)
            }
        }
        unbindAll()
        return false
    }

    private fun videoCaptureFor(quality: VideoQuality): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(quality.toCameraX(), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
            )
            .build()
        return VideoCapture.withOutput(recorder).also { video -> targetRotation?.let { video.targetRotation = it } }
    }

    private fun ProcessCameraProvider.selectorFor(facing: CameraFacing): CameraSelector {
        val preferred = when (facing) {
            CameraFacing.BACK, CameraFacing.BOTH -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val fallback = when (facing) {
            CameraFacing.BACK, CameraFacing.BOTH -> CameraSelector.DEFAULT_FRONT_CAMERA
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

    private data class BoundConfig(
        val facing: CameraFacing,
        val quality: VideoQuality,
        val separateFiles: Boolean,
        /** Target rotation the inset was composited for; null when nothing is composited. */
        val compositedRotation: Int?,
    )

    private companion object {
        const val TAG = "CameraEngine"

        /**
         * Secondary camera: a 30% inset in the top-right corner (normalized device coordinates,
         * y points up), clear of the telemetry subtitles that players draw along the bottom.
         */
        val InsetComposition: CompositionSettings = CompositionSettings.Builder()
            .setAlpha(1f)
            .setScale(0.3f, 0.3f)
            .setOffset(0.65f, 0.65f)
            .build()
    }
}

fun Context.supportsDualCamera(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
