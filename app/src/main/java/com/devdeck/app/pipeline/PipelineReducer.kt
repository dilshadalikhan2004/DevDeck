package com.devdeck.app.pipeline

object PipelineReducer {
    const val MAX_CORRECTION_ROUNDS = 3

    private val reopenable = setOf(
        PipelineOutcome.AWAITING_REVIEW,
        PipelineOutcome.REJECTED,
        PipelineOutcome.FAILED
    )

    fun reduce(current: IncidentPipeline?, event: PipelineEvent): IncidentPipeline {
        val base = current ?: IncidentPipeline.fresh(event.incidentId)
        return when (event.phase) {
            EventPhase.REQUEST_CHANGES -> beginCorrection(base, event)
            EventPhase.REVIEW_REJECTED -> reject(base, event)
            EventPhase.CANDIDATE_READY -> base.copy(candidate = event.candidate ?: base.candidate)
            EventPhase.STARTED -> onStarted(base, event)
            EventPhase.COMPLETED -> onCompleted(base, event)
            EventPhase.FAILED -> onFailed(base, event)
            EventPhase.SKIPPED -> onSkipped(base, event)
        }
    }

    private fun beginCorrection(base: IncidentPipeline, event: PipelineEvent): IncidentPipeline {
        if (base.correctionRounds >= MAX_CORRECTION_ROUNDS) {
            return base.copy(
                correctionCapReached = true,
                failureSummary = event.message.ifBlank {
                    "Correction limit reached. Review the candidate manually instead of another AI attempt."
                }
            )
        }
        if (base.outcome !in reopenable && base.outcome != PipelineOutcome.IN_PROGRESS) {
            return base
        }
        val resetFrom = setOf(
            PipelineStage.DIAGNOSING,
            PipelineStage.GROUNDING_CHECK,
            PipelineStage.SANDBOX_DRY_RUN,
            PipelineStage.AWAITING_REVIEW,
            PipelineStage.APPLYING,
            PipelineStage.VERIFYING,
            PipelineStage.COMPLETE,
            PipelineStage.ROLLED_BACK
        )
        val nodes = deactivateOthers(base.nodes).mapValues { (stage, snap) ->
            if (stage in resetFrom) StageSnapshot(stage) else snap
        }
        return base.copy(
            nodes = nodes,
            outcome = PipelineOutcome.IN_PROGRESS,
            fileTrust = FileTrustState.NONE,
            correctionRounds = base.correctionRounds + 1,
            correctionCapReached = false,
            failureSummary = null,
            candidate = base.candidate
        )
    }

    private fun reject(base: IncidentPipeline, event: PipelineEvent): IncidentPipeline {
        val nodes = deactivateOthers(base.nodes).toMutableMap()
        nodes[PipelineStage.AWAITING_REVIEW] = StageSnapshot(
            PipelineStage.AWAITING_REVIEW,
            NodeStatus.FAILED,
            event.message.ifBlank { "Developer rejected the candidate. No real files changed." },
            event.detail
        )
        return base.copy(
            nodes = nodes,
            outcome = PipelineOutcome.REJECTED,
            fileTrust = FileTrustState.NONE,
            failureSummary = event.message
        )
    }

    private fun onStarted(base: IncidentPipeline, event: PipelineEvent): IncidentPipeline {
        if (isSealed(base) && event.stage != PipelineStage.DIAGNOSING) return base
        val nodes = deactivateOthers(base.nodes).toMutableMap()
        val existing = nodes[event.stage] ?: StageSnapshot(event.stage)
        if (existing.status == NodeStatus.PASSED || existing.status == NodeStatus.SKIPPED) {
            return base.copy(nodes = deactivateOthers(base.nodes))
        }
        nodes[event.stage] = existing.copy(
            status = NodeStatus.ACTIVE,
            summary = event.message,
            detail = event.detail ?: existing.detail
        )
        return base.copy(
            nodes = nodes,
            outcome = if (base.outcome == PipelineOutcome.AWAITING_REVIEW && event.stage == PipelineStage.APPLYING) {
                PipelineOutcome.IN_PROGRESS
            } else if (base.outcome == PipelineOutcome.REJECTED) {
                base.outcome
            } else {
                PipelineOutcome.IN_PROGRESS
            },
            candidate = mergeCandidate(base.candidate, event)
        )
    }

    private fun onCompleted(base: IncidentPipeline, event: PipelineEvent): IncidentPipeline {
        if (isSealed(base) && event.stage !in setOf(PipelineStage.COMPLETE, PipelineStage.ROLLED_BACK)) {
            return base
        }
        val nodes = deactivateOthers(base.nodes, keep = event.stage).toMutableMap()
        nodes[event.stage] = StageSnapshot(
            event.stage,
            NodeStatus.PASSED,
            event.message,
            event.detail
        )
        val candidate = mergeCandidate(base.candidate, event)
        val fileTrust = when (event.stage) {
            PipelineStage.SANDBOX_DRY_RUN, PipelineStage.AWAITING_REVIEW -> FileTrustState.SANDBOXED
            PipelineStage.COMPLETE -> FileTrustState.APPLIED
            PipelineStage.ROLLED_BACK -> FileTrustState.ROLLED_BACK
            else -> base.fileTrust
        }
        val outcome = when (event.stage) {
            PipelineStage.AWAITING_REVIEW -> PipelineOutcome.AWAITING_REVIEW
            PipelineStage.COMPLETE -> PipelineOutcome.COMPLETE
            PipelineStage.ROLLED_BACK -> PipelineOutcome.ROLLED_BACK
            PipelineStage.APPLYING, PipelineStage.VERIFYING -> PipelineOutcome.IN_PROGRESS
            else -> if (base.outcome == PipelineOutcome.AWAITING_REVIEW) {
                base.outcome
            } else {
                PipelineOutcome.IN_PROGRESS
            }
        }
        return base.copy(
            nodes = nodes,
            outcome = outcome,
            fileTrust = fileTrust,
            candidate = candidate,
            failureSummary = if (outcome == PipelineOutcome.COMPLETE) null else base.failureSummary
        )
    }

