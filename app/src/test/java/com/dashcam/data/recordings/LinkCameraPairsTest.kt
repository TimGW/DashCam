package com.dashcam.data.recordings

import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.RecordingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCameraPairsTest {

    @Test
    fun `links the front and back camera videos of one session both ways`() {
        val (back, front) = FileRecordingsRepository.linkCameraPairs(
            listOf(video("DC_20261023_120000.mp4"), video("DC_20261023_120000_front.mp4")),
        )
        assertEquals(front.id, back.pairedWith)
        assertEquals(back.id, front.pairedWith)
        assertFalse(back.isFrontCamera)
        assertTrue(front.isFrontCamera)
    }

    @Test
    fun `links them when either is locked`() {
        val (back, front) = FileRecordingsRepository.linkCameraPairs(
            listOf(video("DC_20261023_120000_locked.mp4"), video("DC_20261023_120000_front.mp4")),
        )
        assertEquals(front.id, back.pairedWith)
        val (back2, front2) = FileRecordingsRepository.linkCameraPairs(
            listOf(video("DC_20261023_120000.mp4"), video("DC_20261023_120000_front_locked.mp4")),
        )
        assertEquals(back2.id, front2.pairedWith)
    }

    @Test
    fun `a front camera video without its partner is still marked as front camera`() {
        val front = FileRecordingsRepository.linkCameraPairs(listOf(video("DC_20261023_120000_front.mp4"))).single()
        assertTrue(front.isFrontCamera)
        assertNull(front.pairedWith)
    }

    @Test
    fun `single-camera videos and event clips stay unlinked`() {
        val result = FileRecordingsRepository.linkCameraPairs(
            listOf(video("DC_20261023_120000.mp4"), video("DC_20261023_120010_event_locked.mp4")),
        )
        assertTrue(result.all { it.pairedWith == null && !it.isFrontCamera })
    }

    private fun video(name: String) = Recording(
        id = "/videos/$name", name = name, type = RecordingType.VIDEO, createdAtMillis = 0, sizeBytes = 100,
        durationMillis = null, isLocked = name.contains("_locked"), subtitlesPath = null,
    )
}
