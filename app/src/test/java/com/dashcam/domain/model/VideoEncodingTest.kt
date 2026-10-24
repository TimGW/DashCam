package com.dashcam.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEncodingTest {

    private val fhd = CameraSetup(CameraFacing.BACK, VideoQuality.FHD, false, VideoAspectRatio.RATIO_16_9, wideAngle = false)

    @Test
    fun `auto bitrate is left to the device`() {
        assertNull(VideoEncoding.targetBitsPerSecond(fhd))
    }

    @Test
    fun `explicit bitrates go up from low to maximum`() {
        val rates = listOf(VideoBitrate.LOW, VideoBitrate.HIGH, VideoBitrate.MAXIMUM)
            .map { VideoEncoding.targetBitsPerSecond(fhd.copy(bitrate = it))!! }
        assertEquals(rates.sorted(), rates)
    }

    @Test
    fun `60 fps needs more space than 30 fps`() {
        assertTrue(VideoEncoding.bytesPerMinute(fhd.copy(frameRate = 60)) > VideoEncoding.bytesPerMinute(fhd))
    }

    @Test
    fun `one minute of auto 1080p is reserved generously`() {
        // 20 Mbit/s for 60 s = 150 MB.
        assertEquals(150_000_000L, VideoEncoding.bytesPerMinute(fhd))
    }
}
