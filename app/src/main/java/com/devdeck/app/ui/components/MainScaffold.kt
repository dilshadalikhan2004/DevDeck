package com.devdeck.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.devdeck.app.pipeline.PipelineOutcome
import com.devdeck.app.ui.AppScreen
import com.devdeck.app.ui.AppState
import com.devdeck.app.ui.MainViewModel
import com.devdeck.app.ui.RepairFilter
import com.devdeck.app.ui.RepairState
import com.devdeck.app.ui.matchesRepairFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    viewModel: MainViewModel,
    onLaunchScanner: () -> Unit = {},
    onManualConnect: (String, String) -> Unit = { _, _ -> },
    onVoiceStop: () -> Unit = {},
    onVoiceMute: () -> Unit = {},
    onOpenModels: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()

    if (state.boot.visible) {
        BootOverlay(boot = state.boot)
        return
    }

    // Pair Device full-screen overlay (shown over everything)
    if (state.showPairDevice) {
        PairDeviceScreen(
            state = state,
            onDismiss = { viewModel.showPairDevice(false) },
            onLaunchScanner = onLaunchScanner,
            onManualConnect = onManualConnect
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                        Text("DEVDECK", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    // Relay connection indicator
                    IconButton(onClick = { viewModel.showPairDevice(true) }) {
                        Icon(
                            if (state.isRelayConnected) Icons.Default.Sensors else Icons.Default.SensorsOff,
                            contentDescription = "Relay status",
                            tint = if (state.isRelayConnected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(0.9f))
            )
        },
        bottomBar = {
            BottomNavigationBar(
                currentScreen = state.currentScreen,
                pendingReviewCount = state.pendingReviewCount,
                onScreenSelected = { viewModel.setScreen(it) }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (state.currentScreen) {
                AppScreen.HOME -> DashboardScreen(
                    state = state,
                    onOpenModels = onOpenModels,
                    onAction = { action ->
                        when (action) {
                            "history" -> viewModel.setScreen(AppScreen.HISTORY)
                            "brain" -> viewModel.setScreen(AppScreen.BRAIN)
                            "settings" -> viewModel.setScreen(AppScreen.SETTINGS)
                            "repair" -> viewModel.setScreen(AppScreen.REPAIR)
                            // Quick action relay commands
                            "new_shell", "sync_db", "run_tests", "deploy" -> {
                                // Handled in MainActivity via VM action flow
                                viewModel.sendQuickAction(action)
                            }
                            else -> {
                                if (action.startsWith("dismiss:")) {
                                    viewModel.dismissCompletedPipeline(action.removePrefix("dismiss:"))
                                }
                            }
                        }
                    }
                )

                AppScreen.REPAIR -> RepairWorkspace(
                    viewModel = viewModel,
                    state = state,
                    onVoiceStop = onVoiceStop,
                    onVoiceMute = onVoiceMute
                )

                AppScreen.BRAIN -> BrainScreen(state = state)

                AppScreen.HISTORY -> HistoryScreen(historyItems = state.historyItems)

                AppScreen.SETTINGS -> SettingsScreen(
                    state = state,
                    onRepairPermissionChange = { viewModel.setRepairPermission(it) },
                    onPairDevice = { viewModel.showPairDevice(true) },
                    onOpenModels = onOpenModels
                )
            }
        }
    }
}

@Composable
fun RepairWorkspace(
    viewModel: MainViewModel,
    state: AppState,
    onVoiceStop: () -> Unit = {},
    onVoiceMute: () -> Unit = {}
) {
    val pipeline = state.selectedPipeline
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        VoiceDebuggerPanel(
            state = state.voice,
            onMic = { viewModel.onMicTapped() },
            onStop = onVoiceStop,
            onMute = onVoiceMute,
            onAskAgain = {
                onVoiceStop()
                viewModel.onAskAgain()
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RepairFilter.entries.forEach { filter ->
                val selected = state.repairFilter == filter
                val label = when (filter) {
                    RepairFilter.ALL -> "All"
                    RepairFilter.ACTIVE -> "Active"
                    RepairFilter.REVIEW -> "Review"
                    RepairFilter.APPLIED -> "Applied"
                    RepairFilter.FAILED -> "Failed"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(0.12f) else MaterialTheme.colorScheme.surface)
                        .clickable { viewModel.setRepairFilter(filter) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        val visible = state.pipelines.incidents.filter { it.matchesRepairFilter(state.repairFilter) }
        if (state.pipelines.incidents.isNotEmpty() && visible.isEmpty()) {
            Text(
                "No incidents match this filter. New crashes switch the filter back to All if needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LivePipelineList(
            incidents = visible,
            selectedIncidentId = state.selectedIncidentId,
            selectedStage = state.selectedStage,
            onSelectIncident = { viewModel.selectIncident(it) },
            onSelectStage = { viewModel.selectStage(it) },
            onDismissIncident = { id -> viewModel.dismissCompletedPipeline(id) }
        )
        if (state.sandboxRunning || state.sandboxLines.isNotEmpty()) {
            Text(
                "SANDBOX DRY-RUN",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LiveSandboxConsole(
                lines = state.sandboxLines,
                running = state.sandboxRunning
            )
        }
        val selected = state.selectedStage
        if (pipeline != null && selected != null) {
            StageDetailPanel(pipeline, selected)
        }
        if (pipeline?.outcome == PipelineOutcome.AWAITING_REVIEW ||
            pipeline?.outcome == PipelineOutcome.COMPLETE ||
            pipeline?.outcome == PipelineOutcome.ROLLED_BACK ||
            pipeline?.candidate != null
        ) {
            RepairReviewScreen(
                result = state.currentResult,
                trustScore = state.trustScore,
                rootCause = state.rootCause,
                pipeline = pipeline,
                onApplyRepair = { viewModel.applyRepair() },
                onReject = { viewModel.rejectRepair() },
                onRequestChanges = { viewModel.requestChanges(it) }
            )
        }
        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun BottomNavigationBar(
    currentScreen: AppScreen,
    pendingReviewCount: Int = 0,
    onScreenSelected: (AppScreen) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface.copy(0.95f),
        tonalElevation = 0.dp
    ) {
        BottomNavItem("Home", Icons.Default.Home, currentScreen == AppScreen.HOME) { onScreenSelected(AppScreen.HOME) }
        NavigationBarItem(
            selected = currentScreen == AppScreen.REPAIR,
            onClick = { onScreenSelected(AppScreen.REPAIR) },
            icon = {
                BadgedBox(badge = {
                    if (pendingReviewCount > 0) {
                        Badge { Text(pendingReviewCount.toString()) }
                    }
                }) {
                    Icon(Icons.Default.BuildCircle, contentDescription = "Repair")
                }
            },
            label = { Text("Repair", style = MaterialTheme.typography.labelSmall) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f),
                indicatorColor = MaterialTheme.colorScheme.primary.copy(0.1f)
            )
        )
        BottomNavItem("Brain", Icons.Default.Psychology, currentScreen == AppScreen.BRAIN) { onScreenSelected(AppScreen.BRAIN) }
        BottomNavItem("History", Icons.Default.History, currentScreen == AppScreen.HISTORY) { onScreenSelected(AppScreen.HISTORY) }
        BottomNavItem("Settings", Icons.Default.Settings, currentScreen == AppScreen.SETTINGS) { onScreenSelected(AppScreen.SETTINGS) }
    }
}

@Composable
fun RowScope.BottomNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f),
            indicatorColor = MaterialTheme.colorScheme.primary.copy(0.1f)
        )
    )
}
