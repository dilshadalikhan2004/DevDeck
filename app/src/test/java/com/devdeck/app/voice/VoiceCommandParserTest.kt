package com.devdeck.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandParserTest {

    @Test
    fun `approve variants`() {
        assertEquals(VoiceCommand.APPROVE, VoiceCommandParser.parse("approve it"))
        assertEquals(VoiceCommand.APPROVE, VoiceCommandParser.parse("approve the change"))
        assertEquals(VoiceCommand.APPROVE, VoiceCommandParser.parse("apply the fix"))
        assertEquals(VoiceCommand.APPROVE, VoiceCommandParser.parse("Yes, apply the patch."))
    }

    @Test
    fun `reject variants`() {
        assertEquals(VoiceCommand.REJECT, VoiceCommandParser.parse("reject"))
        assertEquals(VoiceCommand.REJECT, VoiceCommandParser.parse("don't apply"))
        assertEquals(VoiceCommand.REJECT, VoiceCommandParser.parse("do not approve"))
    }

    @Test
    fun `questions stay questions`() {
        assertEquals(VoiceCommand.STATUS, VoiceCommandParser.parse("what's happening"))
        assertEquals(VoiceCommand.QUESTION, VoiceCommandParser.parse("why did this fail"))
        assertEquals(VoiceCommand.QUESTION, VoiceCommandParser.parse("what is the root cause"))
    }
}
