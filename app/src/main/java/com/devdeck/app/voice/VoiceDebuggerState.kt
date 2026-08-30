package com.devdeck.app.voice

sealed class VoicePhase {
    data object Idle : VoicePhase()
    data object NeedPermission : VoicePhase()
    data object PermissionDenied : VoicePhase()
    data object LoadingModel : VoicePhase()
    data object Listening : VoicePhase()
    data object NoSpeech : VoicePhase()
    data object Thinking : VoicePhase()
    data object Answered : VoicePhase()
    data class Failed(val message: String) : VoicePhase()
}

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.Idle,
    val partial: String = "",
    val you: String? = null,
    val deck: String? = null,
    val muted: Boolean = false,
    val ttsAvailable: Boolean = false,
    val speaking: Boolean = false,
    val hasIncident: Boolean = false,
    val statusLine: String = "Ask about the current incident"
)
