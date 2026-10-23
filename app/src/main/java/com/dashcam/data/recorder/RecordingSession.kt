package com.dashcam.data.recorder

import com.dashcam.data.camera.CameraEngine
import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.model.RecorderEvent
import com.dashcam.domain.model.RecorderState
import com.dashcam.domain.repository.ImpactDetector
import com.dashcam.domain.repository.LocationRepository
import com.dashcam.domain.repository.RecordingsRepository
import com.dashcam.domain.repository.SettingsRepository
import com.dashcam.domain.usecase.EnforceStorageLimitUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Loop-recording engine: writes consecutive fixed-length segments, frees space before each
 * segment, locks segments on impact and writes telemetry sidecars. Runs on the main thread.
 */
class RecordingSession(
    private val camera: CameraEngine,
    private val recordings: RecordingsRepository,
    private val settingsRepository: SettingsRepository,
    private val location: LocationRepository,
    private val impactDetector: ImpactDetector,
    private val enforceStorageLimit: EnforceStorageLimitUseCase,
    private val haptics: HapticFeedback,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RecorderEvent> = _events.asSharedFlow()

    private var sessionJob: Job? = null
    private var lockCurrent = false
    private var lockNext = false
    private var muted = false

    /** Starts a session, or returns the running one. The job completes when recording ends. */
    fun begin(): Job {
        sessionJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            val settings = settingsRepository.settings.first()
            camera.configure(settings.cameraFacing, settings.videoQuality, settings.separateCameraFiles)
            if (!camera.state.value.isReady) {
                _events.tryEmit(RecorderEvent.CameraError(camera.state.value.error))
                return@launch
            }
            camera.setSessionActive(true)
            muted = !settings.recordAudio
            lockCurrent = false
            lockNext = false
            val now = System.currentTimeMillis()
            _state.value = RecorderState.Recording(now, now, isLocked = false, isAudioMuted = muted)
            _events.tryEmit(RecorderEvent.Started)
            if (settings.vibrateOnStartStop) haptics.tick()
            // Keep location updates alive for the whole session (speed + telemetry).
            val locationJob = launch { location.location.collect {} }
            val impactJob = if (settings.gSensorEnabled) {
                launch {
                    impactDetector.impacts(settings.gSensorSensitivity.thresholdG).collect { onImpact(settings) }
                }
            } else {
                null
            }
            try {
                recordSegments(settings)
            } finally {
                impactJob?.cancel()
                locationJob.cancel()
                camera.setSessionActive(false)
                _state.value = RecorderState.Idle
                _events.tryEmit(RecorderEvent.Stopped)
                if (settings.vibrateOnStartStop) haptics.tick()
            }
        }.also { sessionJob = it }
    }

    fun end() {
        sessionJob?.cancel()
    }

    fun toggleLock() {
        if (_state.value !is RecorderState.Recording) return
        lockCurrent = !lockCurrent
        if (!lockCurrent) lockNext = false
        _state.update { (it as? RecorderState.Recording)?.copy(isLocked = lockCurrent) ?: it }
        _events.tryEmit(RecorderEvent.LockChanged(lockCurrent))
    }

    fun setAudioMuted(muted: Boolean) {
        this.muted = muted
        camera.setMuted(muted)
        _state.update { (it as? RecorderState.Recording)?.copy(isAudioMuted = muted) ?: it }
    }

    suspend fun takePhoto() {
        val path = recordings.createPhotoFile()
        val saved = camera.takePhoto(File(path))
        recordings.refresh()
        _events.tryEmit(if (saved) RecorderEvent.PhotoSaved else RecorderEvent.PhotoFailed)
    }

    private fun onImpact(settings: AppSettings) {
        if (_state.value !is RecorderState.Recording) return
        // Protect the clip that captured the impact and the one that follows it.
        lockCurrent = true
        lockNext = true
        _state.update { (it as? RecorderState.Recording)?.copy(isLocked = true) ?: it }
        _events.tryEmit(RecorderEvent.ImpactDetected)
        if (settings.vibrateOnImpact) haptics.alert()
    }

    private suspend fun recordSegments(settings: AppSettings) = coroutineScope {
        val segmentMillis = settings.segmentMinutes * 60_000L
        while (isActive) {
            if (enforceStorageLimit() == EnforceStorageLimitUseCase.Result.FULL) {
                _events.tryEmit(RecorderEvent.StorageFull)
                return@coroutineScope
            }
            val path = recordings.createVideoFile()
            val secondaryPath = if (camera.recordsSecondaryVideo) recordings.secondaryVideoFile(path) else null
            val paths = listOfNotNull(path, secondaryPath)
            val finalized = CompletableDeferred<String?>()
            val started = camera.startSegment(File(path), secondaryPath?.let(::File), settings.recordAudio, muted) { error ->
                finalized.complete(error)
            }
            if (!started) {
                _events.tryEmit(RecorderEvent.CameraError(null))
                return@coroutineScope
            }
            recordings.setInProgress(paths.toSet())
            lockCurrent = lockNext
            lockNext = false
            _state.update {
                (it as? RecorderState.Recording)
                    ?.copy(segmentStartedAtMillis = System.currentTimeMillis(), isLocked = lockCurrent) ?: it
            }
            val telemetry = if (settings.writeSubtitles || settings.writeGpx) {
                launch { TelemetryWriter(path, settings, location.location).run() }
            } else {
                null
            }
            // The front video gets its own subtitles; one GPX track per segment is enough.
            val secondaryTelemetry = if (secondaryPath != null && settings.writeSubtitles) {
                launch { TelemetryWriter(secondaryPath, settings.copy(writeGpx = false), location.location).run() }
            } else {
                null
            }
            // Completes early only if the camera finalized the file on its own (an error).
            val earlyFinish = try {
                withTimeoutOrNull(segmentMillis) { finalized.await() ?: "Recording stopped unexpectedly" }
            } finally {
                withContext(NonCancellable) {
                    camera.stopSegment()
                    withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { finalized.await() }
                    telemetry?.cancelAndJoin()
                    secondaryTelemetry?.cancelAndJoin()
                    recordings.setInProgress(emptySet())
                    if (lockCurrent) paths.forEach { recordings.setLocked(it, true) }
                    recordings.refresh()
                }
            }
            if (earlyFinish != null) {
                _events.tryEmit(RecorderEvent.CameraError(earlyFinish))
                return@coroutineScope
            }
        }
    }

    private companion object {
        const val FINALIZE_TIMEOUT_MS = 5_000L
    }
}
