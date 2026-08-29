package com.devdeck.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devdeck.app.pipeline.FileTrustState
import com.devdeck.app.pipeline.IncidentPipeline
import com.devdeck.app.pipeline.NodeStatus
import com.devdeck.app.pipeline.PipelineOutcome
import com.devdeck.app.pipeline.PipelineStage
import com.devdeck.app.pipeline.StageSnapshot
import com.devdeck.app.ui.theme.LuminaDesign

@Composable
fun LivePipelineList(
    incidents: List<IncidentPipeline>,
    selectedIncidentId: String?,
    selectedStage: PipelineStage?,
    onSelectIncident: (String) -> Unit,
    onSelectStage: (PipelineStage) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (incidents.isEmpty()) {
            Text(
                "Waiting for a failing command from the laptop watcher.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            incidents.asReversed().forEach { pipeline ->
                PipelineIncidentCard(
                    pipeline = pipeline,
                    selected = pipeline.incidentId == selectedIncidentId,
                    selectedStage = selectedStage.takeIf { pipeline.incidentId == selectedIncidentId },
                    onSelectIncident = { onSelectIncident(pipeline.incidentId) },
                    onSelectStage = onSelectStage
                )
            }
        }
    }
}

@Composable
fun PipelineIncidentCard(
    pipeline: IncidentPipeline,
    selected: Boolean,
    selectedStage: PipelineStage?,
    onSelectIncident: () -> Unit,
    onSelectStage: (PipelineStage) -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelectIncident)
            .then(
                if (selected) Modifier.border(1.dp, Color(0xFF0059b5), RoundedCornerShape(16.dp))
                else Modifier
            ),
        cornerRadius = 16.dp,
        contentPadding = 14.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("LIVE PIPELINE", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, letterSpacing = 1.sp)
                    Text(
                        pipeline.incidentId.take(8),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                TrustBanner(pipeline.fileTrust, pipeline.outcome)
            }
            HorizontalDivider(color = LuminaDesign.HairlineStroke, thickness = 0.5.dp)
            pipeline.displayNodes().forEach { snap ->
                PipelineNodeRow(
                    snapshot = snap,
                    expanded = snap.stage == selectedStage,
                    onClick = { onSelectStage(snap.stage) }
                )
            }
            pipeline.failureSummary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFBA1A1A), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun TrustBanner(trust: FileTrustState, outcome: PipelineOutcome) {
    val (label, color, bg) = when {
        trust == FileTrustState.APPLIED || outcome == PipelineOutcome.COMPLETE ->
            Triple("Applied to real files", Color(0xFF006e28), Color(0xFFE8F5E9))
        trust == FileTrustState.ROLLED_BACK || outcome == PipelineOutcome.ROLLED_BACK ->
            Triple("Rolled back", Color(0xFFBA1A1A), Color(0xFFFCE8E6))
        trust == FileTrustState.SANDBOXED || outcome == PipelineOutcome.AWAITING_REVIEW ->
            Triple("Sandbox only — real files unchanged", Color(0xFFBF6A02), Color(0xFFFFF3E0))
        else -> Triple("No file writes yet", Color(0xFF616161), Color(0xFFEEEEEE))
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PipelineNodeRow(snapshot: StageSnapshot, expanded: Boolean, onClick: () -> Unit) {
    val color = when (snapshot.status) {
        NodeStatus.PENDING -> Color(0xFFBDBDBD)
        NodeStatus.ACTIVE -> Color(0xFF0059b5)
        NodeStatus.PASSED, NodeStatus.SKIPPED -> Color(0xFF006e28)
        NodeStatus.FAILED -> Color(0xFFBA1A1A)
    }
    val pulse = rememberInfiniteTransition(label = "node-pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(if (expanded) color.copy(alpha = 0.06f) else Color.Transparent)
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .alpha(if (snapshot.status == NodeStatus.ACTIVE) alpha else 1f)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                when (snapshot.status) {
                    NodeStatus.ACTIVE -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color)
                    NodeStatus.PASSED -> Icon(Icons.Default.Check, null, tint = color, modifier = Modifier.size(12.dp))
                    NodeStatus.FAILED -> Icon(Icons.Default.Close, null, tint = color, modifier = Modifier.size(12.dp))
                    NodeStatus.SKIPPED -> Icon(Icons.Default.Remove, null, tint = color, modifier = Modifier.size(12.dp))
                    NodeStatus.PENDING -> Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    snapshot.stage.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (snapshot.status == NodeStatus.ACTIVE) FontWeight.Bold else FontWeight.Medium,
                    color = if (snapshot.status == NodeStatus.PENDING) Color(0xFF9E9E9E) else MaterialTheme.colorScheme.onSurface
                )
                if (snapshot.summary.isNotBlank()) {
                    Text(snapshot.summary, style = MaterialTheme.typography.labelSmall, color = color, fontSize = 10.sp)
                }
            }
        }
        if (expanded && !snapshot.detail.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                snapshot.detail,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp)
            )
        }
    }
}

@Composable
fun StageDetailPanel(pipeline: IncidentPipeline, stage: PipelineStage) {
    val snap = pipeline.nodes[stage]
    val candidate = pipeline.candidate
    val body = when (stage) {
        PipelineStage.DIAGNOSING -> candidate?.rawModelOutput ?: snap?.detail ?: snap?.summary.orEmpty()
        PipelineStage.SANDBOX_DRY_RUN -> buildString {
            appendLine(snap?.summary.orEmpty())
            candidate?.sandboxCommand?.let { appendLine("Command: $it") }
            candidate?.sandboxExitCode?.let { appendLine("Exit code: $it") }
            snap?.detail?.let { appendLine(it) }
        }
        PipelineStage.GROUNDING_CHECK -> snap?.detail ?: snap?.summary.orEmpty()
        else -> snap?.detail ?: snap?.summary.orEmpty()
    }
    if (body.isNotBlank()) {
        Text(
            body.trim(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFAFAFA), RoundedCornerShape(8.dp))
                .padding(10.dp)
        )
    }
}
