package com.dashcam.data.recorder

import com.dashcam.domain.model.CameraFacing
import com.dashcam.domain.model.CameraSetup
import com.dashcam.domain.model.VideoAspectRatio
import com.dashcam.domain.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipSizeEstimateTest {

    private fun setup(quality: VideoQuality, facing: CameraFacing = CameraFacing.BACK, separate: Boolean = false) =
        CameraSetup(facing, quality, separate, VideoAspectRatio.RATIO_16_9, wideAngle = false)

    @Test
    fun `higher quality reserves more space`() {
        val sizes = VideoQuality.entries.map { RecordingSession.estimatedBytesPerMinute(setup(it)) }
        assertEquals(sizes.sorted(), sizes)
    }

    @Test
    fun `separate front and back files reserve twice the space`() {
        val one = RecordingSession.estimatedBytesPerMinute(setup(VideoQuality.HD, CameraFacing.BOTH))
        val two = RecordingSession.estimatedBytesPerMinute(setup(VideoQuality.HD, CameraFacing.BOTH, separate = true))
        assertEquals(2 * one, two)
    }

    @Test
    fun `4K reserves at least 300 MB per minute`() {
        assertTrue(RecordingSession.estimatedBytesPerMinute(setup(VideoQuality.UHD)) >= 300_000_000L)
    }
}
