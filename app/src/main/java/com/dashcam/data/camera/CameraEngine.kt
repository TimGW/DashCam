package com.dashcam.data.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.annotation.MainThread
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState as CameraXState
import androidx.camera.core.CompositionSettings
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.AspectRatioStrategy
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
import com.dashcam.domain.model.CameraSetup
import com.dashcam.domain.model.CameraState
import com.dashcam.domain.model.FocusMode
import com.dashcam.domain.model.VideoAspectRatio
import com.dashcam.domain.model.VideoEncoding
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

/** Why a clip ended before it was stopped. [outOfStorage] clips are still playable. */
data class ClipFailure(val outOfStorage: Boolean, val message: String)

/** Screen-facing camera operations used by the record screen. */
interface CameraPreviewHost {
    /** Whether the device can stream the front and back cameras at once ([CameraFacing.BOTH]). */
    val supportsDualCamera: Boolean
    suspend fun configure(setup: CameraSetup)
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
class CameraEngine(
    private val context: Context,
    private val videoOverlay: VideoOverlay,
) : CameraController, CameraPreviewHost, LifecycleOwner {

    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    /** Matches the recording's aspect ratio, so the viewfinder shows exactly what is recorded. */
    private var preview = previewFor(VideoAspectRatio.RATIO_16_9)
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
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

    /** Whether the main video gets the burned-in overlay (not possible in every camera mode). */
    var burnsInOverlay = false
        private set

    /** True when the front camera records its own file next to the main one. */
    val recordsSecondaryVideo: Boolean get() = secondaryVideoCapture != null

    override val supportsDualCamera: Boolean = context.supportsDualCamera()

    override suspend fun configure(setup: CameraSetup) = configureMutex.withLock {
        val dual = setup.facing == CameraFacing.BOTH
        val normalized = setup.copy(separateFiles = dual && setup.separateFiles)
        // The inset's corner is fixed in the composited frame when binding; rotating afterwards
        // only changes the video's rotation flag and would carry the inset to another corner.
        val compositedRotation = targetRotation.takeIf { dual && !normalized.separateFiles }
        val config = BoundConfig(normalized, compositedRotation)
        if ((config == boundConfig && camera != null) || activeRecording != null) return@withLock
        try {
            val cameraProvider = provider ?: ProcessCameraProvider.awaitInstance(context).also { provider = it }
            if (boundConfig?.setup?.aspectRatio != setup.aspectRatio) {
                cameraProvider.unbindAll()
                preview = previewFor(setup.aspectRatio)
            }
            secondaryVideoCapture = null
            if (dual && cameraProvider.bindDual(normalized)) {
                boundConfig = config
                return@withLock
            }
            val selector = cameraProvider.selectorFor(setup.facing)
            val image = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            targetRotation?.let { image.targetRotation = it }
            cameraProvider.unbindAll()
            // Some devices can't combine everything: drop 60 fps / stabilization first, then photos.
            var bound: Triple<Camera, VideoCapture<Recorder>, Boolean>? = null
            var lastError: IllegalArgumentException? = null
            attempts@ for (attempt in setup.withFallbacks()) {
                for (withPhoto in listOf(true, false)) {
                    val video = videoCaptureFor(attempt)
                    val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(video)
                        .apply { if (withPhoto) addUseCase(image) }
                        .apply { if (attempt.overlay) addEffect(videoOverlay.effect) }
                        .build()
                    try {
                        val boundCamera = cameraProvider.bindToLifecycle(this, selector, group)
                        // Rather than refuse a combination, CameraX may share one small stream
                        // between all outputs: give up photos before giving up resolution.
                        if (withPhoto && video.isBelow(attempt.quality)) {
                            Log.w(TAG, "Video downscaled to ${video.resolutionInfo?.resolution} with photos; retrying without")
                            cameraProvider.unbindAll()
                            continue
                        }
                        bound = Triple(boundCamera, video, withPhoto)
                        if (attempt != setup) Log.w(TAG, "Bound with fallback settings: $attempt")
                        break@attempts
                    } catch (e: IllegalArgumentException) {
                        lastError = e
                        cameraProvider.unbindAll()
                    }
                }
            }
            val (boundCamera, video, photoSupported) = bound ?: throw lastError ?: IllegalStateException("No camera binding")
            onCameraBound(boundCamera, setup)
            burnsInOverlay = setup.overlay
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
        previewSurfaceProvider = surfaceProvider
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
     * first failure, or null when the segment was closed cleanly. It may be called while a
     * previous segment is still finalizing: the recorder then starts it as soon as it can.
     */
    @SuppressLint("MissingPermission")
    fun startSegment(
        file: File,
        secondaryFile: File?,
        withAudio: Boolean,
        muted: Boolean,
        onFinalized: (ClipFailure?) -> Unit,
    ): Boolean {
        val output = videoCapture?.output ?: return false
        val secondaryOutput = secondaryFile?.let { secondaryVideoCapture?.output }
        var pending = output.prepareRecording(context, FileOutputOptions.Builder(file).build())
        if (withAudio && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pending = pending.withAudioEnabled()
        }
        var remaining = if (secondaryOutput != null) 2 else 1
        var firstError: ClipFailure? = null
        val onFileFinalized = { error: ClipFailure? ->
            firstError = firstError ?: error
            if (--remaining == 0) onFinalized(firstError)
        }
        return try {
            activeRecording = pending.start(mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) onFileFinalized(event.failure())
            }.also { it.mute(muted) }
            // Audio goes into the main file only; two recordings can't share the microphone.
            activeSecondaryRecording = secondaryOutput
                ?.prepareRecording(context, FileOutputOptions.Builder(secondaryFile).build())
                ?.start(mainExecutor) { event ->
                    if (event is VideoRecordEvent.Finalize) onFileFinalized(event.failure())
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

    private fun VideoRecordEvent.Finalize.failure(): ClipFailure? = when (error) {
        VideoRecordEvent.Finalize.ERROR_NONE -> null
        else -> ClipFailure(
            outOfStorage = error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
            message = cause?.message ?: "Recording error $error",
        )
    }

    /** Forces the next [configure] to bind the camera again, e.g. after it was lost to another app. */
    fun invalidate() {
        boundConfig = null
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

    /**
     * Tracks the newly bound camera. With [wideAngle], zooms out to the widest ratio (the
     * ultra-wide lens on multi-camera phones) once the camera is open; zooming fails before that.
     */
    private fun onCameraBound(boundCamera: Camera, setup: CameraSetup) {
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        camera = boundCamera
        if (!setup.wideAngle && setup.focusMode == FocusMode.AUTO) return
        // Zoom and capture request options only stick once the camera is open.
        boundCamera.cameraInfo.cameraState.observe(this) { cameraState ->
            if (cameraState.type != CameraXState.Type.OPEN || camera !== boundCamera) return@observe
            if (setup.wideAngle) {
                boundCamera.cameraInfo.zoomState.value?.minZoomRatio?.let { minRatio ->
                    boundCamera.cameraControl.setZoomRatio(minRatio)
                    _state.update { it.copy(zoomRatio = minRatio) }
                }
            }
            if (setup.focusMode == FocusMode.INFINITY) focusAtInfinity(boundCamera)
        }
    }

    /** Turns autofocus off and focuses at infinity, unless the lens has a fixed focus anyway. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun focusAtInfinity(boundCamera: Camera) {
        val info = Camera2CameraInfo.from(boundCamera.cameraInfo)
        val minFocusDistance = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (minFocusDistance <= 0f) return
        Camera2CameraControl.from(boundCamera.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                .build(),
        )
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
    private fun ProcessCameraProvider.bindDual(setup: CameraSetup): Boolean {
        val quality = setup.quality
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
            val video = videoCaptureFor(setup.copy(quality = q))
            // Sharing one use case group makes CameraX composite; separate groups stay independent.
            // The front file gets no overlay; an effect instance serves one group.
            val secondaryVideo = if (setup.separateFiles) videoCaptureFor(setup.copy(quality = q, overlay = false)) else null
            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(video)
                .apply { if (secondaryVideo == null) addEffect(roundedInsetEffect) }
                // No overlay when composited: a video effect alongside the composition leaves the
                // video without frames (tested on a Pixel 10, CameraX 1.5).
                .apply { if (setup.overlay && secondaryVideo != null) addEffect(videoOverlay.effect) }
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
                onCameraBound(boundCamera, setup)
                burnsInOverlay = setup.overlay && secondaryVideo != null
                videoCapture = video
                secondaryVideoCapture = secondaryVideo
                imageCapture = null
                _state.value = CameraState(
                    isReady = true,
                    hasFlash = boundCamera.cameraInfo.hasFlashUnit(),
                    photoSupported = false,
                    hasSecondaryPreview = secondaryVideo != null,
                )
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Dual camera binding failed at $q", e)
            }
        }
        unbindAll()
        return false
    }

    private fun videoCaptureFor(setup: CameraSetup): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                // Try every lower quality in order: with a single quality, CameraX's own fallback
                // skipped 1080p (4K at 4:3 on a Pixel 10 came out as 1024x768, not 1600x1200).
                QualitySelector.fromOrderedList(
                    VideoQuality.entries.filter { it <= setup.quality }.reversed().map { it.toCameraX() },
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .setAspectRatio(
                when (setup.aspectRatio) {
                    VideoAspectRatio.RATIO_16_9 -> AspectRatio.RATIO_16_9
                    VideoAspectRatio.RATIO_4_3 -> AspectRatio.RATIO_4_3
                },
            )
            .apply { VideoEncoding.targetBitsPerSecond(setup)?.let(::setTargetVideoEncodingBitRate) }
            .build()
        return VideoCapture.Builder(recorder)
            .apply { if (setup.frameRate >= 60) setTargetFrameRate(Range(60, 60)) }
            .setVideoStabilizationEnabled(setup.stabilization)
            .apply { targetRotation?.let(::setTargetRotation) }
            .build()
    }

    private fun previewFor(aspectRatio: VideoAspectRatio): Preview = Preview.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    when (aspectRatio) {
                        VideoAspectRatio.RATIO_16_9 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        VideoAspectRatio.RATIO_4_3 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    },
                )
                .build(),
        )
        .build()
        .also { new ->
            new.surfaceProvider = previewSurfaceProvider
            targetRotation?.let { new.targetRotation = it }
        }

    /** Whether the bound video stream is clearly smaller than [quality] asks for. */
    private fun VideoCapture<Recorder>.isBelow(quality: VideoQuality): Boolean {
        val size = resolutionInfo?.resolution ?: return false
        val expectedShortSide = when (quality) {
            VideoQuality.SD -> 480
            VideoQuality.HD -> 720
            VideoQuality.FHD -> 1080
            VideoQuality.UHD -> 2160
        }
        return minOf(size.width, size.height) < expectedShortSide * 9 / 10
    }

    /** The requested setup, then without the extras that devices most often can't combine. */
    private fun CameraSetup.withFallbacks(): List<CameraSetup> =
        listOf(this, copy(frameRate = 30, stabilization = false)).distinct()

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
        val setup: CameraSetup,
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
