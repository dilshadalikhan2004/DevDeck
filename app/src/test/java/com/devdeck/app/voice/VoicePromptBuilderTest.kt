package com.devdeck.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePromptBuilderTest {

    @Test
    fun `prompt includes question and incident facts`() {
        val pack = IncidentVoicePack(
            incidentId = "inc-1",
            rootCause = "KeyError on database_url",
            confidence = 0.8f,
            reasoning = "Missing dict key",
            file = "test_errors.py",
            line = 18,
            originalLine = "print(config[\"database_url\"])",
            repairCode = "print(config.get(\"database_url\"))",
            diffText = null,
            evidence = "config = {\"port\": 8080}"
        )
        val prompt = VoicePromptBuilder.build("why did this fail", pack)
        assertTrue(prompt.contains("why did this fail"))
        assertTrue(prompt.contains("KeyError on database_url"))
        assertTrue(prompt.contains("test_errors.py"))
        assertFalse(prompt.contains("<<<FIX>>>print"))
    }

    @Test
    fun `sanitize strips repair markers`() {
        val cleaned = VoicePromptBuilder.sanitizeSpokenAnswer(
            "The key is missing. <<<FIX>>>print(x)<<<END>>> <end_of_turn>junk"
        )
        assertTrue(cleaned.startsWith("The key is missing."))
        assertFalse(cleaned.contains("<<<FIX>>>"))
        assertFalse(cleaned.contains("<end_of_turn>"))
    }
}
