package com.dashcam.domain.model

enum class VideoQuality { SD, HD, FHD, UHD }

/** [BOTH] records the back camera with the front camera as a picture-in-picture inset. */
enum class CameraFacing { BACK, FRONT, BOTH }

/** [RATIO_4_3] records the full sensor height; [RATIO_16_9] crops it to widescreen. */
enum class VideoAspectRatio { RATIO_16_9, RATIO_4_3 }

enum class SpeedUnit { KMH, MPH }

/** [INFINITY] keeps the lens focused on the road instead of the windscreen, dirt or raindrops. */
enum class FocusMode { AUTO, INFINITY }

/** Encoder bitrate relative to the resolution; [AUTO] leaves it to the device. */
enum class VideoBitrate { AUTO, LOW, HIGH, MAXIMUM }

/** Seconds of video kept around an event (impact or manual mark); 0 turns event clips off. */
val EventClipSecondsOptions = listOf(0, 30, 60)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How long after navigation ends a recording that navigation started is stopped; null = never. */
enum class NavigationStopDelay(val delayMillis: Long?) {
    NEVER(null),
    IMMEDIATELY(0),
    ONE_MINUTE(60_000),
    TWO_MINUTES(120_000),
    FIVE_MINUTES(300_000),
}

/** Impact threshold in g of linear acceleration; lower threshold = more sensitive. */
enum class GSensorSensitivity(val thresholdG: Float) {
    VERY_LOW(2.5f),
    LOW(2.0f),
    NORMAL(1.5f),
    HIGH(1.1f),
    VERY_HIGH(0.8f),
}

data class AppSettings(
    val videoQuality: VideoQuality = VideoQuality.FHD,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    /** With [CameraFacing.BOTH]: save the front camera as its own video instead of an inset. */
    val separateCameraFiles: Boolean = false,
    val aspectRatio: VideoAspectRatio = VideoAspectRatio.RATIO_16_9,
    /** Start at the camera's widest zoom, which switches to the ultra-wide lens where there is one. */
    val wideAngle: Boolean = false,
    val focusMode: FocusMode = FocusMode.AUTO,
    val bitrate: VideoBitrate = VideoBitrate.AUTO,
    /** 30 or 60. */
    val frameRate: Int = 30,
    /** Electronic stabilization; crops the image a little. */
    val stabilization: Boolean = false,
    /** Burn the date and time into the video itself, optionally with speed and coordinates. */
    val videoOverlay: Boolean = true,
    val overlaySpeed: Boolean = false,
    val overlayLocation: Boolean = false,
    /** Extra text in the overlay, e.g. a number plate. */
    val overlayLabel: String = "",
    val recordAudio: Boolean = true,
    val segmentMinutes: Int = 3,
    val loopRecording: Boolean = true,
    /** 0 means unlimited. */
    val maxStorageGb: Int = 8,
    val gSensorEnabled: Boolean = true,
    val gSensorSensitivity: GSensorSensitivity = GSensorSensitivity.NORMAL,
    val vibrateOnImpact: Boolean = true,
    /** See [EventClipSecondsOptions]. */
    val eventClipSeconds: Int = 30,
    /** A Bluetooth / headset media button, or volume up while DashCam is open, marks an event. */
    val remoteButtonMarksEvent: Boolean = false,
    val writeGpx: Boolean = false,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val autoStartOnLaunch: Boolean = false,
    /** Start recording when a navigation app (Google Maps, Waze) starts navigating. */
    val autoRecordOnNavigation: Boolean = false,
    val navigationStopDelay: NavigationStopDelay = NavigationStopDelay.IMMEDIATELY,
    val keepScreenOn: Boolean = true,
    /** Keep the viewfinder in a picture-in-picture window when leaving the app while recording. */
    val pictureInPicture: Boolean = false,
    val vibrateOnStartStop: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val pinHash: String? = null,
) {
    val isPinEnabled: Boolean get() = pinHash != null

    val cameraSetup: CameraSetup
        get() = CameraSetup(
            cameraFacing, videoQuality, separateCameraFiles, aspectRatio, wideAngle,
            focusMode, bitrate, frameRate, stabilization, videoOverlay,
        )

    companion object {
        val SegmentMinuteOptions = listOf(1, 3, 5, 10)
        val MaxStorageGbOptions = listOf(2, 4, 8, 16, 32, 0)
    }
}

/** The settings that decide how the camera is bound. */
data class CameraSetup(
    val facing: CameraFacing,
    val quality: VideoQuality,
    /** Only applies to [CameraFacing.BOTH]: one video per camera instead of an inset. */
    val separateFiles: Boolean,
    val aspectRatio: VideoAspectRatio,
    val wideAngle: Boolean,
    val focusMode: FocusMode = FocusMode.AUTO,
    val bitrate: VideoBitrate = VideoBitrate.AUTO,
    val frameRate: Int = 30,
    val stabilization: Boolean = false,
    val overlay: Boolean = false,
)
