package com.dashcam.data.recorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandTest {

    @Test
    fun `short utterances with a keyword are commands`() {
        assertTrue(VoiceCommandListener.isCommand("lock"))
        assertTrue(VoiceCommandListener.isCommand("Lock!"))
        assertTrue(VoiceCommandListener.isCommand("op slot"))
        assertTrue(VoiceCommandListener.isCommand("Vergrendel de clip"))
        assertTrue(VoiceCommandListener.isCommand("lok"))
        assertTrue(VoiceCommandListener.isCommand("opslaan"))
        assertTrue(VoiceCommandListener.isCommand("Save"))
        assertTrue(VoiceCommandListener.isCommand("bewaren"))
        assertTrue(VoiceCommandListener.isCommand("sla het op"))
        assertTrue(VoiceCommandListener.isCommand("leg dit vast"))
    }

    @Test
    fun `speech that merely contains a keyword is not a command`() {
        assertFalse(VoiceCommandListener.isCommand("en tot slot nog het weerbericht voor morgen"))
        assertFalse(VoiceCommandListener.isCommand("unlock"))
        assertFalse(VoiceCommandListener.isCommand("sloten"))
        assertFalse(VoiceCommandListener.isCommand("ik sla af en dan op de snelweg"))
        assertFalse(VoiceCommandListener.isCommand("sla"))
        assertFalse(VoiceCommandListener.isCommand(""))
    }
}
