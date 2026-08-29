package com.devdeck.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen Pair Device overlay with QR code scanner UI.
 * Uses Canvas to draw the corner bracket viewfinder — no camera permission
 * needed for the UI shell; actual QR scanning is handled by [CameraActivity].
 */
@Composable
fun PairDeviceScreen(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F7))
    ) {
        // ── Grid background (dot pattern) ─────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val spacing = 28.dp.toPx()
            val dotRadius = 1.5.dp.toPx()
            val cols = (size.width / spacing).toInt() + 1
            val rows = (size.height / spacing).toInt() + 1
            for (row in 0..rows) {
                for (col in 0..cols) {
                    drawCircle(
                        color = Color(0x18000000),
                        radius = dotRadius,
                        center = Offset(col * spacing, row * spacing)
                    )
                }
            }
        }

        // ── Top bar ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "DEVDECK",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            // Close button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // ── QR Viewfinder ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-40).dp)
                .size(280.dp)
        ) {
            // White background card
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                // QR icon placeholder
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF0F0F0)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color(0xFFCCCCCC)
                        )
                    }
                }
            }

            // Blue corner brackets drawn on top
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cornerLen = 40.dp.toPx()
                val strokeW = 3.dp.toPx()
                val padding = 4.dp.toPx()
                val radius = 12.dp.toPx()
                val paint = androidx.compose.ui.graphics.Paint().also {
                    it.color = Color(0xFF0059b5)
                    it.strokeWidth = strokeW
                    it.strokeCap = StrokeCap.Round
                    it.strokeJoin = StrokeJoin.Round
                    it.style = androidx.compose.ui.graphics.PaintingStyle.Stroke
                }
                val blue = Color(0xFF0059b5)

                // Top-left
                drawLine(blue, Offset(padding + radius, padding), Offset(padding + cornerLen, padding), strokeW)
                drawLine(blue, Offset(padding, padding + radius), Offset(padding, padding + cornerLen), strokeW)
                drawArc(Color(0xFF0059b5), startAngle = 180f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(padding, padding), size = Size(radius * 2, radius * 2), style = Stroke(strokeW))

                // Top-right
                val right = size.width - padding
                drawLine(blue, Offset(right - cornerLen, padding), Offset(right - radius, padding), strokeW)
                drawLine(blue, Offset(right, padding + radius), Offset(right, padding + cornerLen), strokeW)
                drawArc(blue, startAngle = 270f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(right - radius * 2, padding), size = Size(radius * 2, radius * 2), style = Stroke(strokeW))

                // Bottom-left
                val bottom = size.height - padding
                drawLine(blue, Offset(padding + radius, bottom), Offset(padding + cornerLen, bottom), strokeW)
                drawLine(blue, Offset(padding, bottom - cornerLen), Offset(padding, bottom - radius), strokeW)
                drawArc(blue, startAngle = 90f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(padding, bottom - radius * 2), size = Size(radius * 2, radius * 2), style = Stroke(strokeW))

                // Bottom-right
                drawLine(blue, Offset(right - cornerLen, bottom), Offset(right - radius, bottom), strokeW)
                drawLine(blue, Offset(right, bottom - cornerLen), Offset(right, bottom - radius), strokeW)
                drawArc(blue, startAngle = 0f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(right - radius * 2, bottom - radius * 2), size = Size(radius * 2, radius * 2), style = Stroke(strokeW))
            }
        }

        // ── Bottom label ──────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Pair Device",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Scan the QR code shown on your laptop's terminal",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Run: python relay_server.py --qr on your laptop",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF0059b5),
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}
