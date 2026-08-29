package com.devdeck.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devdeck.app.model.HistoryItem
import com.devdeck.app.model.IncidentStatus
import com.devdeck.app.ui.AppState
import com.devdeck.app.ui.theme.LuminaDesign
import java.text.SimpleDateFormat
import java.util.*

// ── Brain Screen ──────────────────────────────────────────────────────────────

@Composable
fun BrainScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Knowledge Graph", style = MaterialTheme.typography.headlineLarge, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Codebase semantic index and dependency analysis.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            // Status banner
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFF006e28), CircleShape))
                    Text("Knowledge Graph Status: Synced", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            // Stats row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrainStatCard("14,204", "NODES INDEXED", Color(0xFF0059b5), modifier = Modifier.weight(1f))
                BrainStatCard("89,112", "EDGE CONNECTIONS", Color(0xFF006e28), modifier = Modifier.weight(1f))
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrainStatCard("24", "DEAD ENDS", Color(0xFFBA1A1A), modifier = Modifier.weight(1f))
                GlassCard(modifier = Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("Run Garbage", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                        Text("Collection →", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color(0xFF0059b5))
                    }
                }
            }
        }
        item {
            // Dependency table header
            GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 0.dp) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(0.4f)).padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("TYPE", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("SYMBOL/FILE", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("STATUS", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = LuminaDesign.HairlineStroke)
                    listOf(
                        Triple("class", "DiagnosticNode", "Healthy"),
                        Triple("fn", "applyTaxLogic", "Synced"),
                        Triple("import", "finance_utils", "Orphaned"),
                        Triple("type", "HistoryItem", "Active")
                    ).forEachIndexed { i, (type, symbol, status) ->
                        if (i > 0) HorizontalDivider(color = LuminaDesign.HairlineStroke)
                        DependencyRow(type, symbol, status)
                    }
                }
            }
        }
    }
}

