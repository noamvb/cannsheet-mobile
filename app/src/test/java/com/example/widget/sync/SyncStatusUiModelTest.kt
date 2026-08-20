package com.example.widget.sync

import com.example.data.sync.QUEUE_STUCK_THRESHOLD_MILLIS
import com.example.widget.PenWidgetText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatusUiModelTest {
    @Test
    fun emptyQueueShowsSyncedState() {
        val model = buildSyncStatusUiModel(
            pendingCount = 0,
            lastMeaningfulSyncAtEpochMillis = NOW,
            queueNonEmptySinceEpochMillis = null,
            nowMillis = NOW,
        )

        assertEquals(0, model.pendingCount)
        assertEquals(PenWidgetText.Literal("Synced just now"), model.lastSyncLabel)
        assertFalse(model.stuck)
    }

    @Test
    fun pendingCountIsReported() {
        val model = buildSyncStatusUiModel(
            pendingCount = 7,
            lastMeaningfulSyncAtEpochMillis = NOW,
            queueNonEmptySinceEpochMillis = null,
            nowMillis = NOW,
        )

        assertEquals(7, model.pendingCount)
    }

    @Test
    fun queueOlderThanTheStuckThresholdIsStuck() {
        val model = buildSyncStatusUiModel(
            pendingCount = 1,
            lastMeaningfulSyncAtEpochMillis = NOW,
            queueNonEmptySinceEpochMillis = NOW - QUEUE_STUCK_THRESHOLD_MILLIS - 1L,
            nowMillis = NOW,
        )

        assertTrue(model.stuck)
    }

    @Test
    fun queueYoungerThanTheThresholdIsNotStuck() {
        val model = buildSyncStatusUiModel(
            pendingCount = 1,
            lastMeaningfulSyncAtEpochMillis = NOW,
            queueNonEmptySinceEpochMillis = NOW - QUEUE_STUCK_THRESHOLD_MILLIS + 1L,
            nowMillis = NOW,
        )

        assertFalse(model.stuck)
    }

    @Test
    fun neverSyncedShowsTheNeverLabel() {
        val model = buildSyncStatusUiModel(
            pendingCount = 0,
            lastMeaningfulSyncAtEpochMillis = null,
            queueNonEmptySinceEpochMillis = null,
            nowMillis = NOW,
        )

        assertEquals(PenWidgetText.Literal("Never synced"), model.lastSyncLabel)
    }

    @Test
    fun clockRollbackDoesNotReportNegativeAge() {
        val model = buildSyncStatusUiModel(
            pendingCount = 1,
            lastMeaningfulSyncAtEpochMillis = NOW + 1L,
            queueNonEmptySinceEpochMillis = NOW + 1L,
            nowMillis = NOW,
        )

        assertEquals(PenWidgetText.Literal("Synced just now"), model.lastSyncLabel)
        assertFalse(model.stuck)
    }

    private companion object {
        const val NOW = 1_000_000_000_000L
    }
}
