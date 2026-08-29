package com.devdeck.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.devdeck.app.pipeline.PipelineOutcome
import com.devdeck.app.ui.AppScreen
import com.devdeck.app.ui.AppState
import com.devdeck.app.ui.MainViewModel
import com.devdeck.app.ui.RepairState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    viewModel: MainViewModel,
    onLaunchScanner: () -> Unit = {},
    onManualConnect: (String, String) -> Unit = { _, _ -> }
) {
    val state by viewModel.uiState.collectAsState()

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

                AppScreen.REPAIR -> RepairWorkspace(viewModel = viewModel, state = state)

                AppScreen.BRAIN -> BrainScreen()

                AppScreen.HISTORY -> HistoryScreen(historyItems = state.historyItems)

                AppScreen.SETTINGS -> SettingsScreen(
                    state = state,
                    onRepairPermissionChange = { viewModel.setRepairPermission(it) },
                    onPairDevice = { viewModel.showPairDevice(true) }
                )
            }
        }
    }
}

@Composable
fun RepairWorkspace(viewModel: MainViewModel, state: AppState) {
    val pipeline = state.selectedPipeline
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LivePipelineList(
            incidents = state.pipelines.incidents,
            selectedIncidentId = state.selectedIncidentId,
            selectedStage = state.selectedStage,
            onSelectIncident = { viewModel.selectIncident(it) },
            onSelectStage = { viewModel.selectStage(it) },
            onDismissIncident = { id -> viewModel.dismissCompletedPipeline(id) }
        )
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
