package com.dashcam.domain.usecase

import com.dashcam.domain.model.Recording
import com.dashcam.domain.model.RecordingType
import org.junit.Assert.assertEquals
import org.junit.Test

class KeepCameraPairsTogetherTest {

    private val back = video("back", pairedWith = "front")
    private val front = video("front", pairedWith = "back", isFront = true)
    private val other = video("other")

    @Test
    fun `the pair moves to where the first of its videos sorted, back camera first`() {
        // E.g. sorted by size: the front video is small and landed after an unrelated clip.
        assertEquals(listOf("back", "front", "other"), listOf(back, other, front).keepCameraPairsTogether().map { it.id })
        assertEquals(listOf("back", "front", "other"), listOf(front, other, back).keepCameraPairsTogether().map { it.id })
        assertEquals(listOf("other", "back", "front"), listOf(other, front, back).keepCameraPairsTogether().map { it.id })
    }

    @Test
    fun `a video whose partner isn't in the list stays where it is`() {
        assertEquals(listOf("other", "front"), listOf(other, front).keepCameraPairsTogether().map { it.id })
    }

    private fun video(id: String, pairedWith: String? = null, isFront: Boolean = false) = Recording(
        id = id, name = id, type = RecordingType.VIDEO, createdAtMillis = 0, sizeBytes = 100,
        durationMillis = null, isLocked = false, subtitlesPath = null, pairedWith = pairedWith, isFrontCamera = isFront,
    )
}
