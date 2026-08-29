package com.devdeck.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devdeck.app.model.DiagnosticResult
import com.devdeck.app.model.HistoryItem
import com.devdeck.app.model.IncidentStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AppScreen {
    HOME, REPAIR, BRAIN, HISTORY, SETTINGS
}

enum class RepairState {
    IDLE, CAPTURING, REVIEWING, SUCCESS
}

data class AppState(
    val currentScreen: AppScreen = AppScreen.HOME,
    val repairState: RepairState = RepairState.IDLE,
    val telemetryLogs: List<String> = emptyList(),
    val activeIncidentId: String? = null,
    val currentResult: DiagnosticResult? = null,
    val trustScore: Int = 0,
    val rootCause: String? = null,
    val isModelReady: Boolean = false,
    val isRelayConnected: Boolean = false,
    val pairedDevice: String = "None",
    // Live system telemetry
    val cpuPercent: Int = 0,
    val memUsedMB: Long = 0L,
    val memTotalMB: Long = 0L,
    val netKbps: Float = 0f,
    // Project context
    val activeProject: String = "No project scanned",
    // Persistent history
    val historyItems: List<HistoryItem> = emptyList(),
    // Sandbox proof lines streamed during repair
    val sandboxLines: List<String> = emptyList(),
    val sandboxRunning: Boolean = false,
    // Pair device dialog
    val showPairDevice: Boolean = false,
    // Settings
    val repairPermissionEnabled: Boolean = true,
    val privacyMode: String = "Local Only"
)

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AppState())
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    fun setScreen(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun addLog(log: String) {
        _uiState.update {
            it.copy(telemetryLogs = (it.telemetryLogs + log).takeLast(200))
        }
    }

    fun onIncidentDetected(id: String) {
        _uiState.update {
            it.copy(
                activeIncidentId = id,
                repairState = RepairState.CAPTURING,
                currentScreen = AppScreen.REPAIR,
                sandboxLines = emptyList(),
                sandboxRunning = false
            )
        }
    }

    fun onAnalysisComplete(result: DiagnosticResult, score: Int, cause: String) {
        _uiState.update {
            it.copy(
                currentResult = result,
                trustScore = score,
                rootCause = cause,
                repairState = RepairState.REVIEWING
            )
        }
    }

    fun onRepairApplied() {
        _uiState.update { it.copy(repairState = RepairState.SUCCESS) }
    }

    private val _repairAction = MutableStateFlow<DiagnosticResult?>(null)
    val repairAction: StateFlow<DiagnosticResult?> = _repairAction.asStateFlow()

    fun applyRepair() {
        _uiState.value.currentResult?.let { result ->
            _repairAction.value = result
            onRepairApplied()
        }
    }

    fun clearRepairAction() {
        _repairAction.value = null
    }

    fun resetRepair() {
        _uiState.update {
            it.copy(
                repairState = RepairState.IDLE,
                activeIncidentId = null,
                currentResult = null,
                sandboxLines = emptyList(),
                sandboxRunning = false
            )
        }
    }

    fun updateStatus(modelReady: Boolean, relayConnected: Boolean, device: String) {
        _uiState.update {
            it.copy(
                isModelReady = modelReady,
                isRelayConnected = relayConnected,
                pairedDevice = device
            )
        }
    }

    fun updateTelemetry(cpu: Int, memUsed: Long, memTotal: Long, net: Float) {
        _uiState.update {
            it.copy(
                cpuPercent = cpu,
                memUsedMB = memUsed,
                memTotalMB = memTotal,
                netKbps = net
            )
        }
    }

    fun setActiveProject(name: String) {
        if (name.isNotBlank()) {
            _uiState.update { it.copy(activeProject = name) }
        }
    }

    fun updateHistory(items: List<HistoryItem>) {
        _uiState.update { it.copy(historyItems = items) }
    }

    fun addSandboxLine(line: String) {
        _uiState.update {
            it.copy(sandboxLines = it.sandboxLines + line, sandboxRunning = true)
        }
    }

    fun setSandboxRunning(running: Boolean) {
        _uiState.update { it.copy(sandboxRunning = running) }
    }

    fun showPairDevice(show: Boolean) {
        _uiState.update { it.copy(showPairDevice = show) }
    }

    fun setRepairPermission(enabled: Boolean) {
        _uiState.update { it.copy(repairPermissionEnabled = enabled) }
    }

    fun setPrivacyMode(mode: String) {
        _uiState.update { it.copy(privacyMode = mode) }
    }

    // Quick action relay commands (New Shell, Sync DB, Run Tests, Deploy)
    private val _quickAction = MutableStateFlow<String?>(null)
    val quickAction: StateFlow<String?> = _quickAction.asStateFlow()

    fun sendQuickAction(action: String) {
        _quickAction.value = action
    }

    fun clearQuickAction() {
        _quickAction.value = null
    }
}
