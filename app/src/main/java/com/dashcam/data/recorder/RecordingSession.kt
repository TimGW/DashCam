package com.dashcam.data.recorder

import com.dashcam.data.camera.CameraEngine
import com.dashcam.data.camera.ClipFailure
import com.dashcam.data.log.EventLog
import com.dashcam.domain.model.AppSettings
import com.dashcam.domain.model.CameraFacing
import com.dashcam.domain.model.CameraSetup
import com.dashcam.domain.model.DeviceHealth
import com.dashcam.domain.model.RecorderEvent
import com.dashcam.domain.model.RecorderState
import com.dashcam.domain.model.VideoQuality
import com.dashcam.domain.repository.DeviceHealthMonitor
import com.dashcam.domain.repository.ImpactDetector
import com.dashcam.domain.repository.LocationRepository
import com.dashcam.domain.repository.RecordingsRepository
import com.dashcam.domain.repository.SettingsRepository
import com.dashcam.domain.usecase.EnforceStorageLimitUseCase
import com.dashcam.domain.usecase.SessionHealthPolicy
import com.dashcam.domain.usecase.StopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Loop-recording engine: writes consecutive fixed-length clips, frees space ahead of each clip,
 * locks clips on impact and writes telemetry sidecars. Runs on the main thread.
 *
 * Reliability: clips hand over without waiting for the previous file to be finished (CameraX
 * starts the queued clip as soon as it can), so almost nothing is lost between clips. Camera
 * failures and stalled clips don't end the session: it keeps retrying until the camera is back.
 * The session also stops cleanly before the phone overheats or the battery runs out.
 */
