package com.devdeck.app.voice

object VoicePromptBuilder {

    fun build(question: String, pack: IncidentVoicePack): String {
        val evidence = pack.evidence.orEmpty().take(600)
        val diff = (pack.diffText ?: pack.repairCode).orEmpty().take(400)
        return """
            <start_of_turn>user
            You are DevDeck on the developer's phone. Answer the spoken question about this incident.
            Speak in 2-4 short sentences. No patch markers, no <<<FIX>>>, no unified diffs, no code fences.
            You cannot approve, apply, or reject repairs. If they ask to approve, say they should say "approve it" as a command, or tap Approve on screen.
            If the transcript is messy, still try to answer from the incident facts. If you do not know, say so.

            INCIDENT ID: ${pack.incidentId ?: "unknown"}
            ROOT CAUSE: ${pack.rootCause ?: "not diagnosed yet"}
            CONFIDENCE: ${pack.confidence ?: "unknown"}
            FILE: ${pack.file ?: "unknown"}:${pack.line ?: "?"}
            FAILING LINE: ${pack.originalLine ?: "unknown"}
            WHY: ${pack.reasoning ?: "unknown"}
            CANDIDATE FIX OR DIFF:
            ${diff.ifBlank { "(none)" }}
            EVIDENCE:
            ${evidence.ifBlank { "(none)" }}

            QUESTION: $question
            <end_of_turn>
            <start_of_turn>model
        """.trimIndent()
    }

    fun sanitizeSpokenAnswer(raw: String): String {
        var text = raw.trim()
        text = text.replace(Regex("(?s)<start_of_turn>.*"), "")
        text = text.replace(Regex("(?s)<end_of_turn>.*"), "")
        text = text.replace(Regex("(?s)<<<FIX>>>.*?(?:<<<END>>>|$)"), "")
        text = text.replace(Regex("(?s)<<<DIFF>>>.*?(?:<<<END>>>|$)"), "")
        text = text.replace(Regex("```[\\s\\S]*?```"), "")
        return text.replace(Regex("\\s+"), " ").trim().take(800)
    }
}
