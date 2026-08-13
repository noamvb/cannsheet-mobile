package com.example.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PenWidgetTransitionsTest {
    @Test
    fun matchingUndoRestoresSecondsAndStaleUndoDoesNothing() {
        val payload = payload()

        assertEquals(
            PenWidgetUndoResolution.Restored(30),
            resolveUndo(payload, "commit-1", nowMillis = 1_500L),
        )
        assertEquals(
            PenWidgetUndoResolution.NoOp,
            resolveUndo(payload, "stale", nowMillis = 1_500L),
        )
    }

    @Test
    fun undoLosesToALiveClaimAndWinsAgainstAStaleOne() {
        val claimed = payload().copy(
            claimId = "owner:claim-1",
            claimedAtEpochMillis = 1_000L,
        )

        assertEquals(
            PenWidgetUndoResolution.NoOp,
            resolveUndo(claimed, claimed.commitId, nowMillis = 1_500L),
        )
        assertEquals(
            PenWidgetUndoResolution.Restored(claimed.seconds),
            resolveUndo(
                claimed,
                claimed.commitId,
                nowMillis = 1_000L + CLAIM_STALE_MILLIS,
            ),
        )
        assertEquals(
            PenWidgetUndoResolution.Restored(payload().seconds),
            resolveUndo(payload(), payload().commitId, nowMillis = 1_500L),
        )
    }

    @Test
    fun matchingCommitWinsAndSecondApplicationIsNoOp() {
        val payload = payload()

        assertEquals(
            PenWidgetCommitResolution.Committed(payload),
            resolveCommit(payload, "commit-1", nowMillis = 5_000L),
        )
        assertEquals(
            PenWidgetCommitResolution.NoOp,
            resolveCommit(null, "commit-1", nowMillis = 5_000L),
        )
    }

    @Test
    fun staleCommitDoesNothingAndOverdueFlushAcceptsAnyId() {
        val payload = payload()

        assertEquals(
            PenWidgetCommitResolution.NoOp,
            resolveCommit(payload, "stale", nowMillis = 10_000L),
        )
        val overdue = resolveCommit(payload, null, nowMillis = 6_000L)
        assertTrue(overdue is PenWidgetCommitResolution.Committed)
        assertEquals(
            PenWidgetCommitResolution.NoOp,
            resolveCommit(payload, null, nowMillis = 1_001L),
        )
    }

    @Test
    fun graceDeadlineAndPendingAgeCeilingAreExplicit() {
        val payload = payload().copy(
            submittedAtEpochMillis = 10_000L,
            commitAtEpochMillis = 10_000L + COMMIT_DELAY_MILLIS,
        )

        assertEquals(
            PenWidgetCommitResolution.NoOp,
            resolveCommit(payload, payload.commitId, nowMillis = payload.commitAtEpochMillis - 1L),
        )
        assertTrue(
            resolveCommit(payload, payload.commitId, nowMillis = payload.commitAtEpochMillis)
                is PenWidgetCommitResolution.Committed,
        )
        assertTrue(
            resolveCommit(
                payload.copy(commitAtEpochMillis = Long.MAX_VALUE),
                null,
                nowMillis = payload.submittedAtEpochMillis + MAX_PENDING_AGE_MILLIS,
            ) is PenWidgetCommitResolution.Committed,
        )
    }

    @Test
    fun backwardsClockJumpAndStaleClaimRecoverTowardCommit() {
        val submittedAt = 100_000L
        val payload = payload().copy(
            submittedAtEpochMillis = submittedAt,
            commitAtEpochMillis = submittedAt + COMMIT_DELAY_MILLIS,
        )
        assertTrue(
            resolveCommit(
                payload,
                null,
                nowMillis = submittedAt - CLOCK_ROLLBACK_TOLERANCE_MILLIS - 1L,
            ) is PenWidgetCommitResolution.Committed,
        )

        val claimed = payload.copy(claimId = "claim-1", claimedAtEpochMillis = 1_000L)
        assertEquals(
            PenWidgetCommitResolution.NoOp,
            resolveCommit(claimed, null, nowMillis = 1_000L + CLAIM_STALE_MILLIS - 1L, force = true),
        )
        assertTrue(
            resolveCommit(claimed, null, nowMillis = 1_000L + CLAIM_STALE_MILLIS, force = true)
                is PenWidgetCommitResolution.Committed,
        )
        assertTrue(
            resolveCommit(
                claimed.copy(claimId = "old-process:claim-1"),
                null,
                nowMillis = 1_001L,
                force = true,
                claimOwnerId = "new-process",
            ) is PenWidgetCommitResolution.Committed,
        )
    }

    @Test
    fun undoThenCommitHasNoPayload() {
        val payload = payload()
        val undone = resolveUndo(payload, payload.commitId, nowMillis = 10_000L)
        val afterUndo = if (undone is PenWidgetUndoResolution.Restored) null else payload

        assertEquals(
            PenWidgetCommitResolution.NoOp,
            resolveCommit(afterUndo, payload.commitId, nowMillis = 10_000L),
        )
    }

    private fun payload() = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = "commit-1",
        eventId = "event-1",
        submittedAtEpochMillis = 0L,
        commitAtEpochMillis = 5_000L,
        productId = "pen-1",
        productUuid = null,
        seconds = 30,
        secondsPerUse = 10.0,
        uses = 3.0,
        date = "2026-08-12",
        time = "12:00",
    )
}
