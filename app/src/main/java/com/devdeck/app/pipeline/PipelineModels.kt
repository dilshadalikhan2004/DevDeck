package com.devdeck.app.pipeline

import com.devdeck.app.model.DiagnosticResult
import com.devdeck.app.model.PatchType

enum class PipelineStage(val wireName: String, val title: String) {
    CRASH_DETECTED("crash_detected", "Crash detected"),
    CONTEXT_INDEXING("context_indexing", "Context indexing"),
    SENT_TO_PHONE("sent_to_phone", "Sent to phone"),
    DIAGNOSING("diagnosing", "Diagnosing"),
    GROUNDING_CHECK("grounding_check", "Grounding check"),
    SANDBOX_DRY_RUN("sandbox_dry_run", "Sandbox dry-run"),
    AWAITING_REVIEW("awaiting_review", "Awaiting review"),
    APPLYING("applying", "Applying to real files"),
    VERIFYING("verifying", "Verifying"),
    COMPLETE("complete", "Complete"),
    ROLLED_BACK("rolled_back", "Rolled back");

    companion object {
        fun fromWire(name: String): PipelineStage? =
            entries.find { it.wireName.equals(name, ignoreCase = true) }
    }
}

enum class NodeStatus { PENDING, ACTIVE, PASSED, FAILED, SKIPPED }

enum class EventPhase {
    STARTED, COMPLETED, FAILED, SKIPPED, REQUEST_CHANGES, REVIEW_REJECTED, CANDIDATE_READY;

    companion object {
        fun fromWire(name: String): EventPhase? = when (name.lowercase()) {
            "started" -> STARTED
            "completed" -> COMPLETED
            "failed" -> FAILED
            "skipped" -> SKIPPED
            "request_changes" -> REQUEST_CHANGES
            "review_rejected" -> REVIEW_REJECTED
            "candidate_ready" -> CANDIDATE_READY
            else -> null
        }
    }
}

enum class FileTrustState { NONE, SANDBOXED, APPLIED, ROLLED_BACK }

enum class PipelineOutcome {
    IN_PROGRESS, AWAITING_REVIEW, FAILED, REJECTED, COMPLETE, ROLLED_BACK
}

data class StageSnapshot(
    val stage: PipelineStage,
    val status: NodeStatus = NodeStatus.PENDING,
    val summary: String = "",
    val detail: String? = null
)

data class DiffLineModel(
    val text: String,
    val kind: Kind
) {
    enum class Kind { CONTEXT, ADD, REMOVE }
}

data class RepairCandidate(
    val incidentId: String,
    val repairFile: String?,
    val repairLine: Int?,
    val originalLine: String?,
    val repairCode: String?,
    val diffText: String?,
    val patchType: PatchType,
    val reasoning: String,
    val rawModelOutput: String?,
    val groundingPassed: Boolean,
    val sandboxPassed: Boolean,
    val sandboxCommand: String?,
    val sandboxExitCode: Int?,
    val trustScore: Int,
    val expectedSha256: String?,
    val projectId: String?,
    val confidence: Float,
    val correctionRound: Int,
    val diagnostic: DiagnosticResult
) {
    fun toDiffLines(): List<DiffLineModel> {
        val unified = diffText?.takeIf { it.isNotBlank() }
        if (unified != null) {
            return unified.lines().map { line ->
                when {
                    line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") ->
                        DiffLineModel(line, DiffLineModel.Kind.CONTEXT)
                    line.startsWith("+") -> DiffLineModel(line, DiffLineModel.Kind.ADD)
                    line.startsWith("-") -> DiffLineModel(line, DiffLineModel.Kind.REMOVE)
                    else -> DiffLineModel(line, DiffLineModel.Kind.CONTEXT)
                }
            }
        }
        val removed = originalLine?.takeIf { it.isNotBlank() }
        val added = repairCode?.takeIf { it.isNotBlank() }
        return buildList {
            if (removed != null) add(DiffLineModel("- $removed", DiffLineModel.Kind.REMOVE))
            if (added != null) add(DiffLineModel("+ $added", DiffLineModel.Kind.ADD))
            if (isEmpty()) add(DiffLineModel(diagnostic.fix, DiffLineModel.Kind.CONTEXT))
        }
    }
}

data class PipelineEvent(
    val incidentId: String,
    val stage: PipelineStage,
    val phase: EventPhase,
    val message: String,
    val detail: String? = null,
    val timestamp: Long = 0L,
    val candidate: RepairCandidate? = null,
    val sandboxPassed: Boolean? = null,
    val sandboxCommand: String? = null,
    val sandboxExitCode: Int? = null,
    val trustScore: Int? = null
)

data class IncidentPipeline(
    val incidentId: String,
    val nodes: Map<PipelineStage, StageSnapshot>,
    val outcome: PipelineOutcome = PipelineOutcome.IN_PROGRESS,
    val fileTrust: FileTrustState = FileTrustState.NONE,
    val candidate: RepairCandidate? = null,
    val correctionRounds: Int = 0,
    val correctionCapReached: Boolean = false,
    val failureSummary: String? = null,
    val selectedStage: PipelineStage? = null
) {
    val activeCount: Int get() = nodes.values.count { it.status == NodeStatus.ACTIVE }

    fun displayNodes(): List<StageSnapshot> {
        val core = DISPLAY_STAGES.map { stage ->
            nodes[stage] ?: StageSnapshot(stage)
        }
        val terminal: StageSnapshot? = when (outcome) {
            PipelineOutcome.ROLLED_BACK ->
                nodes[PipelineStage.ROLLED_BACK] ?: StageSnapshot(PipelineStage.ROLLED_BACK)
            PipelineOutcome.COMPLETE ->
                nodes[PipelineStage.COMPLETE] ?: StageSnapshot(PipelineStage.COMPLETE, NodeStatus.PASSED)
            PipelineOutcome.FAILED -> {
                val failed = nodes.values.firstOrNull { it.status == NodeStatus.FAILED }
                when {
                    failed != null && failed.stage in DISPLAY_STAGES -> null
                    else -> nodes[PipelineStage.ROLLED_BACK]?.takeIf { it.status != NodeStatus.PENDING }
                }
            }
            else -> null
        }
        return if (terminal == null) core else core + terminal
    }

    companion object {
        val DISPLAY_STAGES = listOf(
            PipelineStage.CRASH_DETECTED,
            PipelineStage.CONTEXT_INDEXING,
            PipelineStage.SENT_TO_PHONE,
            PipelineStage.DIAGNOSING,
            PipelineStage.GROUNDING_CHECK,
            PipelineStage.SANDBOX_DRY_RUN,
            PipelineStage.AWAITING_REVIEW,
            PipelineStage.APPLYING,
            PipelineStage.VERIFYING
        )

        fun fresh(incidentId: String): IncidentPipeline = IncidentPipeline(
            incidentId = incidentId,
            nodes = PipelineStage.entries.associateWith { StageSnapshot(it) }
        )
    }
}

data class PipelineRegistry(
    val byId: Map<String, IncidentPipeline> = emptyMap(),
    val order: List<String> = emptyList()
) {
    val incidents: List<IncidentPipeline>
        get() = order.mapNotNull { byId[it] }

    val pendingReviewCount: Int
        get() = byId.values.count { it.outcome == PipelineOutcome.AWAITING_REVIEW }

    fun apply(event: PipelineEvent): PipelineRegistry {
        val updated = PipelineReducer.reduce(byId[event.incidentId], event)
        val newOrder = if (event.incidentId in order) order else order + event.incidentId
        return copy(byId = byId + (event.incidentId to updated), order = newOrder)
    }
}
