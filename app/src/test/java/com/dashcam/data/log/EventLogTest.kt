package com.dashcam.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date

class EventLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `entries read back newest first with a timestamp`() {
        val log = EventLog(folder.newFile("log.txt"))
        log.append(Date(0), "first")
        log.append(Date(1_000), "second")
        val lines = log.read()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("  second"))
        assertTrue(lines[1].endsWith("  first"))
    }

    @Test
    fun `log is trimmed to its newest entries when too large`() {
        val log = EventLog(folder.newFile("log.txt"), maxBytes = 2_000)
        repeat(200) { log.append(Date(), "event $it") }
        assertTrue(log.file.length() <= 2_000)
        assertTrue(log.read().first().endsWith("event 199"))
    }

    @Test
    fun `multi-line messages stay on one line`() {
        val log = EventLog(folder.newFile("log.txt"))
        log.append(Date(), "camera error\nat line 2")
        assertEquals(1, log.read().size)
    }
}
