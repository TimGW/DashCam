package com.dashcam.domain.model

enum class VideoQuality { SD, HD, FHD, UHD }

enum class CameraFacing { BACK, FRONT }

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

    companion object {
        val SegmentMinuteOptions = listOf(1, 3, 5, 10)
        val MaxStorageGbOptions = listOf(2, 4, 8, 16, 32, 0)
    }
}
