package com.devdeck.app.voice

data class IncidentVoicePack(
    val incidentId: String?,
    val rootCause: String?,
    val confidence: Float?,
    val reasoning: String?,
    val file: String?,
    val line: Int?,
    val originalLine: String?,
    val repairCode: String?,
    val diffText: String?,
    val evidence: String?
)
