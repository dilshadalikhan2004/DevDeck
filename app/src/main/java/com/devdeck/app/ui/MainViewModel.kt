package com.devdeck.app.ui

import androidx.lifecycle.ViewModel
import com.devdeck.app.model.DiagnosticResult
import com.devdeck.app.model.HistoryItem
import com.devdeck.app.pipeline.EventPhase
import com.devdeck.app.pipeline.IncidentPipeline
import com.devdeck.app.pipeline.PipelineEvent
import com.devdeck.app.pipeline.PipelineOutcome
import com.devdeck.app.pipeline.PipelineRegistry
import com.devdeck.app.pipeline.PipelineReducer
import com.devdeck.app.pipeline.PipelineStage
import com.devdeck.app.pipeline.RepairCandidate
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

data class CorrectionRequest(
    val incidentId: String,
    val note: String,
    val previous: DiagnosticResult
)

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
    val cpuPercent: Int = 0,
    val memUsedMB: Long = 0L,
    val memTotalMB: Long = 0L,
    val netKbps: Float = 0f,
    val activeProject: String = "No project scanned",
    val historyItems: List<HistoryItem> = emptyList(),
    val sandboxLines: List<String> = emptyList(),
    val sandboxRunning: Boolean = false,
    val showPairDevice: Boolean = false,
    val repairPermissionEnabled: Boolean = true,
    val privacyMode: String = "Local Only",
    val autonomyPolicy: String = "approve_each",
    val rerunCommand: String? = null,
    val rerunSuccess: Boolean = false,
    val pipelines: PipelineRegistry = PipelineRegistry(),
    val selectedIncidentId: String? = null,
    val selectedStage: PipelineStage? = null,
    val pendingReviewCount: Int = 0
) {
    val selectedPipeline: IncidentPipeline?
        get() = selectedIncidentId?.let { pipelines.byId[it] }
            ?: pipelines.incidents.lastOrNull()
}

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

    fun applyPipelineEvent(event: PipelineEvent) {
        _uiState.update { state ->
            val next = state.pipelines.apply(event)
            val pipeline = next.byId[event.incidentId]
            val candidate = pipeline?.candidate
            val derivedRepair = when (pipeline?.outcome) {
                PipelineOutcome.AWAITING_REVIEW -> RepairState.REVIEWING
                PipelineOutcome.COMPLETE -> RepairState.SUCCESS
                PipelineOutcome.IN_PROGRESS -> RepairState.CAPTURING
                PipelineOutcome.FAILED, PipelineOutcome.ROLLED_BACK, PipelineOutcome.REJECTED -> RepairState.CAPTURING
                null -> state.repairState
            }
            state.copy(
                pipelines = next,
                activeIncidentId = event.incidentId,
                selectedIncidentId = state.selectedIncidentId ?: event.incidentId,
                pendingReviewCount = next.pendingReviewCount,
                repairState = derivedRepair,
                currentResult = candidate?.diagnostic ?: state.currentResult,
                trustScore = candidate?.trustScore ?: state.trustScore,
                rootCause = candidate?.reasoning ?: candidate?.diagnostic?.rootCause ?: state.rootCause,
                sandboxRunning = pipeline?.nodes?.get(PipelineStage.SANDBOX_DRY_RUN)?.status?.name == "ACTIVE"
            )
        }
        addLog("[Pipeline] ${event.stage.wireName} ${event.phase.name.lowercase()}: ${event.message}")
    }

    fun selectIncident(id: String) {
        _uiState.update { it.copy(selectedIncidentId = id, activeIncidentId = id) }
    }

    fun selectStage(stage: PipelineStage?) {
        _uiState.update { it.copy(selectedStage = stage) }
    }

    fun dismissCompletedPipeline(incidentId: String) {
        _uiState.update { state ->
            val newById = state.pipelines.byId - incidentId
            val newOrder = state.pipelines.order - incidentId
            val nextPipelines = state.pipelines.copy(byId = newById, order = newOrder)
            state.copy(
                pipelines = nextPipelines,
                selectedIncidentId = if (state.selectedIncidentId == incidentId) nextPipelines.order.lastOrNull() else state.selectedIncidentId,
                activeIncidentId = if (state.activeIncidentId == incidentId) nextPipelines.order.lastOrNull() else state.activeIncidentId,
                pendingReviewCount = nextPipelines.pendingReviewCount
            )
        }
    }

    fun clearFinishedPipelines() {
        _uiState.update { state ->
            val finishedIds = state.pipelines.byId.filterValues {
                it.outcome in setOf(PipelineOutcome.COMPLETE, PipelineOutcome.ROLLED_BACK, PipelineOutcome.REJECTED)
            }.keys
            val newById = state.pipelines.byId - finishedIds
            val newOrder = state.pipelines.order - finishedIds
            val nextPipelines = state.pipelines.copy(byId = newById, order = newOrder)
            state.copy(
                pipelines = nextPipelines,
                selectedIncidentId = if (state.selectedIncidentId in finishedIds) nextPipelines.order.lastOrNull() else state.selectedIncidentId,
                activeIncidentId = if (state.activeIncidentId in finishedIds) nextPipelines.order.lastOrNull() else state.activeIncidentId,
                pendingReviewCount = nextPipelines.pendingReviewCount
            )
        }
    }

    fun onIncidentDetected(id: String) {
        applyPipelineEvent(
            PipelineEvent(id, PipelineStage.SENT_TO_PHONE, EventPhase.COMPLETED, "Incident received on phone")
        )
        _uiState.update {
            it.copy(
                activeIncidentId = id,
                selectedIncidentId = id,
                sandboxLines = emptyList(),
                sandboxRunning = false
            )
        }
    }

    fun onAnalysisComplete(result: DiagnosticResult, score: Int, cause: String) {
        val incidentId = result.incidentId ?: _uiState.value.activeIncidentId ?: return
        val candidate = RepairCandidate(
            incidentId = incidentId,
            repairFile = result.repairFile,
            repairLine = result.repairLine,
            originalLine = result.originalLine,
            repairCode = result.repairCode,
            diffText = result.diffText,
            patchType = result.patchType,
            reasoning = result.reasoning ?: result.fix,
            rawModelOutput = result.rawOutput,
            groundingPassed = !result.abstained,
            sandboxPassed = false,
            sandboxCommand = null,
            sandboxExitCode = null,
            trustScore = score,
            expectedSha256 = result.expectedSha256,
            projectId = result.projectId,
            confidence = result.confidence,
            correctionRound = _uiState.value.selectedPipeline?.correctionRounds ?: 0,
            diagnostic = result
        )
        applyPipelineEvent(
            PipelineEvent(
                incidentId = incidentId,
                stage = PipelineStage.DIAGNOSING,
                phase = EventPhase.CANDIDATE_READY,
                message = cause,
                candidate = candidate
            )
        )
        _uiState.update {
            it.copy(currentResult = result, trustScore = score, rootCause = cause)
        }
    }

    fun onRepairApplied() {
        val id = _uiState.value.activeIncidentId ?: return
        applyPipelineEvent(PipelineEvent(id, PipelineStage.APPLYING, EventPhase.STARTED, "Applying to real files"))
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

    fun rejectRepair() {
        val id = _uiState.value.activeIncidentId ?: return
        applyPipelineEvent(
            PipelineEvent(
                id,
                PipelineStage.AWAITING_REVIEW,
                EventPhase.REVIEW_REJECTED,
                "Developer rejected the candidate. No real files changed."
            )
        )
        _uiState.update {
            it.copy(currentResult = null, repairState = RepairState.IDLE)
        }
    }

    private val _correctionAction = MutableStateFlow<CorrectionRequest?>(null)
    val correctionAction: StateFlow<CorrectionRequest?> = _correctionAction.asStateFlow()

    fun requestChanges(note: String): Boolean {
        val state = _uiState.value
        val pipeline = state.selectedPipeline ?: return false
        val result = state.currentResult ?: return false
        if (pipeline.correctionRounds >= PipelineReducer.MAX_CORRECTION_ROUNDS) {
            applyPipelineEvent(
                PipelineEvent(
                    pipeline.incidentId,
                    PipelineStage.DIAGNOSING,
                    EventPhase.REQUEST_CHANGES,
                    "Correction limit reached. Review the candidate manually instead of another AI attempt."
                )
            )
            return false
        }
        applyPipelineEvent(
            PipelineEvent(
                pipeline.incidentId,
                PipelineStage.DIAGNOSING,
                EventPhase.REQUEST_CHANGES,
                "Developer requested changes: $note"
            )
        )
        _correctionAction.value = CorrectionRequest(pipeline.incidentId, note, result)
        return true
    }

    fun clearCorrectionAction() {
        _correctionAction.value = null
    }

    fun resetRepair() {
        _uiState.update {
            it.copy(
                repairState = RepairState.IDLE,
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

    fun setAutonomyPolicy(policy: String) {
        _uiState.update { it.copy(autonomyPolicy = policy) }
    }

    fun onRerunCompleted(command: String?, success: Boolean) {
        _uiState.update {
            it.copy(
                rerunCommand = command,
                rerunSuccess = success,
                repairState = RepairState.SUCCESS
            )
        }
    }

    private val _quickAction = MutableStateFlow<String?>(null)
    val quickAction: StateFlow<String?> = _quickAction.asStateFlow()

    fun sendQuickAction(action: String) {
        _quickAction.value = action
    }

    fun clearQuickAction() {
        _quickAction.value = null
    }
}