    private fun onFailed(base: IncidentPipeline, event: PipelineEvent): IncidentPipeline {
        val nodes = deactivateOthers(base.nodes).toMutableMap()
        nodes[event.stage] = StageSnapshot(
            event.stage,
            NodeStatus.FAILED,
            event.message,
            event.detail
        )
        val rolledBack = event.stage == PipelineStage.APPLYING ||
            event.stage == PipelineStage.VERIFYING ||
            event.stage == PipelineStage.ROLLED_BACK
        if (rolledBack) {
            nodes[PipelineStage.ROLLED_BACK] = StageSnapshot(
                PipelineStage.ROLLED_BACK,
                NodeStatus.FAILED,
                event.message,
                event.detail
            )
        }
        return base.copy(
            nodes = nodes,
            outcome = if (rolledBack) PipelineOutcome.ROLLED_BACK else PipelineOutcome.FAILED,
            fileTrust = if (rolledBack) FileTrustState.ROLLED_BACK else base.fileTrust,
            failureSummary = event.message,
            candidate = mergeCandidate(base.candidate, event)
        )
    }

    private fun onSkipped(base: IncidentPipeline, event: PipelineEvent): IncidentPipeline {
        val nodes = base.nodes.toMutableMap()
        nodes[event.stage] = StageSnapshot(
            event.stage,
            NodeStatus.SKIPPED,
            event.message,
            event.detail
        )
        return base.copy(nodes = deactivateOthers(nodes, keep = event.stage))
    }

    private fun isSealed(base: IncidentPipeline): Boolean =
        base.outcome == PipelineOutcome.COMPLETE || base.outcome == PipelineOutcome.ROLLED_BACK

    private fun deactivateOthers(
        nodes: Map<PipelineStage, StageSnapshot>,
        keep: PipelineStage? = null
    ): Map<PipelineStage, StageSnapshot> {
        return nodes.mapValues { (stage, snap) ->
            if (snap.status == NodeStatus.ACTIVE && stage != keep) {
                snap.copy(status = NodeStatus.PENDING)
            } else {
                snap
            }
        }
    }

    private fun mergeCandidate(current: RepairCandidate?, event: PipelineEvent): RepairCandidate? {
        val next = event.candidate ?: current ?: return null
        return next.copy(
            sandboxPassed = event.sandboxPassed ?: next.sandboxPassed,
            sandboxCommand = event.sandboxCommand ?: next.sandboxCommand,
            sandboxExitCode = event.sandboxExitCode ?: next.sandboxExitCode,
            trustScore = event.trustScore ?: next.trustScore
        )
    }
}

object PipelineEventParser {
    fun parse(
        incidentId: String?,
        stage: String?,
        phase: String?,
        message: String?,
        detail: String? = null,
        sandboxPassed: Boolean? = null,
        sandboxCommand: String? = null,
        sandboxExitCode: Int? = null,
        trustScore: Int? = null
    ): PipelineEvent? {
        val id = incidentId?.takeIf { it.isNotBlank() } ?: return null
        val parsedStage = PipelineStage.fromWire(stage ?: return null) ?: return null
        val parsedPhase = EventPhase.fromWire(phase ?: return null) ?: return null
        return PipelineEvent(
            incidentId = id,
            stage = parsedStage,
            phase = parsedPhase,
            message = message.orEmpty(),
            detail = detail,
            sandboxPassed = sandboxPassed,
            sandboxCommand = sandboxCommand,
            sandboxExitCode = sandboxExitCode,
            trustScore = trustScore
        )
    }
}

object PipelineFixtures {
    fun happyPath(incidentId: String): List<PipelineEvent> {
        fun ev(stage: PipelineStage, phase: EventPhase, message: String) =
            PipelineEvent(incidentId, stage, phase, message)

        return listOf(
            ev(PipelineStage.CRASH_DETECTED, EventPhase.STARTED, "pytest failed"),
            ev(PipelineStage.CRASH_DETECTED, EventPhase.COMPLETED, "Crash intercepted"),
            ev(PipelineStage.CONTEXT_INDEXING, EventPhase.SKIPPED, "Index already current — skipped full re-scan"),
            ev(PipelineStage.SENT_TO_PHONE, EventPhase.STARTED, "Sending incident"),
            ev(PipelineStage.SENT_TO_PHONE, EventPhase.COMPLETED, "Phone received incident"),
            ev(PipelineStage.DIAGNOSING, EventPhase.STARTED, "Gemma-2B-IT running"),
            ev(PipelineStage.DIAGNOSING, EventPhase.COMPLETED, "Candidate patch synthesized"),
            ev(PipelineStage.GROUNDING_CHECK, EventPhase.STARTED, "Validating symbols"),
            ev(PipelineStage.GROUNDING_CHECK, EventPhase.COMPLETED, "All symbols present in the repo index"),
            ev(PipelineStage.SANDBOX_DRY_RUN, EventPhase.STARTED, "Applying patch in a throwaway copy"),
            ev(PipelineStage.SANDBOX_DRY_RUN, EventPhase.COMPLETED, "Sandbox dry-run passed (exit 0)"),
            ev(PipelineStage.AWAITING_REVIEW, EventPhase.STARTED, "Waiting for developer review"),
            ev(PipelineStage.AWAITING_REVIEW, EventPhase.COMPLETED, "Ready for Approve / Reject / Request Changes")
        )
    }
}
