package com.devdeck.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devdeck.app.ui.AppState
import com.devdeck.app.ui.theme.LuminaDesign

@Composable
fun PairDeviceScreen(
    state: AppState,
    onDismiss: () -> Unit,
    onLaunchScanner: () -> Unit,
    onManualConnect: (url: String, secret: String) -> Unit
) {
    var showManualInput by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("10.0.2.2:8765") }
    var manualSecret by remember { mutableStateOf("DECK-POCKET-SAFE") }

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

        // ── Content Scroll ────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        .background(Color.White)
                        .border(1.dp, LuminaDesign.HairlineStroke, CircleShape),
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

            Spacer(modifier = Modifier.height(24.dp))

            // Current connection status banner
            if (state.isRelayConnected) {
                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp, contentPadding = 12.dp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(Color(0xFF006e28), CircleShape))
                        Column {
                            Text(
                                "PAIRED AND CONNECTED",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = Color(0xFF006e28),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                state.pairedDevice,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── QR Viewfinder / Camera Trigger ────────────────────────────
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onLaunchScanner() }
            ) {
                // White background card
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE8F0FE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = "Camera Scanner",
                                modifier = Modifier.size(48.dp),
                                tint = Color(0xFF0059b5)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "TAP TO SCAN QR",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0059b5),
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Blue corner brackets drawn on top
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cornerLen = 36.dp.toPx()
                    val strokeW = 3.5.dp.toPx()
                    val padding = 4.dp.toPx()
                    val radius = 12.dp.toPx()
                    val blue = Color(0xFF0059b5)

                    // Top-left
                    drawLine(blue, Offset(padding + radius, padding), Offset(padding + cornerLen, padding), strokeW)
                    drawLine(blue, Offset(padding, padding + radius), Offset(padding, padding + cornerLen), strokeW)
                    drawArc(blue, startAngle = 180f, sweepAngle = 90f, useCenter = false,
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

            Spacer(modifier = Modifier.height(20.dp))

            // Title and scan instructions
            Text(
                "Pair Device",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Point camera at the QR code generated by relay_server.py",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Action Button: Open Camera
            Button(
                onClick = onLaunchScanner,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0059b5))
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Camera Scanner", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Manual Connection Toggle Card
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp, contentPadding = 14.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showManualInput = !showManualInput },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Lan, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Text(
                                "Manual IP / Port Connect",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        }
                        Icon(
                            if (showManualInput) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (showManualInput) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            label = { Text("Host / IP:Port (e.g. 10.0.2.2:8765)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualSecret,
                            onValueChange = { manualSecret = it },
                            label = { Text("Pairing Secret") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val formattedUrl = if (manualIp.startsWith("ws://") || manualIp.startsWith("wss://")) {
                                    manualIp
                                } else {
                                    "ws://$manualIp"
                                }
                                onManualConnect(formattedUrl, manualSecret)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006e28))
                        ) {
                            Text("Connect Bridge", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // CLI Help box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        "# Run on your laptop to start bridge + display QR:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF888888),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                    Text(
                        "python relay_server.py",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF00E676),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
