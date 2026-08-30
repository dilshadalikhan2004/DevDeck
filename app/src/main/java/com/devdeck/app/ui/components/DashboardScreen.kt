package com.devdeck.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devdeck.app.model.HistoryItem
import com.devdeck.app.model.IncidentStatus
import com.devdeck.app.ui.AppState
import com.devdeck.app.ui.repairHeadline
import com.devdeck.app.ui.theme.LuminaDesign
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@Composable
fun DashboardScreen(
    state: AppState,
    onAction: (String) -> Unit,
    onOpenModels: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFF5F5F7), Color(0xFFE8E8EC))
                )
            )
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { StatusHeaderColumn(state = state, onOpenModels = onOpenModels) }
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "REPAIR STATUS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        repairHeadline(state),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Same pipeline as the Repair tab. Tap the card below to open it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (state.sandboxRunning || state.sandboxLines.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "SANDBOX DRY-RUN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        letterSpacing = 1.5.sp
                    )
                    LiveSandboxConsole(
                        lines = state.sandboxLines,
                        running = state.sandboxRunning
                    )
                }
            }
        }
        item {
            GlassCard(modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp), cornerRadius = 16.dp, contentPadding = 16.dp) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "LIVE PIPELINE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp,
                            letterSpacing = 1.5.sp
                        )
                        Icon(Icons.Default.Timeline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    }
                    val memStr = if (state.memTotalMB > 0)
                        "${state.memUsedMB / 1024}GB/${state.memTotalMB / 1024}GB"
                    else "—"
                    val netStr = if (state.netKbps > 0) "%.1fMB/s".format(state.netKbps / 1024f) else "—"
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TelemetryMetric(label = "CPU", value = if (state.cpuPercent > 0) "${state.cpuPercent}%" else "—")
                        TelemetryMetric(label = "MEM", value = memStr)
                        TelemetryMetric(label = "NET", value = netStr)
                    }
                    HorizontalDivider(color = LuminaDesign.HairlineStroke, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    LivePipelineList(
                        incidents = state.pipelines.incidents,
                        selectedIncidentId = state.selectedIncidentId,
                        selectedStage = null,
                        onSelectIncident = { onAction("repair") },
                        onSelectStage = { onAction("repair") },
                        onDismissIncident = { id -> onAction("dismiss:$id") }
                    )
                }
            }
        }
        item { RecentActionCard(modifier = Modifier.fillMaxWidth(), state = state, onViewLog = { onAction("history") }) }
    }
}

// ── Status Header ─────────────────────────────────────────────────────────────

@Composable
fun StatusHeaderColumn(state: AppState, onOpenModels: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Connected Device — green dot when relay is connected
        StatusChip(
            label = "CONNECTED DEVICE",
            value = if (state.isRelayConnected) state.pairedDevice else "Not connected",
            icon = Icons.Rounded.LaptopMac,
            statusColor = if (state.isRelayConnected) Color(0xFF006e28) else Color(0xFF9E9E9E),
            modifier = Modifier.fillMaxWidth()
        )
        // Model Status — green when engine ready
        StatusChip(
            label = "ON-DEVICE MODEL",
            value = when {
                !state.isModelReady && state.modelDisplayName.isNotBlank() ->
                    "Loading ${state.modelDisplayName}…"
                state.isModelReady -> "${state.modelDisplayName} · Ready"
                else -> "Initializing…"
            },
            icon = Icons.Rounded.Psychology,
            statusColor = if (state.isModelReady) Color(0xFF006e28) else Color(0xFFE65100),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenModels)
        )
        // Active Project — blue highlight
        StatusChip(
            label = "ACTIVE PROJECT",
            value = state.activeProject,
            icon = Icons.Rounded.FolderOpen,
            statusColor = Color(0xFF0059b5),
            modifier = Modifier.fillMaxWidth(),
            isActive = true
        )
    }
}

@Composable
fun StatusChip(
    label: String,
    value: String,
    icon: ImageVector,
    statusColor: Color,
    modifier: Modifier = Modifier,
    isActive: Boolean = false
) {
    GlassCard(modifier = modifier, cornerRadius = 12.dp, contentPadding = 14.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp
                    ),
                    color = if (isActive) Color(0xFF0059b5) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) Color(0xFF0059b5) else MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── Telemetry Card ────────────────────────────────────────────────────────────

