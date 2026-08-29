package com.devdeck.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.devdeck.app.ui.AppScreen
import com.devdeck.app.ui.MainViewModel
import com.devdeck.app.ui.RepairState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()

    // Pair Device full-screen overlay (shown over everything)
    if (state.showPairDevice) {
        PairDeviceScreen(onDismiss = { viewModel.showPairDevice(false) })
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
                        }
                    }
                )

                AppScreen.REPAIR -> {
                    when (state.repairState) {
                        RepairState.IDLE -> SandboxProofScreen(
                            sandboxLines = state.sandboxLines,
                            sandboxRunning = state.sandboxRunning
                        )
                        RepairState.CAPTURING -> RepairTimelineScreen(state.activeIncidentId)
                        RepairState.REVIEWING -> RepairReviewScreen(
                            result = state.currentResult,
                            trustScore = state.trustScore,
                            rootCause = state.rootCause,
                            onApplyRepair = { viewModel.applyRepair() },
                            onReject = { viewModel.resetRepair() }
                        )
                        RepairState.SUCCESS -> RepairSuccessScreen(onDone = { viewModel.resetRepair() })
                    }
                }

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
fun BottomNavigationBar(
    currentScreen: AppScreen,
    onScreenSelected: (AppScreen) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface.copy(0.95f),
        tonalElevation = 0.dp
    ) {
        BottomNavItem("Home", Icons.Default.Home, currentScreen == AppScreen.HOME) { onScreenSelected(AppScreen.HOME) }
        BottomNavItem("Repair", Icons.Default.BuildCircle, currentScreen == AppScreen.REPAIR) { onScreenSelected(AppScreen.REPAIR) }
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
