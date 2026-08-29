package com.devdeck.app.pipeline

import com.devdeck.app.model.DiagnosticResult
import com.devdeck.app.model.PatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineReducerTest {

    private val id = "inc-1"

    @Test
    fun happyPathReachesAwaitingReviewWithSingleActiveNode() {
        val pipeline = fold(PipelineFixtures.happyPath(id))
        assertEquals(0, pipeline.activeCount)
        assertEquals(PipelineOutcome.AWAITING_REVIEW, pipeline.outcome)
        assertEquals(FileTrustState.SANDBOXED, pipeline.fileTrust)
        assertEquals(NodeStatus.SKIPPED, pipeline.nodes.getValue(PipelineStage.CONTEXT_INDEXING).status)
        assertEquals(NodeStatus.PASSED, pipeline.nodes.getValue(PipelineStage.SANDBOX_DRY_RUN).status)
        assertEquals(NodeStatus.PASSED, pipeline.nodes.getValue(PipelineStage.AWAITING_REVIEW).status)
    }

    @Test
    fun onlyOneNodeIsActiveAtATime() {
        var registry = PipelineRegistry()
        PipelineFixtures.happyPath(id).forEach { event ->
            registry = registry.apply(event)
            val active = registry.byId.getValue(id).activeCount
            assertTrue("expected at most one active node, found $active after ${event.stage}/${event.phase}", active <= 1)
        }
    }

    @Test
    fun sandboxFailureStopsPipelineWithHumanMessage() {
        val events = PipelineFixtures.happyPath(id).takeWhile { it.stage != PipelineStage.SANDBOX_DRY_RUN } +
            PipelineEvent(id, PipelineStage.SANDBOX_DRY_RUN, EventPhase.STARTED, "Applying patch in a throwaway copy") +
            PipelineEvent(
                id,
                PipelineStage.SANDBOX_DRY_RUN,
                EventPhase.FAILED,
                "Sandbox dry-run failed: test suite exited with code 1",
                detail = "Command: pytest\nExit code: 1"
            )
        val pipeline = fold(events)
        assertEquals(PipelineOutcome.FAILED, pipeline.outcome)
        assertEquals(NodeStatus.FAILED, pipeline.nodes.getValue(PipelineStage.SANDBOX_DRY_RUN).status)
        assertEquals(0, pipeline.activeCount)
        assertTrue(pipeline.failureSummary!!.contains("exited with code 1"))
        assertEquals(NodeStatus.PENDING, pipeline.nodes.getValue(PipelineStage.AWAITING_REVIEW).status)
        assertEquals(NodeStatus.PENDING, pipeline.nodes.getValue(PipelineStage.COMPLETE).status)
    }

    @Test
    fun applyThenVerifyReachesCompleteAndAppliedTrust() {
        val events = PipelineFixtures.happyPath(id) + listOf(
            PipelineEvent(id, PipelineStage.APPLYING, EventPhase.STARTED, "Writing snapshot and patch"),
            PipelineEvent(id, PipelineStage.APPLYING, EventPhase.COMPLETED, "Patch written to real files"),
            PipelineEvent(id, PipelineStage.VERIFYING, EventPhase.STARTED, "Re-running original command"),
            PipelineEvent(id, PipelineStage.VERIFYING, EventPhase.COMPLETED, "Original command exited 0"),
            PipelineEvent(id, PipelineStage.COMPLETE, EventPhase.COMPLETED, "Fix kept on disk")
        )
        val pipeline = fold(events)
        assertEquals(PipelineOutcome.COMPLETE, pipeline.outcome)
        assertEquals(FileTrustState.APPLIED, pipeline.fileTrust)
        assertEquals(NodeStatus.PASSED, pipeline.nodes.getValue(PipelineStage.COMPLETE).status)
    }

    @Test
    fun verifyFailureMarksRolledBack() {
        val events = PipelineFixtures.happyPath(id) + listOf(
            PipelineEvent(id, PipelineStage.APPLYING, EventPhase.STARTED, "Applying"),
            PipelineEvent(id, PipelineStage.APPLYING, EventPhase.COMPLETED, "Applied"),
            PipelineEvent(id, PipelineStage.VERIFYING, EventPhase.STARTED, "Verifying"),
            PipelineEvent(
                id,
                PipelineStage.VERIFYING,
                EventPhase.FAILED,
                "Verification failed: original command exited with code 1"
            )
        )
        val pipeline = fold(events)
        assertEquals(PipelineOutcome.ROLLED_BACK, pipeline.outcome)
        assertEquals(FileTrustState.ROLLED_BACK, pipeline.fileTrust)
        assertEquals(NodeStatus.FAILED, pipeline.nodes.getValue(PipelineStage.VERIFYING).status)
    }

    @Test
    fun concurrentIncidentsDoNotOverwriteEachOther() {
        var registry = PipelineRegistry()
        registry = registry.apply(PipelineEvent("a", PipelineStage.CRASH_DETECTED, EventPhase.STARTED, "crash A"))
        registry = registry.apply(PipelineEvent("b", PipelineStage.CRASH_DETECTED, EventPhase.STARTED, "crash B"))
        registry = registry.apply(PipelineEvent("a", PipelineStage.CRASH_DETECTED, EventPhase.COMPLETED, "A captured"))
        val a = registry.byId.getValue("a")
        val b = registry.byId.getValue("b")
        assertEquals(NodeStatus.PASSED, a.nodes.getValue(PipelineStage.CRASH_DETECTED).status)
        assertEquals(NodeStatus.ACTIVE, b.nodes.getValue(PipelineStage.CRASH_DETECTED).status)
        assertEquals(2, registry.incidents.size)
    }

    @Test
    fun requestChangesIsCapped() {
        var pipeline = fold(PipelineFixtures.happyPath(id))
        repeat(PipelineReducer.MAX_CORRECTION_ROUNDS) {
            pipeline = PipelineReducer.reduce(
                pipeline,
                PipelineEvent(id, PipelineStage.DIAGNOSING, EventPhase.REQUEST_CHANGES, "please use str()")
            )
        }
        assertEquals(PipelineReducer.MAX_CORRECTION_ROUNDS, pipeline.correctionRounds)
        assertFalse(pipeline.correctionCapReached)
        pipeline = PipelineReducer.reduce(
            pipeline,
            PipelineEvent(id, PipelineStage.DIAGNOSING, EventPhase.REQUEST_CHANGES, "again")
        )
        assertTrue(pipeline.correctionCapReached)
        assertEquals(PipelineReducer.MAX_CORRECTION_ROUNDS, pipeline.correctionRounds)
        assertTrue(pipeline.failureSummary!!.contains("manually"))
    }

    @Test
    fun rejectDoesNotApplyAndReachesTerminalOutcome() {
        val pipeline = PipelineReducer.reduce(
            fold(PipelineFixtures.happyPath(id)),
            PipelineEvent(id, PipelineStage.AWAITING_REVIEW, EventPhase.REVIEW_REJECTED, "Developer rejected the candidate")
        )
        assertEquals(PipelineOutcome.REJECTED, pipeline.outcome)
        assertEquals(FileTrustState.NONE, pipeline.fileTrust)
        assertEquals(NodeStatus.FAILED, pipeline.nodes.getValue(PipelineStage.AWAITING_REVIEW).status)
        assertEquals(0, pipeline.activeCount)
    }

    @Test
    fun candidateDiffUsesPatchManagerStyleLines() {
        val candidate = RepairCandidate(
            incidentId = id,
            repairFile = "auth.py",
            repairLine = 4,
            originalLine = "print(user.name)",
            repairCode = "print(user.name if user else None)",
            diffText = null,
            patchType = PatchType.SINGLE_LINE,
            reasoning = "Guard None before attribute access.",
            rawModelOutput = "<<<FIX>>>print(user.name if user else None)<<<END>>>",
            groundingPassed = true,
            sandboxPassed = true,
            sandboxCommand = "pytest",
            sandboxExitCode = 0,
            trustScore = 91,
            expectedSha256 = "a".repeat(64),
            projectId = "p1",
            confidence = 0.9f,
            correctionRound = 0,
            diagnostic = DiagnosticResult(
                rootCause = "Attribute access on None",
                location = "auth.py:4",
                fix = "print(user.name if user else None)",
                repairFile = "auth.py",
                repairLine = 4,
                repairCode = "print(user.name if user else None)",
                originalLine = "print(user.name)"
            )
        )
        val lines = candidate.toDiffLines()
        assertEquals(DiffLineModel.Kind.REMOVE, lines[0].kind)
        assertEquals(DiffLineModel.Kind.ADD, lines[1].kind)
    }

    private fun fold(events: List<PipelineEvent>): IncidentPipeline {
        var pipeline: IncidentPipeline? = null
        events.forEach { pipeline = PipelineReducer.reduce(pipeline, it) }
        return pipeline!!
    }
}
