package com.example.data.sync

import com.example.data.QueuedSyncSnapshot
import com.example.data.SyncAcknowledgementPlan
import com.example.data.SyncOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkerPrefetchGateTest {
    @Test
    fun nothingToSyncPrefetchesOnlyWhenRequested() {
        assertEquals(true, shouldPrefetchAnalytics(BackgroundSyncRunResult.NothingToSync, prefetchRequested = true))
        assertEquals(false, shouldPrefetchAnalytics(BackgroundSyncRunResult.NothingToSync, prefetchRequested = false))
    }

    @Test
    fun appliedPrefetchesOnlyWhenRequested() {
        val applied = BackgroundSyncRunResult.Applied(appliedOutcome(), completedPasses = 1)
        assertEquals(true, shouldPrefetchAnalytics(applied, prefetchRequested = true))
        assertEquals(false, shouldPrefetchAnalytics(applied, prefetchRequested = false))
    }

    @Test
    fun environmentMismatchNeverPrefetches() {
        val mismatch = BackgroundSyncRunResult.EnvironmentMismatch(
            SyncOutcome.EnvironmentMismatch(emptyList()),
        )
        assertEquals(false, shouldPrefetchAnalytics(mismatch, prefetchRequested = true))
        assertEquals(false, shouldPrefetchAnalytics(mismatch, prefetchRequested = false))
    }

    @Test
    fun retryNeverPrefetches() {
        val retry = BackgroundSyncRunResult.Retry(SyncOutcome.HtmlResponse(emptyList()))
        assertEquals(false, shouldPrefetchAnalytics(retry, prefetchRequested = true))
        assertEquals(false, shouldPrefetchAnalytics(retry, prefetchRequested = false))
    }

    private fun appliedOutcome() = SyncOutcome.Applied(
        plan = SyncAcknowledgementPlan(acknowledgedPurchaseActionIds = setOf("purchase-action")),
        pendingCorrectionsAtSnapshot = emptyList(),
        snapshot = QueuedSyncSnapshot(emptySet(), emptySet()),
    )
}
