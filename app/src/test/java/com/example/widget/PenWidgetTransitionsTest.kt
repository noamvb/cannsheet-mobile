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
            resolveUndo(payload, "commit-1"),
        )
        assertEquals(
            PenWidgetUndoResolution.NoOp,
            resolveUndo(payload, "stale"),
        )
    }

    @Test
    fun matchingCommitWinsAndSecondApplicationIsNoOp() {
        val payload = payload()

        assertEquals(
            PenWidgetCommitResolution.Committed(payload),
            resolveCommit(payload, "commit-1", nowMillis = 1_001L),
        )
        assertEquals(
            PenWidgetCommitResolution.NoOp,
            resolveCommit(null, "commit-1", nowMillis = 1_001L),
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
    fun undoThenCommitHasNoPayload() {
        val payload = payload()
        val undone = resolveUndo(payload, payload.commitId)
        val afterUndo = if (undone is PenWidgetUndoResolution.Restored) null else payload

        assertEquals(
            PenWidgetCommitResolution.NoOp,
            resolveCommit(afterUndo, payload.commitId, nowMillis = 10_000L),
        )
    }

    private fun payload() = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = "commit-1",
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
