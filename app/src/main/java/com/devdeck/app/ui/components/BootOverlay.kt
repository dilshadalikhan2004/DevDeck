package com.devdeck.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devdeck.app.ui.BootState

@Composable
fun BootOverlay(boot: BootState) {
    val navy = Color(0xFF0B1220)
    val navy2 = Color(0xFF152238)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(navy, navy2, Color(0xFF0E1628)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
        ) {
            Text(
                "DEVDECK",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF7EB6FF),
                letterSpacing = 4.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "On-device repair",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (boot.modelName.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    boot.modelName,
                    color = Color(0xFFB8C4D6),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(28.dp))
            if (!boot.failed) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF4C8DFF),
                    trackColor = Color.White.copy(alpha = 0.12f)
                )
            }
            Spacer(Modifier.height(28.dp))
            BootStepRow("Load model", active = boot.step == 0 && !boot.failed, done = boot.step > 0, failed = boot.failed && boot.step == 0)
            Spacer(Modifier.height(12.dp))
            BootStepRow("Connect laptop", active = boot.step == 1 && !boot.failed, done = false, failed = boot.failed && boot.step == 1)
            Spacer(Modifier.height(24.dp))
            Text(
                boot.line,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
            boot.detail?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = Color(0xFF9AA8BD),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun BootStepRow(label: String, active: Boolean, done: Boolean, failed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    when {
                        failed -> Color(0xFFBA1A1A)
                        done -> Color(0xFF006e28)
                        active -> Color(0xFF4C8DFF)
                        else -> Color.White.copy(alpha = 0.12f)
                    },
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                done -> Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                active && !failed -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = Color.White
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = if (active || done || failed) Color.White else Color(0xFF8A97AB),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
