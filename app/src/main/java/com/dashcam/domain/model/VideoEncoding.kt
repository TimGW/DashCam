package com.dashcam.domain.model

/** Encoder bitrates per resolution, used both to configure the encoder and to predict clip sizes. */
object VideoEncoding {

    /** Bitrate to request from the encoder, or null for [VideoBitrate.AUTO] (device default). */
    fun targetBitsPerSecond(setup: CameraSetup): Int? =
        if (setup.bitrate == VideoBitrate.AUTO) null else megabitsPerSecond(setup) * 1_000_000

    /**
     * Expected bitrate in Mbit/s. For [VideoBitrate.AUTO] this is a generous guess at the device
     * default, so storage estimates err on the safe side.
     */
    fun megabitsPerSecond(setup: CameraSetup): Int {
        val (low, auto, high, maximum) = when (setup.quality) {
            VideoQuality.SD -> listOf(2, 4, 5, 8)
            VideoQuality.HD -> listOf(5, 10, 12, 16)
            VideoQuality.FHD -> listOf(8, 20, 20, 30)
            VideoQuality.UHD -> listOf(25, 50, 60, 80)
        }
        val base = when (setup.bitrate) {
            VideoBitrate.LOW -> low
            VideoBitrate.AUTO -> auto
            VideoBitrate.HIGH -> high
            VideoBitrate.MAXIMUM -> maximum
        }
        // Twice the frames need roughly half again the bits for the same quality.
        return if (setup.frameRate >= 60) base * 3 / 2 else base
    }

    /** Bytes per minute of recording, counting both files when front and back record separately. */
    fun bytesPerMinute(setup: CameraSetup): Long {
        val files = if (setup.facing == CameraFacing.BOTH && setup.separateFiles) 2 else 1
        return megabitsPerSecond(setup) * 1_000_000L / 8 * 60 * files
    }
}
