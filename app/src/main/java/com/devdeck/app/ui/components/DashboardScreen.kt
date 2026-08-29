package com.devdeck.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devdeck.app.ui.theme.LuminaDesign

@Composable
fun DashboardScreen(
    telemetryLogs: List<String>,
    onAction: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        Color(0xFFE2E2E5)
                    )
                )
            )
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            StatusHeaderColumn()
        }
        item {
            TelemetryCard(
                modifier = Modifier.fillMaxWidth().height(300.dp),
                logs = telemetryLogs
            )
        }
        item {
            RecentActionCard(
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            QuickActionGrid(onAction = onAction)
        }
    }
}

@Composable
fun StatusHeaderColumn() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusChip(
            label = "Connected Device",
            value = "MacBook Pro (Paired)",
            icon = Icons.Rounded.LaptopMac,
            statusColor = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxWidth()
        )
        StatusChip(
            label = "Model Status",
            value = "Local LLM (Ready)",
            icon = Icons.Rounded.Psychology,
            statusColor = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxWidth()
        )
        StatusChip(
            label = "Active Project",
            value = "api-gateway-v3",
            icon = Icons.Rounded.FolderOpen,
            statusColor = MaterialTheme.colorScheme.primary,
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
    GlassCard(
        modifier = modifier,
        cornerRadius = 12.dp,
        contentPadding = 12.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                        fontSize = 13.sp
                    ),
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun TelemetryCard(modifier: Modifier = Modifier, logs: List<String>) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 16.dp,
        contentPadding = 20.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SYSTEM TELEMETRY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp
                )
                Icon(
                    Icons.Default.Timeline,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            HorizontalDivider(color = LuminaDesign.HairlineStroke, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(1.dp, LuminaDesign.HairlineStroke, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("CPU Load: 45%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text("MEM: 16GB/32GB", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text("NET: 1.2MB/s", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        logs.forEach { log ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(
                                    text = log,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = MaterialTheme.typography.labelSmall.fontFamily,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 10.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecentActionCard(modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 16.dp,
        contentPadding = 20.dp
    ) {
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
                    letterSpacing = 2.sp
                )
                Icon(
                    Icons.Rounded.BuildCircle,
                    null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            HorizontalDivider(color = LuminaDesign.HairlineStroke, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))
            
            Text("Task Completed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Docker Network Reset", 
                style = MaterialTheme.typography.headlineMedium, 
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold
            )
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Text("Fixed 5m ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("IMPACT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { 0.85f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Latency Improvement", style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
                    Text("-45ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            Button(
                onClick = { },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
            ) {
                Text("VIEW LOG", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun QuickActionGrid(onAction: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ExecutiveButton(
                text = "New Shell",
                icon = Icons.Default.Terminal,
                onClick = { onAction("new_shell") },
                modifier = Modifier.weight(1f)
            )
            ExecutiveButton(
                text = "Sync DB",
                icon = Icons.Default.Storage,
                onClick = { onAction("sync_db") },
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ExecutiveButton(
                text = "Run Tests",
                icon = Icons.Default.BugReport,
                onClick = { onAction("run_tests") },
                modifier = Modifier.weight(1f)
            )
            ExecutiveButton(
                text = "Deploy",
                icon = Icons.Default.PlayArrow,
                onClick = { onAction("deploy") },
                modifier = Modifier.weight(1f),
                isPrimary = true
            )
        }
    }
}