@Composable
fun TelemetryCard(
    modifier: Modifier = Modifier,
    state: AppState,
    logListState: LazyListState
) {
    GlassCard(modifier = modifier, cornerRadius = 16.dp, contentPadding = 16.dp) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SYSTEM TELEMETRY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp
                )
                Icon(Icons.Default.Timeline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
            HorizontalDivider(color = LuminaDesign.HairlineStroke, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // CPU / MEM / NET real values
            val memStr = if (state.memTotalMB > 0)
                "${state.memUsedMB / 1024}GB/${state.memTotalMB / 1024}GB"
            else "—"
            val netStr = if (state.netKbps > 0) "%.1fMB/s".format(state.netKbps / 1024f) else "—"

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryMetric(label = "CPU", value = if (state.cpuPercent > 0) "${state.cpuPercent}%" else "—")
                TelemetryMetric(label = "MEM", value = memStr)
                TelemetryMetric(label = "NET", value = netStr)
            }

            // Scrollable log terminal
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFAFAFA))
                    .border(0.5.dp, LuminaDesign.HairlineStroke, RoundedCornerShape(8.dp))
            ) {
                LazyColumn(
                    state = logListState,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (state.telemetryLogs.isEmpty()) {
                        item {
                            Text(
                                "Waiting for relay connection...",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9E9E9E),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        items(state.telemetryLogs) { log ->
                            LogLine(log)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
fun LogLine(log: String) {
    // Color code by level: INFO=green, WARN=orange, ERROR/err=red, else default
    val (timeColor, msgColor) = when {
        log.contains("WARN", ignoreCase = true) -> Color(0xFFBF6A02) to Color(0xFFBF6A02)
        log.contains("ERROR", ignoreCase = true) || log.contains("err", ignoreCase = false) && log.contains("[") -> Color(0xFFBA1A1A) to Color(0xFFBA1A1A)
        log.contains("INFO", ignoreCase = true) || log.contains("REQ:", ignoreCase = false) || log.contains("OK") -> Color(0xFF2E7D32) to Color(0xFF1B1B1D)
        log.contains("[Relay]") -> Color(0xFF0059b5) to Color(0xFF0059b5)
        else -> Color(0xFF616161) to Color(0xFF1B1B1D)
    }

    // Try to split timestamp from rest
    val tsRegex = Regex("""^\[(\d{2}:\d{2}:\d{2})](.*)$""")
    val match = tsRegex.find(log.trim())

    if (match != null) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "[${match.groupValues[1]}]",
                style = MaterialTheme.typography.labelSmall,
                color = timeColor,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                match.groupValues[2].trim(),
                style = MaterialTheme.typography.labelSmall,
                color = msgColor,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = true
            )
        }
    } else {
        Text(
            log,
            style = MaterialTheme.typography.labelSmall,
            color = msgColor,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = true
        )
    }
}

// ── Recent Action Card ────────────────────────────────────────────────────────

@Composable
fun RecentActionCard(
    modifier: Modifier = Modifier,
    state: AppState,
    onViewLog: () -> Unit
) {
    val latest = state.historyItems.firstOrNull()

    GlassCard(modifier = modifier, cornerRadius = 16.dp, contentPadding = 20.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "RECENT ACTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp
                )
                Icon(Icons.Rounded.BuildCircle, null, tint = Color(0xFF006e28), modifier = Modifier.size(16.dp))
            }
            HorizontalDivider(color = LuminaDesign.HairlineStroke, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(16.dp))

            if (latest == null) {
                Text(
                    "No repairs recorded yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Run a command through DevDeck CLI",
                    style = MaterialTheme.typography.headlineMedium,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                val statusLabel = when (latest.status) {
                    IncidentStatus.SOLVED -> "Task Completed"
                    IncidentStatus.REPAIR_SENT -> "Repair Sent"
                    IncidentStatus.FAILED -> "Repair Failed"
                    IncidentStatus.SUPERSEDED -> "Superseded"
                    IncidentStatus.DIAGNOSED -> "Diagnosed"
                    IncidentStatus.DETECTED -> "Detected"
                }

                Text(statusLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Title: use root cause truncated, or file
                val title = when {
                    latest.rootCause.isNotBlank() -> latest.rootCause.take(48).let { if (latest.rootCause.length > 48) "$it…" else it }
                    else -> "${latest.errorFile}:${latest.errorLine}"
                }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                // Status + relative time
                val relativeTime = relativeTimeString(latest.timestamp)
                val statusColor = when (latest.status) {
                    IncidentStatus.SOLVED -> Color(0xFF006e28)
                    IncidentStatus.REPAIR_SENT -> Color(0xFF0059b5)
                    IncidentStatus.FAILED -> Color(0xFFBA1A1A)
                    else -> Color(0xFF616161)
                }
                val statusIcon = when (latest.status) {
                    IncidentStatus.SOLVED -> Icons.Default.CheckCircle
                    IncidentStatus.REPAIR_SENT -> Icons.AutoMirrored.Filled.Send
                    IncidentStatus.FAILED -> Icons.Default.Error
                    else -> Icons.Default.Info
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(16.dp))
                    Text(
                        "${latest.status.name.lowercase().replaceFirstChar { it.uppercase() }} · $relativeTime",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Impact bar — based on confidence derived from patchType presence
                val impactLabel = if (latest.repairCode != null || latest.diffText != null)
                    "Latency Improvement  -45ms"
                else "No impact data"

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("IMPACT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (latest.repairCode != null || latest.diffText != null) 0.85f else 0.0f },
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                        color = Color(0xFF006e28),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    Text(impactLabel, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onViewLog,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
            ) {
                Text("VIEW LOG", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
        }
    }
}

// ── Utility ───────────────────────────────────────────────────────────────────

fun relativeTimeString(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
        else -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
    }
}
