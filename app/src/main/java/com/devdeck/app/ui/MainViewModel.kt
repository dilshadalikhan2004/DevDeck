package com.devdeck.app.ui

import androidx.lifecycle.ViewModel
import com.devdeck.app.model.DiagnosticResult
import com.devdeck.app.model.HistoryItem
import com.devdeck.app.voice.VoicePhase
import com.devdeck.app.voice.VoiceUiState
import com.devdeck.app.pipeline.EventPhase
import com.devdeck.app.pipeline.IncidentPipeline
import com.devdeck.app.pipeline.NodeStatus
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

data class BrainEdge(
    val src: String,
    val dst: String,
    val kind: String = "import"
)

data class BrainSnapshot(
    val synced: Boolean = false,
    val filesIndexed: Int = 0,
    val symbolsIndexed: Int = 0,
    val testsDiscovered: Int = 0,
    val projectRoot: String = "",
    val tests: List<String> = emptyList(),
    val symbolsInPlay: List<String> = emptyList(),
    val evidenceFiles: List<String> = emptyList(),
    val edges: List<BrainEdge> = emptyList()
)

data class BootState(
    val visible: Boolean = true,
    val line: String = "Starting…",
    val detail: String? = null,
    val failed: Boolean = false,
    val modelName: String = "",
    val step: Int = 0
)

enum class RepairFilter {
    ALL, ACTIVE, REVIEW, APPLIED, FAILED
}

