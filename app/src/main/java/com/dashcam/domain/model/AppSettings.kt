package com.dashcam.domain.model

enum class VideoQuality { SD, HD, FHD, UHD }

/** [BOTH] records the back camera with the front camera as a picture-in-picture inset. */
enum class CameraFacing { BACK, FRONT, BOTH }

/** [RATIO_4_3] records the full sensor height; [RATIO_16_9] crops it to widescreen. */
enum class VideoAspectRatio { RATIO_16_9, RATIO_4_3 }

enum class SpeedUnit { KMH, MPH }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

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
    val recordAudio: Boolean = true,
    val segmentMinutes: Int = 3,
    val loopRecording: Boolean = true,
    /** 0 means unlimited. */
    val maxStorageGb: Int = 8,
    val gSensorEnabled: Boolean = true,
    val gSensorSensitivity: GSensorSensitivity = GSensorSensitivity.NORMAL,
    val vibrateOnImpact: Boolean = true,
    val writeSubtitles: Boolean = true,
    val writeGpx: Boolean = false,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val autoStartOnLaunch: Boolean = false,
    val keepScreenOn: Boolean = true,
    val vibrateOnStartStop: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val pinHash: String? = null,
) {
    val isPinEnabled: Boolean get() = pinHash != null

    val cameraSetup: CameraSetup
        get() = CameraSetup(cameraFacing, videoQuality, separateCameraFiles, aspectRatio, wideAngle)

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
)
