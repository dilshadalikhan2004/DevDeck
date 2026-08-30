package com.devdeck.app.voice

enum class VoiceCommand {
    APPROVE,
    REJECT,
    ROLLBACK,
    STATUS,
    QUESTION
}

object VoiceCommandParser {

    fun parse(transcript: String): VoiceCommand {
        val t = transcript.lowercase()
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.isBlank()) return VoiceCommand.QUESTION

        if (isReject(t)) return VoiceCommand.REJECT
        if (isRollback(t)) return VoiceCommand.ROLLBACK
        if (isStatus(t)) return VoiceCommand.STATUS
        if (isApprove(t)) return VoiceCommand.APPROVE
        return VoiceCommand.QUESTION
    }

    private fun isStatus(t: String): Boolean {
        return t == "status" || t.contains("whats happening") || t.contains("what is happening") ||
            t.contains("what is the status") || t == "whats the status"
    }

    private fun isRollback(t: String): Boolean {
        return t.contains("rollback") || t.contains("roll back")
    }

    private fun isApprove(t: String): Boolean {
        if (t.contains("dont approve") || t.contains("do not approve")) {
            return false
        }
        val phrases = listOf(
            "approve",
            "apply it",
            "apply the fix",
            "apply the patch",
            "apply the change",
            "apply this",
            "accept the fix",
            "accept the patch",
            "accept the change",
            "ship it",
            "go ahead and apply",
            "yes apply",
            "confirm the fix",
            "confirm the patch"
        )
        return phrases.any { t.contains(it) }
    }

    private fun isReject(t: String): Boolean {
        val phrases = listOf(
            "reject",
            "dont apply",
            "do not apply",
            "dont approve",
            "do not approve",
            "discard the fix",
            "discard the patch"
        )
        return phrases.any { t.contains(it) }
    }
}
