package com.devdeck.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devdeck.app.model.DiagnosticResult
import com.devdeck.app.ui.theme.LuminaDesign

@Composable
fun RepairTimelineScreen(incidentId: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        HeaderSection(incidentId ?: "UNKNOWN")
        TimelineStep(
            "Failure Captured", 
            "Traceback recorded. Exception: NullReference in module auth_core.py.",
            isComplete = true
        )
        TimelineStep(
            "Analyzing...", 
            "Generating fix hypothesis based on repository history.",
            isActive = true,
            terminalContent = listOf("> Scanning auth_core.py line 124...", "> Synthesizing AST diff...")
        )
        TimelineStep("Sandbox Verification", "Awaiting analysis completion to run tests.", isPending = true)
    }
}

@Composable
fun HeaderSection(id: String) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Live Repair Incident", style = MaterialTheme.typography.headlineSmall)
                Text("ID: $id", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("ABORT", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun TimelineStep(
    title: String, 
    description: String, 
    isComplete: Boolean = false, 
    isActive: Boolean = false,
    isPending: Boolean = false,
    terminalContent: List<String>? = null
) {
    val statusColor = when {
        isComplete -> MaterialTheme.colorScheme.secondary
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(statusColor.copy(0.1f))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isActive) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = statusColor, modifier = Modifier.size(16.dp))
            } else if (isComplete) {
                Icon(Icons.Default.Check, null, tint = statusColor, modifier = Modifier.size(16.dp))
            }
        }
        
        GlassCard(
            modifier = Modifier.weight(1f).then(
                if (isActive) Modifier.background(statusColor.copy(0.05f)) else Modifier
            )
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = statusColor)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                
                if (terminalContent != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(0.9f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        terminalContent.forEach { line ->
                            Text(line, style = MaterialTheme.typography.labelSmall, color = Color.Green.copy(0.8f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScanlineOverlay() {
    val infiniteTransition = rememberInfiniteTransition()
    val yOffset by infiniteTransition.animateFloat(
        initialValue = -100f,
        targetValue = 2000f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawLine(
            color = Color(0xFF0059B5).copy(alpha = 0.1f),
            start = Offset(0f, yOffset),
            end = Offset(size.width, yOffset),
            strokeWidth = 4.dp.toPx()
        )
    }
}

@Composable
fun RepairReviewScreen(
    result: DiagnosticResult?, 
    trustScore: Int, 
    rootCause: String?,
    onApplyRepair: () -> Unit,
    onReject: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ScanlineOverlay()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Repair Review", style = MaterialTheme.typography.headlineMedium)
            
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TrustCard(trustScore, modifier = Modifier.weight(1f))
                RootCauseCard(rootCause ?: "Calculating...", modifier = Modifier.weight(1f))
            }
            
            DiffCard(result)
            
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
                    Text("REJECT")
                }
                Button(
                    onClick = onApplyRepair,
                    modifier = Modifier.weight(1.5f)
                ) {
                    Text("APPLY REPAIR")
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.Bolt, null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun TrustCard(score: Int, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { score / 100f }, color = MaterialTheme.colorScheme.secondary, strokeWidth = 4.dp, modifier = Modifier.size(48.dp))
                Text("$score%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Column {
                Text("TRUST SCORE", style = MaterialTheme.typography.labelSmall)
                Text("High confidence", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun RootCauseCard(cause: String, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Column {
            Text("ROOT CAUSE", style = MaterialTheme.typography.labelSmall)
            Text(cause, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DiffCard(result: DiagnosticResult?) {
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)).padding(8.dp)) {
                Text(result?.repairFile ?: "unknown_file.ts", style = MaterialTheme.typography.labelSmall)
            }
            Column(modifier = Modifier.padding(16.dp)) {
                DiffLine("- old code block", isRemoved = true)
                DiffLine("+ fixed code block", isAdded = true)
            }
        }
    }
}

@Composable
fun DiffLine(text: String, isRemoved: Boolean = false, isAdded: Boolean = false) {
    val bgColor = when {
        isRemoved -> MaterialTheme.colorScheme.error.copy(0.1f)
        isAdded -> MaterialTheme.colorScheme.secondary.copy(0.1f)
        else -> Color.Transparent
    }
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().background(bgColor).padding(vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = when {
            isRemoved -> MaterialTheme.colorScheme.error
            isAdded -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurface
        }
    )
}

@Composable
fun RepairSuccessScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CheckCircle, 
            null, 
            tint = MaterialTheme.colorScheme.secondary, 
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text("Repair Verified on Mainframe", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(32.dp))
        
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SuccessDetail("System Kernel Core", "Stable")
                SuccessDetail("Memory Allocation Table", "Repaired")
                SuccessDetail("Thermal Throttling Logic", "Optimized")
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("DONE")
        }
    }
}

@Composable
fun SuccessDetail(label: String, status: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
    }
}
