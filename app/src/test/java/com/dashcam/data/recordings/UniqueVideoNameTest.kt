package com.dashcam.data.recordings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class UniqueVideoNameTest {

    private val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse("20261023_120000")!!

    @Test
    fun `uses the timestamp when free`() {
        assertEquals("DC_20261023_120000", FileRecordingsRepository.uniqueVideoBase(date) { false })
    }

    @Test
    fun `moves to the next free second instead of overwriting a clip`() {
        val taken = setOf("DC_20261023_120000", "DC_20261023_120001")
        assertEquals("DC_20261023_120002", FileRecordingsRepository.uniqueVideoBase(date) { it in taken })
    }
}
