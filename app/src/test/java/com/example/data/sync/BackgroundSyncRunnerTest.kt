package com.example.data.sync

import com.example.data.QueuedSyncSnapshot
import com.example.data.ProductCatalogRefreshResult
import com.example.data.SyncAcknowledgementPlan
import com.example.data.SyncOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncRunnerTest {
    @Test
    fun emptyQueueCompletesWithoutCallingTheNetwork() = runBlocking {
        val operations = FakeOperations(pendingStates = ArrayDeque(listOf(false)))

        val result = BackgroundSyncRunner("https://example.test/exec", operations).run()

        assertTrue(result is BackgroundSyncRunResult.NothingToSync)
        assertEquals(0, operations.syncCalls)
    }

    @Test
    fun htmlResponseIsRetryableAndDoesNotRefreshOrEmit() = runBlocking {
        val operations = FakeOperations(
            pendingStates = ArrayDeque(listOf(true)),
            outcomes = ArrayDeque(listOf(SyncOutcome.HtmlResponse(emptyList()))),
        )

        val result = BackgroundSyncRunner("https://example.test/exec", operations).run()

        assertTrue(result is BackgroundSyncRunResult.Retry)
        assertEquals(0, operations.refreshCalls)
        assertEquals(0, operations.emittedOutcomes.size)
    }

    @Test
    fun acknowledgementRefreshesCatalogEmitsAndStopsWhenQueueDrains() = runBlocking {
        val applied = appliedOutcome()
        val operations = FakeOperations(
            pendingStates = ArrayDeque(listOf(true, false)),
            outcomes = ArrayDeque(listOf(applied)),
        )

        val result = BackgroundSyncRunner("https://example.test/exec", operations).run()

        assertTrue(result is BackgroundSyncRunResult.Applied)
        assertEquals(1, operations.refreshCalls)
        assertEquals(listOf(applied), operations.emittedOutcomes)
        assertEquals(1, operations.syncCalls)
    }

    @Test
    fun neverRunsMoreThanTwoPassesWhenMoreWorkKeepsArriving() = runBlocking {
        val first = appliedOutcome()
        val second = appliedOutcome()
        val operations = FakeOperations(
            pendingStates = ArrayDeque(listOf(true, true)),
            outcomes = ArrayDeque(listOf(first, second)),
        )

        val result = BackgroundSyncRunner("https://example.test/exec", operations).run()

        assertEquals(2, (result as BackgroundSyncRunResult.Applied).completedPasses)
        assertEquals(2, operations.syncCalls)
        assertEquals(2, operations.refreshCalls)
    }

    private fun appliedOutcome(
        plan: SyncAcknowledgementPlan = SyncAcknowledgementPlan(
            acknowledgedPurchaseActionIds = setOf("purchase-action"),
        ),
    ) = SyncOutcome.Applied(
        plan = plan,
        pendingCorrectionsAtSnapshot = emptyList(),
        snapshot = QueuedSyncSnapshot(emptySet(), emptySet()),
    )

    private class FakeOperations(
        private val pendingStates: ArrayDeque<Boolean>,
        private val outcomes: ArrayDeque<SyncOutcome> = ArrayDeque(),
    ) : BackgroundSyncOperations {
        var syncCalls = 0
        var refreshCalls = 0
        val emittedOutcomes = mutableListOf<SyncOutcome.Applied>()

        override suspend fun hasPendingActions(): Boolean = pendingStates.removeFirst()

        override suspend fun sync(endpoint: String): SyncOutcome {
            syncCalls += 1
            return outcomes.removeFirst()
        }

        override suspend fun refreshCatalog(endpoint: String): ProductCatalogRefreshResult {
            refreshCalls += 1
            return ProductCatalogRefreshResult.Updated
        }

        override suspend fun emitApplied(outcome: SyncOutcome.Applied) {
            emittedOutcomes += outcome
        }
    }
}
