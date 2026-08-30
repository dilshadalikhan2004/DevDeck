package com.devdeck.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devdeck.app.voice.VoicePhase
import com.devdeck.app.voice.VoiceUiState

private val VoiceBg = Color(0xFF121214)
private val VoiceFg = Color(0xFFE8E8EC)
private val VoiceMuted = Color(0xFF9A9AA3)
private val VoiceAccent = Color(0xFF7EB6FF)

@Composable
fun VoiceDebuggerPanel(
    state: VoiceUiState,
    onMic: () -> Unit,
    onStop: () -> Unit,
    onMute: () -> Unit,
    onAskAgain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listening = state.phase is VoicePhase.Listening
    val thinking = state.phase is VoicePhase.Thinking
    val loading = state.phase is VoicePhase.LoadingModel
    val pulse = rememberInfiniteTransition(label = "voice-listen")
    val bar by pulse.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "bar"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(VoiceBg)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "VOICE",
            style = MaterialTheme.typography.labelSmall,
            color = VoiceMuted,
            letterSpacing = 1.5.sp,
            fontSize = 10.sp
        )
        Text(
            state.statusLine,
            style = MaterialTheme.typography.bodyMedium,
            color = VoiceFg,
            textAlign = TextAlign.Center
        )

        if (listening) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(VoiceAccent.copy(alpha = bar))
            )
        }

        FilledIconButton(
            onClick = onMic,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (listening) VoiceAccent else Color(0xFF2A2A30),
                contentColor = if (listening) Color(0xFF121214) else VoiceFg
            ),
            shape = CircleShape
        ) {
            when {
                loading || thinking -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = VoiceFg,
                    strokeWidth = 2.dp
                )
                else -> Icon(Icons.Default.Mic, contentDescription = "Ask about this incident")
            }
        }

        if (state.you != null) {
            SpeakerLine("YOU", state.you)
        } else if (listening && state.partial.isNotBlank()) {
            SpeakerLine("YOU", state.partial)
        }

        if (thinking) {
            Text(
                "Thinking…",
                style = MaterialTheme.typography.bodySmall,
                color = VoiceMuted,
                fontFamily = FontFamily.Monospace
            )
        }

        if (state.deck != null) {
            SpeakerLine("DEVDECK", state.deck)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onMute) {
                Icon(
                    if (state.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (state.muted) "Unmute" else "Mute",
                    tint = VoiceFg,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (state.muted) "Unmute" else "Mute", color = VoiceFg)
            }
            TextButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = VoiceFg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Stop", color = VoiceFg)
            }
            TextButton(onClick = onAskAgain) {
                Text("Ask again", color = VoiceAccent)
            }
        }

        if (!state.ttsAvailable && state.deck != null) {
            Text(
                "No on-device voice engine — answer is on screen only.",
                style = MaterialTheme.typography.labelSmall,
                color = VoiceMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SpeakerLine(who: String, text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2E2E34), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            who,
            style = MaterialTheme.typography.labelSmall,
            color = VoiceAccent,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = VoiceFg
        )
    }
}
