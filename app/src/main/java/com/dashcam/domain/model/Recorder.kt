package com.dashcam.domain.model

sealed interface RecorderState {
    data object Idle : RecorderState

    data class Recording(
        val startedAtMillis: Long,
        val segmentStartedAtMillis: Long,
        val isLocked: Boolean,
        val isAudioMuted: Boolean,
    ) : RecorderState
}

sealed interface RecorderEvent {
    data object Started : RecorderEvent
    data object Stopped : RecorderEvent
    data class LockChanged(val locked: Boolean) : RecorderEvent
    data object ImpactDetected : RecorderEvent
    data object PhotoSaved : RecorderEvent
    data object PhotoFailed : RecorderEvent
    data object StorageFull : RecorderEvent
    data object StopToSwitchCamera : RecorderEvent
    data class CameraError(val message: String?) : RecorderEvent
}

data class CameraState(
    val isReady: Boolean = false,
    val hasFlash: Boolean = false,
    val torchOn: Boolean = false,
    val photoSupported: Boolean = true,
    /** The front camera has its own viewfinder (both cameras recording to separate files). */
    val hasSecondaryPreview: Boolean = false,
    /** The preview has the front camera composited in a corner, so it must not be cropped. */
    val hasCompositedPreview: Boolean = false,
    val zoomRatio: Float = 1f,
    val error: String? = null,
)

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float?,
    val accuracyMeters: Float?,
    val timeMillis: Long,
)

fun Float.toSpeed(unit: SpeedUnit): Int = when (unit) {
    SpeedUnit.KMH -> this * 3.6f
    SpeedUnit.MPH -> this * 2.236936f
}.toInt()
