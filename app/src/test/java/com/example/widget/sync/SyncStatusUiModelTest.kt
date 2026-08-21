package com.example.widget.sync

import com.example.R
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
        assertEquals(PenWidgetText.Resource(R.string.sync_status_last_sync_just_now), model.lastSyncLabel)
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

        assertEquals(PenWidgetText.Resource(R.string.sync_status_last_sync_never), model.lastSyncLabel)
    }

    @Test
    fun clockRollbackDoesNotReportNegativeAge() {
        val model = buildSyncStatusUiModel(
            pendingCount = 1,
            lastMeaningfulSyncAtEpochMillis = NOW + 1L,
            queueNonEmptySinceEpochMillis = NOW + 1L,
            nowMillis = NOW,
        )

        assertEquals(PenWidgetText.Resource(R.string.sync_status_last_sync_just_now), model.lastSyncLabel)
        assertFalse(model.stuck)
    }


    @Test
    fun minutesOldSyncReportsMinutesWithTheElapsedCount() {
        assertEquals(
            PenWidgetText.Resource(R.string.sync_status_last_sync_minutes, listOf(5)),
            labelFor(ageMillis = 5L * 60_000L),
        )
    }

    @Test
    fun anHourOldSyncCrossesFromMinutesToHours() {
        assertEquals(
            PenWidgetText.Resource(R.string.sync_status_last_sync_minutes, listOf(59)),
            labelFor(ageMillis = 59L * 60_000L),
        )
        assertEquals(
            PenWidgetText.Resource(R.string.sync_status_last_sync_hours, listOf(1)),
            labelFor(ageMillis = 60L * 60_000L),
        )
    }

    @Test
    fun aDayOldSyncCrossesFromHoursToYesterday() {
        assertEquals(
            PenWidgetText.Resource(R.string.sync_status_last_sync_hours, listOf(23)),
            labelFor(ageMillis = 23L * 60L * 60_000L),
        )
        assertEquals(
            PenWidgetText.Resource(R.string.sync_status_last_sync_yesterday),
            labelFor(ageMillis = 24L * 60L * 60_000L),
        )
    }

    @Test
    fun twoDayOldSyncCrossesFromYesterdayToDays() {
        assertEquals(
            PenWidgetText.Resource(R.string.sync_status_last_sync_yesterday),
            labelFor(ageMillis = 47L * 60L * 60_000L),
        )
        assertEquals(
            PenWidgetText.Resource(R.string.sync_status_last_sync_days, listOf(2)),
            labelFor(ageMillis = 48L * 60L * 60_000L),
        )
    }

    /** Every branch of the last-sync label, addressed by how long ago the sync happened. */
    private fun labelFor(ageMillis: Long): PenWidgetText = buildSyncStatusUiModel(
        pendingCount = 0,
        lastMeaningfulSyncAtEpochMillis = NOW - ageMillis,
        queueNonEmptySinceEpochMillis = null,
        nowMillis = NOW,
    ).lastSyncLabel

    private companion object {
        const val NOW = 1_000_000_000_000L
    }
}