enum class HistoryStatusFilter {
    ALL, DIAGNOSED, FIXED, FAILED
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
    val modelDisplayName: String = "On-device model",
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
    val pendingReviewCount: Int = 0,
    val brain: BrainSnapshot = BrainSnapshot(),
    val voice: VoiceUiState = VoiceUiState(),
    val boot: BootState = BootState(),
    val repairFilter: RepairFilter = RepairFilter.ALL
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
                selectedStage = when (event.phase) {
                    EventPhase.FAILED, EventPhase.STARTED, EventPhase.COMPLETED -> event.stage
                    else -> state.selectedStage
                },
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
            val keepFilter = it.repairFilter == RepairFilter.ALL || it.repairFilter == RepairFilter.ACTIVE
            it.copy(
                activeIncidentId = id,
                selectedIncidentId = id,
                sandboxLines = emptyList(),
                sandboxRunning = false,
                currentScreen = AppScreen.REPAIR,
                repairFilter = if (keepFilter) it.repairFilter else RepairFilter.ALL
            )
        }
    }

    fun setRepairFilter(filter: RepairFilter) {
        _uiState.update { it.copy(repairFilter = filter) }
    }

    fun setBootLine(
        line: String,
        detail: String? = null,
        failed: Boolean = false,
        step: Int = 0,
        modelName: String? = null
    ) {
        _uiState.update {
            val name = modelName ?: it.boot.modelName
            it.copy(
                boot = BootState(
                    visible = true,
                    line = line,
                    detail = detail,
                    failed = failed,
                    modelName = name,
                    step = step
                ),
                modelDisplayName = name.ifBlank { it.modelDisplayName }
            )
        }
    }

    fun finishBoot() {
        _uiState.update { it.copy(boot = it.boot.copy(visible = false)) }
    }

    fun setModelDisplayName(name: String) {
        _uiState.update { it.copy(modelDisplayName = name.ifBlank { "On-device model" }) }
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

    fun voiceApproveMessage(): String {
        val state = _uiState.value
        val pipeline = state.selectedPipeline
        val result = state.currentResult
        return when {
            result == null ->
                "There is no candidate to approve yet. Wait until diagnosis finishes, then say approve again or tap Approve."
            pipeline?.outcome == PipelineOutcome.IN_PROGRESS ->
                "Not ready. The pipeline is still running. Wait until you see sandbox review, then say approve."
            pipeline?.outcome == PipelineOutcome.COMPLETE ->
                "This incident is already marked complete. Nothing new to approve."
            pipeline?.outcome == PipelineOutcome.REJECTED ->
                "This candidate was already rejected."
            pipeline?.outcome == PipelineOutcome.FAILED || pipeline?.outcome == PipelineOutcome.ROLLED_BACK ->
                "This run failed or rolled back. There is nothing safe to approve from voice."
            else -> {
                applyRepair()
                "Approve sent to the laptop. Watch the pipeline. Live files change only if apply succeeds."
            }
        }
    }

    fun voiceRejectMessage(): String {
        val state = _uiState.value
        val pipeline = state.selectedPipeline
        return when {
            pipeline == null && state.currentResult == null ->
                "There is no candidate to reject."
            pipeline?.outcome == PipelineOutcome.COMPLETE ->
                "Too late to reject — this one already completed."
            else -> {
                rejectRepair()
                "Rejected. No live files were changed from this voice command."
            }
        }
    }

    fun voiceStatusMessage(): String {
        val state = _uiState.value
        val pipeline = state.selectedPipeline
        val review = state.pendingReviewCount
        if (pipeline == null && state.pipelines.incidents.isEmpty()) {
            return "No active incidents."
        }
        val stage = pipeline?.displayNodes()?.firstOrNull { it.status == NodeStatus.ACTIVE }?.summary
        return buildString {
            append(repairHeadline(state))
            if (stage != null) append(" ").append(stage)
            if (review > 0) append(" $review awaiting review.")
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
            it.copy(
                sandboxLines = (it.sandboxLines + line).takeLast(150),
                sandboxRunning = true,
                selectedStage = PipelineStage.SANDBOX_DRY_RUN
            )
        }
    }

    fun setBrainReady(
        filesIndexed: Int,
        symbolsIndexed: Int,
        testsDiscovered: Int,
        projectRoot: String,
        tests: List<String>,
        sampleSymbols: List<String> = emptyList(),
        edges: List<BrainEdge> = emptyList()
    ) {
        _uiState.update {
            it.copy(
                brain = it.brain.copy(
                    synced = true,
                    filesIndexed = filesIndexed,
                    symbolsIndexed = symbolsIndexed,
                    testsDiscovered = testsDiscovered,
                    projectRoot = projectRoot,
                    tests = tests,
                    symbolsInPlay = (sampleSymbols + it.brain.symbolsInPlay).distinct().take(40),
                    edges = edges
                ),
                activeProject = projectRoot.substringAfterLast('\\').substringAfterLast('/').ifBlank { it.activeProject }
            )
        }
        addLog("[Brain] Indexed $filesIndexed files · $symbolsIndexed symbols · $testsDiscovered tests")
    }

    fun mergeBrainFromIncident(symbols: List<String>, evidenceFiles: List<String>) {
        _uiState.update {
            val nextSymbols = (it.brain.symbolsInPlay + symbols.filter { s -> s.isNotBlank() }).distinct().take(40)
            val nextFiles = (it.brain.evidenceFiles + evidenceFiles.filter { f -> f.isNotBlank() }).distinct().take(40)
            it.copy(
                brain = it.brain.copy(
                    symbolsInPlay = nextSymbols,
                    evidenceFiles = nextFiles
                )
            )
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

    private val _voiceListenNonce = MutableStateFlow(0L)
    val voiceListenNonce: StateFlow<Long> = _voiceListenNonce.asStateFlow()

    fun onMicTapped() {
        val voice = _uiState.value.voice
        if (voice.phase is VoicePhase.Listening ||
            voice.phase is VoicePhase.Thinking ||
            voice.phase is VoicePhase.LoadingModel
        ) {
            return
        }
        val state = _uiState.value
        val hasIncident = state.currentResult != null || !state.rootCause.isNullOrBlank()
        if (!hasIncident) {
            patchVoice(
                voice.copy(
                    phase = VoicePhase.Failed("No incident to discuss yet. Capture a crash first."),
                    hasIncident = false,
                    statusLine = "No incident loaded"
                )
            )
            return
        }
        patchVoice(voice.copy(hasIncident = true, deck = null, you = null, partial = ""))
        _voiceListenNonce.value = System.currentTimeMillis()
    }

    fun onAskAgain() {
        val voice = _uiState.value.voice
        if (voice.phase is VoicePhase.Listening || voice.phase is VoicePhase.Thinking) return
        patchVoice(voice.copy(you = null, deck = null, partial = "", phase = VoicePhase.Idle))
        onMicTapped()
    }

    fun onVoiceStopRequested() {
        val voice = _uiState.value.voice
        patchVoice(
            voice.copy(
                phase = VoicePhase.Idle,
                partial = "",
                speaking = false,
                statusLine = "Stopped"
            )
        )
    }

    fun onVoiceMuteToggle() {
        val voice = _uiState.value.voice
        patchVoice(voice.copy(muted = !voice.muted))
    }

    fun onTtsAvailability(available: Boolean) {
        patchVoice(_uiState.value.voice.copy(ttsAvailable = available))
    }

    fun onTtsSpeaking(speaking: Boolean) {
        patchVoice(_uiState.value.voice.copy(speaking = speaking))
    }

    fun onNeedMicPermission() {
        patchVoice(
            _uiState.value.voice.copy(
                phase = VoicePhase.NeedPermission,
                statusLine = "Microphone permission needed"
            )
        )
    }

    fun onMicPermissionDenied() {
        patchVoice(
            _uiState.value.voice.copy(
                phase = VoicePhase.PermissionDenied,
                statusLine = "Microphone permission denied — enable it in system settings"
            )
        )
    }

    fun onVoiceLoadingModel() {
        patchVoice(
            _uiState.value.voice.copy(
                phase = VoicePhase.LoadingModel,
                statusLine = "Loading on-device speech model…"
            )
        )
    }

    fun onVoiceListening() {
        patchVoice(
            _uiState.value.voice.copy(
                phase = VoicePhase.Listening,
                statusLine = "Listening…",
                partial = ""
            )
        )
    }

    fun onVoicePartial(text: String) {
        patchVoice(_uiState.value.voice.copy(partial = text, phase = VoicePhase.Listening))
    }

    fun onVoiceNoSpeech() {
        patchVoice(
            _uiState.value.voice.copy(
                phase = VoicePhase.NoSpeech,
                statusLine = "Didn't catch that — try again",
                partial = ""
            )
        )
    }

    fun onVoiceHeard(text: String) {
        patchVoice(
            _uiState.value.voice.copy(
                phase = VoicePhase.Thinking,
                you = text,
                partial = "",
                statusLine = "Thinking…"
            )
        )
    }

    fun onVoiceAnswer(text: String) {
        patchVoice(
            _uiState.value.voice.copy(
                phase = VoicePhase.Answered,
                deck = text,
                statusLine = "Answer ready"
            )
        )
    }

    fun onVoiceFailed(message: String) {
        patchVoice(
            _uiState.value.voice.copy(
                phase = VoicePhase.Failed(message),
                statusLine = message
            )
        )
    }

    private fun patchVoice(next: VoiceUiState) {
        _uiState.update { it.copy(voice = next) }
    }
}

fun repairHeadline(state: AppState): String {
    val incidents = state.pipelines.incidents
    if (incidents.isEmpty()) return "No active incidents"
    val review = incidents.count { it.outcome == PipelineOutcome.AWAITING_REVIEW }
    val running = incidents.count { it.outcome == PipelineOutcome.IN_PROGRESS }
    val applied = incidents.count { it.outcome == PipelineOutcome.COMPLETE }
    return when {
        running > 0 && state.sandboxRunning -> "Repair in progress: sandbox verifying"
        running > 0 -> "Repair in progress"
        review == 1 -> "1 incident awaiting review"
        review > 1 -> "$review incidents awaiting review"
        applied > 0 -> "Last repair applied ($applied complete)"
        else -> "${incidents.size} incident(s) in pipeline"
    }
}

fun IncidentPipeline.matchesRepairFilter(filter: RepairFilter): Boolean = when (filter) {
    RepairFilter.ALL -> true
    RepairFilter.ACTIVE -> outcome == PipelineOutcome.IN_PROGRESS
    RepairFilter.REVIEW -> outcome == PipelineOutcome.AWAITING_REVIEW
    RepairFilter.APPLIED -> outcome == PipelineOutcome.COMPLETE
    RepairFilter.FAILED -> outcome == PipelineOutcome.FAILED ||
        outcome == PipelineOutcome.REJECTED ||
        outcome == PipelineOutcome.ROLLED_BACK
}
