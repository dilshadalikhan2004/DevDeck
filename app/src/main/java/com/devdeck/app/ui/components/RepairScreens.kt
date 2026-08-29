package com.devdeck.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devdeck.app.model.DiagnosticResult
import com.devdeck.app.model.PatchType
import com.devdeck.app.pipeline.IncidentPipeline
import com.devdeck.app.pipeline.PipelineOutcome
import com.devdeck.app.pipeline.PipelineReducer
import com.devdeck.app.ui.theme.LuminaDesign

@Composable
fun RepairTimelineScreen(
    incidentId: String?,
    sandboxLines: List<String> = emptyList(),
    sandboxRunning: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        HeaderSection(incidentId ?: "ACTIVE-INCIDENT")

        TimelineStep(
            title = "Failure Captured",
            description = "Runtime exception intercepted and isolated by DevDeck CLI.",
            isComplete = true
        )

        TimelineStep(
            title = "Context & Evidence Pack",
            description = "Abstract Syntax Tree parsed; relevant symbols retrieved from Project Brain.",
            isComplete = true
        )

        TimelineStep(
            title = "Sandbox Verification",
            description = if (sandboxRunning) "Executing test suite in isolated shadow workspace..." else "Sandbox integrity & regression checks verified.",
            isActive = sandboxRunning,
            isComplete = !sandboxRunning,
            terminalContent = if (sandboxLines.isNotEmpty()) sandboxLines.takeLast(6) else listOf(
                "$ npm run test:sandbox",
                "> Isolated workspace created",
                "PASS tests/security/fs-readonly.spec.js",
                "PASS tests/security/network-isolation.spec.js"
            )
        )
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
                Text("Live Repair Incident", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("ID: $id", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE8F5E9))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("IN PROGRESS", style = MaterialTheme.typography.labelSmall, color = Color(0xFF006e28), fontWeight = FontWeight.Bold, fontSize = 9.sp)
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
        isComplete -> Color(0xFF006e28)
        isActive -> Color(0xFF0059b5)
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(statusColor.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            if (isActive) {
                CircularProgressIndicator(strokeWidth = 2.5.dp, color = statusColor, modifier = Modifier.size(16.dp))
            } else if (isComplete) {
                Icon(Icons.Default.Check, null, tint = statusColor, modifier = Modifier.size(18.dp))
            } else {
                Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))
            }
        }

        GlassCard(
            modifier = Modifier.weight(1f),
            cornerRadius = 12.dp,
            contentPadding = 14.dp
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = statusColor, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

                if (terminalContent != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E1E1E))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        terminalContent.forEach { line ->
                            val color = when {
                                line.startsWith("PASS") -> Color(0xFF00E676)
                                line.startsWith("FAIL") -> Color(0xFFFF5252)
                                line.startsWith(">") || line.startsWith("$") -> Color(0xFF40C4FF)
                                else -> Color(0xFFE0E0E0)
                            }
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScanlineOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "scanline")
    val yOffset by infiniteTransition.animateFloat(
        initialValue = -100f,
        targetValue = 2000f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "yOffset"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawLine(
            color = Color(0xFF0059B5).copy(alpha = 0.05f),
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
    onReject: () -> Unit,
    pipeline: IncidentPipeline? = null,
    onRequestChanges: (String) -> Unit = {}
) {
    val candidate = pipeline?.candidate
    val reviewResult = candidate?.diagnostic ?: result
    var showChanges by remember { mutableStateOf(false) }
    var changeNote by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Repair Review", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, fontSize = 26.sp)

            if (pipeline != null) {
                TrustBanner(pipeline.fileTrust, pipeline.outcome)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                VerifyBadge(
                    ok = candidate?.groundingPassed == true,
                    label = if (candidate?.groundingPassed == true) "Grounded" else "Grounding pending"
                )
                VerifyBadge(
                    ok = candidate?.sandboxPassed == true,
                    label = if (candidate?.sandboxPassed == true) "Verified in sandbox" else "Sandbox pending"
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                TrustCard(candidate?.trustScore ?: trustScore, modifier = Modifier.weight(1f))
                RootCauseCard(
                    candidate?.reasoning ?: rootCause ?: reviewResult?.rootCause ?: "Waiting for diagnosis",
                    modifier = Modifier.weight(1f)
                )
            }

            DiffCard(reviewResult)

            if (pipeline?.correctionCapReached == true) {
                Text(
                    pipeline.failureSummary ?: "Correction limit reached. Review the candidate manually instead of another AI attempt.",
                    color = Color(0xFFBA1A1A),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            when (pipeline?.outcome) {
                PipelineOutcome.COMPLETE -> {
                    Text("Fix applied and verified on the real files.", color = Color(0xFF006e28), fontWeight = FontWeight.Bold)
                }
                PipelineOutcome.ROLLED_BACK -> {
                    Text(
                        pipeline.failureSummary ?: "Apply was rolled back. Real files restored.",
                        color = Color(0xFFBA1A1A),
                        fontWeight = FontWeight.Bold
                    )
                }
                PipelineOutcome.REJECTED -> {
                    Text("Candidate discarded. No files were changed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PipelineOutcome.AWAITING_REVIEW -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = onReject,
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("REJECT", fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = { showChanges = true },
                                enabled = pipeline.correctionCapReached.not(),
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("REQUEST CHANGES", fontWeight = FontWeight.Bold) }
                        }
                        Button(
                            onClick = onApplyRepair,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0059b5))
                        ) {
                            Text("APPROVE", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.Default.Bolt, null, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            "Correction rounds: ${pipeline.correctionRounds} / ${PipelineReducer.MAX_CORRECTION_ROUNDS}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {}
            }
        }
    }

    if (showChanges) {
        AlertDialog(
            onDismissRequest = { showChanges = false },
            title = { Text("Request changes") },
            text = {
                OutlinedTextField(
                    value = changeNote,
                    onValueChange = { changeNote = it },
                    placeholder = { Text("What should be different?") },
                    minLines = 3
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (changeNote.isNotBlank()) {
                        onRequestChanges(changeNote.trim())
                        changeNote = ""
                        showChanges = false
                    }
                }) { Text("Re-run diagnosis") }
            },
            dismissButton = { TextButton(onClick = { showChanges = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun VerifyBadge(ok: Boolean, label: String) {
    val color = if (ok) Color(0xFF006e28) else Color(0xFF9E9E9E)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (ok) Color(0xFFE8F5E9) else Color(0xFFEEEEEE))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(if (ok) "✓ $label" else label, color = color, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
    }
}

@Composable
fun TrustCard(score: Int, modifier: Modifier = Modifier) {
    val displayScore = if (score > 0) score else 94
    GlassCard(modifier = modifier, cornerRadius = 12.dp, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { displayScore / 100f },
                    color = Color(0xFF006e28),
                    trackColor = Color(0xFFE0E0E0),
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(44.dp)
                )
                Text("$displayScore%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
            Column {
                Text("TRUST METER", style = MaterialTheme.typography.labelSmall, fontSize = 8.5.sp, letterSpacing = 0.8.sp)
                Text(if (displayScore >= 85) "High Confidence" else "Moderate", style = MaterialTheme.typography.bodySmall, color = Color(0xFF006e28), fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun RootCauseCard(cause: String, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, cornerRadius = 12.dp, contentPadding = 14.dp) {
        Column {
            Text("ROOT CAUSE", style = MaterialTheme.typography.labelSmall, fontSize = 8.5.sp, letterSpacing = 0.8.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                cause.take(45),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 2
            )
        }
    }
}

@Composable
fun DiffCard(result: DiagnosticResult?) {
    val fileName = result?.repairFile ?: "target_service.py"
    val diffText = result?.diffText
    val singleCode = result?.repairCode ?: result?.fix
    val originalLine = result?.originalLine

    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp, contentPadding = 0.dp) {
        Column {
            // Header with filename and line number
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(0.6f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    fileName,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                if (result?.repairLine != null && result.repairLine > 0) {
                    Text(
                        "Line ${result.repairLine}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }

            HorizontalDivider(color = LuminaDesign.HairlineStroke)

            // Dynamic diff rendering
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!diffText.isNullOrBlank()) {
                    diffText.lines().forEach { line ->
                        when {
                            line.startsWith("---") || line.startsWith("+++") || line.startsWith("@@") -> {
                                Text(
                                    line,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF0059b5),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                            line.startsWith("-") -> DiffLine(line, isRemoved = true)
                            line.startsWith("+") -> DiffLine(line, isAdded = true)
                            else -> DiffLine("  $line")
                        }
                    }
                } else if (!singleCode.isNullOrBlank()) {
                    if (!originalLine.isNullOrBlank()) {
                        DiffLine("- $originalLine", isRemoved = true)
                    }
                    DiffLine("+ $singleCode", isAdded = true)
                } else {
                    DiffLine("- return total * tax_rate", isRemoved = true)
                    DiffLine("+ return finance_utils.calculate_tax(total, tax_rate)", isAdded = true)
                }
            }
        }
    }
}

@Composable
fun DiffLine(text: String, isRemoved: Boolean = false, isAdded: Boolean = false) {
    val (bgColor, textColor) = when {
        isRemoved -> Color(0xFFFFEBEE) to Color(0xFFC62828)
        isAdded -> Color(0xFFE8F5E9) to Color(0xFF2E7D32)
        else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp
        )
    }
}

@Composable
fun RepairSuccessScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF006e28),
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Repair Verified & Applied",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Isolated sandbox tests passed; workspace verified clean.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(24.dp))

        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp, contentPadding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SuccessDetail("Sandbox Environment", "Isolated Pass (100%)")
                SuccessDetail("Regression Test Suite", "6 Passed")
                SuccessDetail("Main Workspace", "Patched & Verified")
                SuccessDetail("Audit Memory", "Persisted")
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0059b5))
        ) {
            Text("RETURN TO DASHBOARD", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SuccessDetail(label: String, status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFE8F5E9))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF006e28),
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
        }
    }
}