@Composable
fun BrainStatCard(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = color, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
fun DependencyRow(type: String, symbol: String, status: String) {
    val statusColor = when (status) {
        "Healthy" -> Color(0xFF006e28)
        "Synced" -> Color(0xFF0059b5)
        "Orphaned" -> Color(0xFFE65100)
        "Active" -> Color(0xFF006e28)
        else -> Color(0xFF616161)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(type, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
        }
        Text(symbol, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(statusColor.copy(0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(status, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = statusColor, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── History Screen ────────────────────────────────────────────────────────────

@Composable
fun HistoryScreen(historyItems: List<HistoryItem>) {
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(searchQuery, historyItems) {
        if (searchQuery.isBlank()) historyItems
        else historyItems.filter {
            it.rootCause.contains(searchQuery, ignoreCase = true) ||
            it.errorFile.contains(searchQuery, ignoreCase = true) ||
            it.errorText.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header (not scrollable)
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Repair Log", style = MaterialTheme.typography.headlineLarge, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "SYSTEM // DIAGNOSTICS // HISTORY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            // Search bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search logs...", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFAAAAAA)) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFFAAAAAA), modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0059b5),
                        unfocusedBorderColor = LuminaDesign.HairlineStroke,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                // Filter button
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .border(1.dp, LuminaDesign.HairlineStroke, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.FilterList, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Scrollable list
        if (filtered.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Outlined.History, null, modifier = Modifier.size(48.dp), tint = Color(0xFFCCCCCC))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    if (historyItems.isEmpty()) "No incidents recorded yet" else "No results for \"$searchQuery\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (historyItems.isEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Run a command through DevDeck CLI to capture incidents",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFAAAAAA),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filtered, key = { it.incidentId }) { item ->
                    HistoryCard(item)
                }
                item { Spacer(modifier = Modifier.height(60.dp)) }
            }
        }
    }
}

@Composable
fun HistoryCard(item: HistoryItem) {
    val formatter = remember { SimpleDateFormat("yyyy.MM.dd_HH:mm:ss", Locale.getDefault()) }
    val dateStr = formatter.format(Date(item.timestamp))

    val (statusLabel, statusColor, statusBg) = when (item.status) {
        IncidentStatus.SOLVED -> Triple("• FIXED", Color(0xFF006e28), Color(0xFFE8F5E9))
        IncidentStatus.REPAIR_SENT -> Triple("• PATCH SENT", Color(0xFF0059b5), Color(0xFFE3F2FD))
        IncidentStatus.FAILED -> Triple("• ROLLED BACK", Color(0xFFBA1A1A), Color(0xFFFCE8E6))
        IncidentStatus.SUPERSEDED -> Triple("• SUPERSEDED", Color(0xFFBF6A02), Color(0xFFFFF3E0))
        IncidentStatus.DIAGNOSED -> Triple("• DIAGNOSED", Color(0xFF616161), Color(0xFFEEEEEE))
        IncidentStatus.DETECTED -> Triple("• DETECTED", Color(0xFFE65100), Color(0xFFFFF3E0))
    }

    val isRolledBack = item.status == IncidentStatus.FAILED
    val tsColor = if (isRolledBack) Color(0xFF0059b5) else MaterialTheme.colorScheme.onSurface

    // Icon by type of error
    val icon = when {
        item.errorFile.endsWith(".py") -> Icons.Default.Code
        item.errorFile.endsWith(".kt") || item.errorFile.endsWith(".java") -> Icons.Default.DeveloperMode
        item.errorText.contains("memory", ignoreCase = true) -> Icons.Default.Memory
        item.errorText.contains("thermal", ignoreCase = true) -> Icons.Default.Thermostat
        item.errorText.contains("network", ignoreCase = true) || item.errorText.contains("connection", ignoreCase = true) -> Icons.Default.NetworkCheck
        else -> Icons.Default.BugReport
    }

    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp, contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Timestamp
            Text(
                dateStr,
                style = MaterialTheme.typography.labelSmall,
                color = tsColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            // Title row
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = item.rootCause.ifBlank { "${item.errorFile}:${item.errorLine}" }.take(55),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
            // Status badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(statusBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }
    }
}

// ── Settings Screen ───────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    state: AppState,
    onRepairPermissionChange: (Boolean) -> Unit,
    onPairDevice: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Configuration", style = MaterialTheme.typography.headlineLarge, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Manage diagnostic parameters and connection states.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 0.dp) {
                Column {
                    // Repair Permission
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(Icons.Outlined.Shield, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column {
                                Text("Repair Permission", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                Text("Allow remote diagnostic execution", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                        Switch(
                            checked = state.repairPermissionEnabled,
                            onCheckedChange = onRepairPermissionChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF0059b5)
                            )
                        )
                    }
                    HorizontalDivider(color = LuminaDesign.HairlineStroke)
                    // Paired Laptop
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(Icons.Default.LaptopMac, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Paired Laptop", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF006e28), RoundedCornerShape(8.dp))
                                .clickable { onPairDevice() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                if (state.isRelayConnected) state.pairedDevice else "Pair →",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF006e28),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                        }
                    }
                    HorizontalDivider(color = LuminaDesign.HairlineStroke)
                    // Privacy
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Privacy", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, LuminaDesign.HairlineStroke, RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f), CircleShape))
                            Text(state.privacyMode, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
                        }
                    }
                    HorizontalDivider(color = LuminaDesign.HairlineStroke)
                    // Model Status
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(Icons.Default.Memory, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Model Status", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFF006e28), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(modifier = Modifier.size(6.dp).background(
                                    if (state.isModelReady) Color(0xFF006e28) else Color(0xFFE65100),
                                    CircleShape
                                ))
                                Text(
                                    if (state.isModelReady) "Ready" else "Loading",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (state.isModelReady) Color(0xFF006e28) else Color(0xFFE65100),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Sandbox Proof Screen ──────────────────────────────────────────────────────

@Composable
fun SandboxProofScreen(
    sandboxLines: List<String>,
    sandboxRunning: Boolean
) {
    val scrollState = rememberLazyListState()

    // Auto-scroll terminal to latest line
    LaunchedEffect(sandboxLines.size) {
        if (sandboxLines.isNotEmpty()) {
            scrollState.animateScrollToItem(sandboxLines.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        Text("Sandbox Proof", style = MaterialTheme.typography.headlineLarge, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Execution environment verification and integrity checks.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Constraint chips
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SandboxConstraintChip(label = "No Network Access", active = true)
            SandboxConstraintChip(label = "Read-Only", active = true)
        }
        SandboxConstraintChip(label = "Mem-Isolate", active = true)

        // Terminal window
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, LuminaDesign.HairlineStroke, RoundedCornerShape(12.dp))
        ) {
            // Terminal title bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEDEDED))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.size(10.dp).background(Color(0xFFFF5F57), CircleShape))
                Box(modifier = Modifier.size(10.dp).background(Color(0xFFFFBD2E), CircleShape))
                Box(modifier = Modifier.size(10.dp).background(Color(0xFF28C840), CircleShape))
                Spacer(modifier = Modifier.weight(1f))
                Text("bash — npm test", style = MaterialTheme.typography.labelSmall, color = Color(0xFF666666), fontSize = 10.sp)
                Spacer(modifier = Modifier.weight(1f))
            }

            // Terminal body
            if (sandboxLines.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1E1E1E))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (sandboxRunning) {
                            CircularProgressIndicator(color = Color(0xFF00E676), strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Text(
                            if (sandboxRunning) "Running sandbox tests..." else "Waiting for repair to trigger sandbox...",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF888888),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1E1E1E))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(sandboxLines) { line ->
                        SandboxTerminalLine(line)
                    }
                }
            }
        }

        // Export Log button
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                onClick = { /* TODO: export to clipboard/file */ },
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, LuminaDesign.HairlineStroke)
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export Log", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
            }
        }
        Spacer(modifier = Modifier.height(60.dp))
    }
}

@Composable
fun SandboxConstraintChip(label: String, active: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (active) Color(0xFF006e28) else LuminaDesign.HairlineStroke, RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(6.dp).background(if (active) Color(0xFF006e28) else Color(0xFFAAAAAA), CircleShape))
            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
        }
    }
}

@Composable
fun SandboxTerminalLine(line: String) {
    // Color rules: PASS=green badge, command prefix=white, cyan=info, else gray
    when {
        line.trimStart().startsWith("PASS") -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF006e28))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("PASS", style = MaterialTheme.typography.labelSmall, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    line.trimStart().removePrefix("PASS").trim(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFCCCCCC),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
        line.trimStart().startsWith("FAIL") -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFBA1A1A))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("FAIL", style = MaterialTheme.typography.labelSmall, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    line.trimStart().removePrefix("FAIL").trim(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFF6B6B),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
        line.startsWith(">") || line.startsWith("$ ") -> {
            Text(line, style = MaterialTheme.typography.labelSmall, color = Color(0xFF00B0FF), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        line.contains("Test Suites") || line.contains("passed") -> {
            Text(line, style = MaterialTheme.typography.labelSmall, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        else -> {
            Text(line, style = MaterialTheme.typography.labelSmall, color = Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}
