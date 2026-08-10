package com.example.ui

import com.example.data.BackgroundSyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundSyncLastRunTextTest {
    private val nowEpochMillis = 1_000_000_000L

    @Test
    fun showsNotRunYetWhenNoRunHasBeenRecorded() {
        assertEquals(
            "Not run yet",
            backgroundSyncLastRunText(
                lastRunEpochMillis = null,
                lastResult = null,
                nowEpochMillis = nowEpochMillis,
            ),
        )
    }

    @Test
    fun formatsRecentCompletedRunInFriendlyLanguage() {
        assertEquals(
            "Last run: just now — Sync successful",
            backgroundSyncLastRunText(
                lastRunEpochMillis = nowEpochMillis - 45_000L,
                lastResult = BackgroundSyncResult.SUCCESS,
                nowEpochMillis = nowEpochMillis,
            ),
        )
    }

    @Test
    fun formatsElapsedTimeWithSingularAndPluralUnits() {
        assertEquals(
            "Last run: 1 minute ago — Could not sync; actions still pending",
            backgroundSyncLastRunText(
                lastRunEpochMillis = nowEpochMillis - 60_000L,
                lastResult = BackgroundSyncResult.RETRY_EXHAUSTED,
                nowEpochMillis = nowEpochMillis,
            ),
        )
        assertEquals(
            "Last run: 2 hours ago — Sync setup needs attention",
            backgroundSyncLastRunText(
                lastRunEpochMillis = nowEpochMillis - 2 * 60 * 60_000L,
                lastResult = BackgroundSyncResult.ENVIRONMENT_MISMATCH,
                nowEpochMillis = nowEpochMillis,
            ),
        )
        assertEquals(
            "Last run: 3 days ago — Network unavailable",
            backgroundSyncLastRunText(
                lastRunEpochMillis = nowEpochMillis - 3 * 24 * 60 * 60_000L,
                lastResult = TestResult.NETWORK_UNAVAILABLE,
                nowEpochMillis = nowEpochMillis,
            ),
        )
    }

    @Test
    fun treatsFutureClockSkewAsJustNow() {
        assertEquals(
            "Last run: just now — Sync successful",
            backgroundSyncLastRunText(
                lastRunEpochMillis = nowEpochMillis + 1_000L,
                lastResult = BackgroundSyncResult.SUCCESS,
                nowEpochMillis = nowEpochMillis,
            ),
        )
    }

    @Test
    fun usesPlainLanguageForAllKnownTerminalResults() {
        assertEquals(
            "Last run: just now — Some actions need attention",
            backgroundSyncLastRunText(
                lastRunEpochMillis = nowEpochMillis,
                lastResult = BackgroundSyncResult.PARTIAL_REJECTIONS,
                nowEpochMillis = nowEpochMillis,
            ),
        )
        assertEquals(
            "Last run: just now — Server update needed; actions still pending",
            backgroundSyncLastRunText(
                lastRunEpochMillis = nowEpochMillis,
                lastResult = BackgroundSyncResult.BACKEND_CAPABILITY_PENDING,
                nowEpochMillis = nowEpochMillis,
            ),
        )
        assertEquals(
            "Last run: just now — Completed without confirmation",
            backgroundSyncLastRunText(
                lastRunEpochMillis = nowEpochMillis,
                lastResult = BackgroundSyncResult.COMPLETED_WITHOUT_ACK,
                nowEpochMillis = nowEpochMillis,
            ),
        )
    }

    private enum class TestResult {
        NETWORK_UNAVAILABLE,
    }
}