class RecordingSession(
    private val camera: CameraEngine,
    private val recordings: RecordingsRepository,
    private val settingsRepository: SettingsRepository,
    private val location: LocationRepository,
    private val impactDetector: ImpactDetector,
    private val enforceStorageLimit: EnforceStorageLimitUseCase,
    private val deviceHealth: DeviceHealthMonitor,
    private val eventLog: EventLog,
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
    private var interrupted = false

    /** Set by the health monitor; picked up by the clip loop. */
    private val healthStop = MutableStateFlow<StopReason?>(null)
    private val qualityCap = MutableStateFlow<VideoQuality?>(null)
    private var lowBatteryWarned = false

    /** Bytes per minute of recent clips, to reserve enough space for the next one. */
    private var observedBytesPerMinute: Long? = null
    @Volatile private var storageFullForNextClip = false

    /** Starts a session, or returns the running one. The job completes when recording ends. */
    fun begin(): Job {
        sessionJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            val settings = settingsRepository.settings.first()
            muted = !settings.recordAudio
            lockCurrent = false
            lockNext = false
            interrupted = false
            healthStop.value = null
            qualityCap.value = null
            lowBatteryWarned = false
            storageFullForNextClip = false
            camera.setSessionActive(true)
            val now = System.currentTimeMillis()
            _state.value = RecorderState.Recording(now, now, isLocked = false, isAudioMuted = muted)
            _events.tryEmit(RecorderEvent.Started)
            eventLog.log("Recording started: ${settings.cameraSetup.describe()}, ${settings.segmentMinutes} min clips")
            if (settings.vibrateOnStartStop) haptics.tick()
            // Keep location updates alive for the whole session (speed + telemetry).
            val locationJob = launch { location.location.collect {} }
            val impactJob = if (settings.gSensorEnabled) {
                launch {
                    impactDetector.impacts(settings.gSensorSensitivity.thresholdG).collect { onImpact(settings, it) }
                }
            } else {
                null
            }
            val healthJob = launch { deviceHealth.health.collect(::onHealth) }
            var reason = "stopped"
            try {
                reason = recordClips(settings)
            } finally {
                impactJob?.cancel()
                locationJob.cancel()
                healthJob.cancel()
                camera.setSessionActive(false)
                _state.value = RecorderState.Idle
                _events.tryEmit(RecorderEvent.Stopped)
                eventLog.log("Recording ended: $reason")
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
        eventLog.log(if (lockCurrent) "Clip locked" else "Clip unlocked")
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

    private fun onImpact(settings: AppSettings, g: Float) {
        if (_state.value !is RecorderState.Recording) return
        // Protect the clip that captured the impact and the one that follows it.
        lockCurrent = true
        lockNext = true
        _state.update { (it as? RecorderState.Recording)?.copy(isLocked = true) ?: it }
        _events.tryEmit(RecorderEvent.ImpactDetected)
        eventLog.log("Impact detected (%.1f g): clip locked".format(g))
        if (settings.vibrateOnImpact) haptics.alert()
    }

    private fun onHealth(health: DeviceHealth) {
        val verdict = SessionHealthPolicy.evaluate(health)
        if (verdict.stop != null && healthStop.value == null) {
            eventLog.log("Stopping: ${verdict.stop} (thermal ${health.thermal}, battery ${health.batteryPercent}%)")
            healthStop.value = verdict.stop
        }
        if (verdict.qualityCap != null && qualityCap.value == null) {
            eventLog.log("Phone is hot (${health.thermal}): switching to ${verdict.qualityCap} now")
            qualityCap.value = verdict.qualityCap
        }
        if (verdict.lowBatteryWarning && !lowBatteryWarned) {
            lowBatteryWarned = true
            eventLog.log("Battery low: ${health.batteryPercent}%")
            _events.tryEmit(RecorderEvent.BatteryLow)
        }
    }

    /** Records clips until cancelled or a stop condition; returns why recording ended. */
    private suspend fun recordClips(settings: AppSettings): String = coroutineScope {
        val clipMillis = settings.segmentMinutes * 60_000L
        var setup = settings.cameraSetup
        var retryDelay = RETRY_INITIAL_MS
        val finishing = mutableListOf<Job>()
        var active: Clip? = null
        try {
            while (true) {
                if (enforceStorage(setup, settings) is EnforceStorageLimitUseCase.Result.Full) {
                    _events.tryEmit(RecorderEvent.StorageFull)
                    return@coroutineScope "storage full"
                }
                camera.configure(setup)
                val first = if (camera.state.value.isReady) startClip(settings) else null
                if (first == null) {
                    onInterrupted("camera unavailable (${camera.state.value.error ?: "could not start a clip"})")
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_MS)
                    camera.invalidate()
                    continue
                }
                active = first
                onResumed()
                retryDelay = RETRY_INITIAL_MS

                // Roll from clip to clip; leave this loop to reconfigure or recover the camera.
                while (true) {
                    val clip = active ?: break
                    launch { prepareStorageForNextClip(setup, settings) }
                    val outcome = awaitClip(clip, clipMillis, setup)
                    val locked = lockCurrent
                    when (outcome) {
                        ClipOutcome.Rollover -> {
                            if (storageFullForNextClip) {
                                active = null
                                stopAndFinish(clip, locked)
                                _events.tryEmit(RecorderEvent.StorageFull)
                                return@coroutineScope "storage full"
                            }
                            camera.stopSegment()
                            active = null
                            lockCurrent = lockNext
                            lockNext = false
                            val next = startClip(settings)
                            // Finish the previous clip while the next one is already recording.
                            finishing += scope.launch(NonCancellable) { finishClip(clip, locked) }
                            if (next == null) {
                                onInterrupted("could not start the next clip")
                                camera.invalidate()
                                break
                            }
                            active = next
                        }
                        is ClipOutcome.Failed -> {
                            active = null
                            stopAndFinish(clip, locked)
                            if (!outcome.failure.outOfStorage) {
                                onInterrupted(outcome.failure.message)
                                delay(retryDelay)
                                retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_MS)
                                camera.invalidate()
                            }
                            break
                        }
                        is ClipOutcome.HealthStop -> {
                            active = null
                            stopAndFinish(clip, locked)
                            _events.tryEmit(
                                when (outcome.reason) {
                                    StopReason.TOO_HOT -> RecorderEvent.StoppedTooHot
                                    StopReason.BATTERY_EMPTY -> RecorderEvent.StoppedBatteryEmpty
                                },
                            )
                            return@coroutineScope when (outcome.reason) {
                                StopReason.TOO_HOT -> "phone too hot"
                                StopReason.BATTERY_EMPTY -> "battery almost empty"
                            }
                        }
                        is ClipOutcome.LowerQuality -> {
                            active = null
                            // Rebinding the camera needs the current clip fully finished.
                            stopAndFinish(clip, locked)
                            setup = setup.copy(quality = outcome.quality)
                            _events.tryEmit(RecorderEvent.Overheating)
                            break
                        }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            "stopped" // The loop only ends through the returns above.
        } finally {
            withContext(NonCancellable) {
                active?.let { stopAndFinish(it, lockCurrent) }
                finishing.joinAll()
            }
        }
    }

    private suspend fun startClip(settings: AppSettings): Clip? {
        val path = recordings.createVideoFile()
        val secondaryPath = if (camera.recordsSecondaryVideo) recordings.secondaryVideoFile(path) else null
        val paths = listOfNotNull(path, secondaryPath)
        val finalized = CompletableDeferred<ClipFailure?>()
        val started = camera.startSegment(File(path), secondaryPath?.let(::File), settings.recordAudio, muted) {
            finalized.complete(it)
        }
        if (!started) return null
        recordings.markInProgress(paths)
        _state.update {
            (it as? RecorderState.Recording)
                ?.copy(segmentStartedAtMillis = System.currentTimeMillis(), isLocked = lockCurrent) ?: it
        }
        val telemetry = buildList {
            if (settings.writeSubtitles || settings.writeGpx) {
                add(scope.launch { TelemetryWriter(path, settings, location.location).run() })
            }
            // The front video gets its own subtitles; one GPX track per clip is enough.
            if (secondaryPath != null && settings.writeSubtitles) {
                add(scope.launch { TelemetryWriter(secondaryPath, settings.copy(writeGpx = false), location.location).run() })
            }
        }
        return Clip(paths, finalized, telemetry, System.currentTimeMillis())
    }

    /** Waits for whichever comes first: the clip's full length, a failure, a stall, or a health event. */
    private suspend fun awaitClip(clip: Clip, clipMillis: Long, setup: CameraSetup): ClipOutcome = coroutineScope {
        val outcome = CompletableDeferred<ClipOutcome>()
        val watchers = listOf(
            launch {
                delay(clipMillis)
                outcome.complete(ClipOutcome.Rollover)
            },
            launch {
                val failure = clip.finalized.await() ?: ClipFailure(false, "clip ended unexpectedly")
                outcome.complete(ClipOutcome.Failed(failure))
            },
            launch {
                waitForStall(clip)
                outcome.complete(ClipOutcome.Failed(ClipFailure(false, "no video written for ${STALL_MS / 1000} s")))
            },
            launch {
                outcome.complete(ClipOutcome.HealthStop(healthStop.filterNotNull().first()))
            },
            launch {
                val cap = qualityCap.filterNotNull().first()
                if (setup.quality > cap) outcome.complete(ClipOutcome.LowerQuality(cap))
            },
        )
        outcome.await().also { watchers.forEach(Job::cancel) }
    }

    /** Returns once the clip's files stopped growing for [STALL_MS]: the encoder has hung. */
    private suspend fun waitForStall(clip: Clip) {
        delay(STALL_GRACE_MS)
        var lastSize = -1L
        var lastGrowth = System.currentTimeMillis()
        while (true) {
            val size = withContext(Dispatchers.IO) { clip.paths.sumOf { File(it).length() } }
            val now = System.currentTimeMillis()
            if (size > lastSize) {
                lastSize = size
                lastGrowth = now
            } else if (now - lastGrowth >= STALL_MS) {
                return
            }
            delay(STALL_CHECK_MS)
        }
    }

    private suspend fun stopAndFinish(clip: Clip, locked: Boolean) = withContext(NonCancellable) {
        camera.stopSegment()
        finishClip(clip, locked)
    }

    /** Waits for the clip's file to be finished, then locks it and updates the listings. */
    private suspend fun finishClip(clip: Clip, locked: Boolean) {
        val failure = withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { clip.finalized.await() }
        clip.telemetry.forEach { it.cancelAndJoin() }
        recordings.clearInProgress(clip.paths)
        if (locked) clip.paths.forEach { recordings.setLocked(it, true) }
        recordings.refresh()
        val bytes = withContext(Dispatchers.IO) { clip.paths.sumOf { File(it).length() } }
        val minutes = (System.currentTimeMillis() - clip.startedAt) / 60_000.0
        if (failure == null && minutes >= 0.5) observedBytesPerMinute = (bytes / minutes).toLong()
        val name = File(clip.paths.first()).name
        eventLog.log(
            "Clip saved: $name, %.1f MB%s%s".format(
                bytes / 1_000_000.0,
                if (locked) ", locked" else "",
                failure?.let { " (ended early: ${it.message})" } ?: "",
            ),
        )
    }

    private suspend fun enforceStorage(setup: CameraSetup, settings: AppSettings): EnforceStorageLimitUseCase.Result {
        val result = enforceStorageLimit(reserveBytes = expectedClipBytes(setup, settings))
        logStorage(result)
        storageFullForNextClip = result is EnforceStorageLimitUseCase.Result.Full
        return result
    }

    /** Runs while a clip records: room for that clip to finish and the next one to fit. */
    private suspend fun prepareStorageForNextClip(setup: CameraSetup, settings: AppSettings) {
        val result = enforceStorageLimit(reserveBytes = 2 * expectedClipBytes(setup, settings))
        logStorage(result)
        storageFullForNextClip = result is EnforceStorageLimitUseCase.Result.Full
    }

    private fun logStorage(result: EnforceStorageLimitUseCase.Result) {
        when (result) {
            is EnforceStorageLimitUseCase.Result.Ok ->
                if (result.deletedClips > 0) eventLog.log("Loop recording: deleted ${result.deletedClips} oldest clip(s)")
            EnforceStorageLimitUseCase.Result.Full -> eventLog.log("Storage full: nothing left to delete")
        }
    }

    private fun expectedClipBytes(setup: CameraSetup, settings: AppSettings): Long {
        val perMinute = observedBytesPerMinute ?: estimatedBytesPerMinute(setup)
        return perMinute * settings.segmentMinutes
    }

    private fun onInterrupted(reason: String) {
        eventLog.log("Camera interrupted: $reason; retrying")
        if (interrupted) return
        interrupted = true
        _state.update { (it as? RecorderState.Recording)?.copy(isInterrupted = true) ?: it }
        _events.tryEmit(RecorderEvent.CameraInterrupted)
    }

    private fun onResumed() {
        if (!interrupted) return
        interrupted = false
        eventLog.log("Camera back: recording resumed")
        _state.update { (it as? RecorderState.Recording)?.copy(isInterrupted = false) ?: it }
        _events.tryEmit(RecorderEvent.CameraResumed)
    }

    private class Clip(
        val paths: List<String>,
        val finalized: CompletableDeferred<ClipFailure?>,
        val telemetry: List<Job>,
        val startedAt: Long,
    )

    private sealed interface ClipOutcome {
        data object Rollover : ClipOutcome
        data class Failed(val failure: ClipFailure) : ClipOutcome
        data class HealthStop(val reason: StopReason) : ClipOutcome
        data class LowerQuality(val quality: VideoQuality) : ClipOutcome
    }

    companion object {
        private const val FINALIZE_TIMEOUT_MS = 5_000L
        private const val RETRY_INITIAL_MS = 2_000L
        private const val RETRY_MAX_MS = 30_000L
        /** Clips need a moment before the first data reaches the file. */
        private const val STALL_GRACE_MS = 15_000L
        private const val STALL_CHECK_MS = 5_000L
        private const val STALL_MS = 20_000L

        /** Typical encoder output until real clips have been measured (generous on purpose). */
        fun estimatedBytesPerMinute(setup: CameraSetup): Long {
            val megabitsPerSecond = when (setup.quality) {
                VideoQuality.SD -> 4
                VideoQuality.HD -> 10
                VideoQuality.FHD -> 20
                VideoQuality.UHD -> 50
            }
            val files = if (setup.facing == CameraFacing.BOTH && setup.separateFiles) 2 else 1
            return megabitsPerSecond * 1_000_000L / 8 * 60 * files
        }
    }
}

private fun CameraSetup.describe() = buildString {
    append(facing.name.lowercase())
    append(' ').append(quality.name)
    if (facing == CameraFacing.BOTH) append(if (separateFiles) " separate files" else " inset")
    if (wideAngle) append(", wide angle")
}
