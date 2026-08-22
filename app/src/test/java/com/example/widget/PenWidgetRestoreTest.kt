package com.example.widget

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the restore-remapping contract.
 *
 * Configuration and queue state now live in two separate DataStores -
 * [PenWidgetConfigRepository] and [PenWidgetStateRepository] respectively - because only the
 * config store is safe to back up. `PenConsumptionWidgetProvider.onRestored` remaps both stores
 * with the same old/new id arrays, so most tests below exercise both repositories the same way.
 */
class PenWidgetRestoreTest {
    @Test
    fun remapMovesDraftAndPendingInTheStateStore() = runBlocking {
        val repository = stateRepository()

        repository.setDraftSeconds(42, 40)
        val pending = seedPendingState(repository, 43, "moved")

        repository.remapWidgetIds(
            oldWidgetIds = intArrayOf(42, 43),
            newWidgetIds = intArrayOf(17, 18),
        )

        assertEquals(40, repository.read(17).draftSeconds)
        assertNull(repository.read(17).pendingCommit)

        val movedPendingState = repository.read(18)
        assertEquals(pending, movedPendingState.pendingCommit)
        assertEquals(9_000L, movedPendingState.lastQueuedAtMillis)

        assertStateEmpty(repository, 42)
        assertStateEmpty(repository, 43)
    }

    @Test
    fun remapMovesConfigInTheConfigStore() = runBlocking {
        val repository = configRepository()
        val draftConfig = PenWidgetInstanceConfig(
            pinnedProductId = "draft-product",
            discreet = true,
            stepSecondsOverride = 30,
        )
        val pendingConfig = PenWidgetInstanceConfig(
            pinnedProductId = "pending-product",
            discreet = false,
            stepSecondsOverride = 5,
        )
        repository.write(42, draftConfig)
        repository.write(43, pendingConfig)

        repository.remapWidgetIds(
            oldWidgetIds = intArrayOf(42, 43),
            newWidgetIds = intArrayOf(17, 18),
        )

        assertEquals(draftConfig, repository.read(17))
        assertEquals(pendingConfig, repository.read(18))
        assertEquals(PenWidgetInstanceConfig.DEFAULT, repository.read(42))
        assertEquals(PenWidgetInstanceConfig.DEFAULT, repository.read(43))
    }

    @Test
    fun remapClearsTheOldId() = runBlocking {
        val state = stateRepository()
        val config = configRepository()
        state.setDraftSeconds(42, 50)
        config.write(
            42,
            PenWidgetInstanceConfig(
                pinnedProductId = "draft-product",
                discreet = true,
                stepSecondsOverride = 30,
            ),
        )
        seedPendingState(state, 43, "cleared")
        config.write(43, PenWidgetInstanceConfig(pinnedProductId = "pending-product"))

        state.remapWidgetIds(oldWidgetIds = intArrayOf(42, 43), newWidgetIds = intArrayOf(17, 18))
        config.remapWidgetIds(oldWidgetIds = intArrayOf(42, 43), newWidgetIds = intArrayOf(17, 18))

        assertStateEmpty(state, 42)
        assertStateEmpty(state, 43)
        assertEquals(PenWidgetInstanceConfig.DEFAULT, config.read(42))
        assertEquals(PenWidgetInstanceConfig.DEFAULT, config.read(43))
    }

    @Test
    fun remapHandlesOverlappingOldAndNewIds() = runBlocking {
        val state = stateRepository()
        val config = configRepository()
        val firstConfig = PenWidgetInstanceConfig(
            pinnedProductId = "first-product",
            discreet = true,
            stepSecondsOverride = 30,
        )
        val secondConfig = PenWidgetInstanceConfig(
            pinnedProductId = "second-product",
            discreet = false,
            stepSecondsOverride = 5,
        )
        state.setDraftSeconds(42, 40)
        config.write(42, firstConfig)
        state.setDraftSeconds(17, 70)
        config.write(17, secondConfig)

        state.remapWidgetIds(oldWidgetIds = intArrayOf(42, 17), newWidgetIds = intArrayOf(17, 99))
        config.remapWidgetIds(oldWidgetIds = intArrayOf(42, 17), newWidgetIds = intArrayOf(17, 99))

        assertEquals(40, state.read(17).draftSeconds)
        assertEquals(firstConfig, config.read(17))
        assertEquals(70, state.read(99).draftSeconds)
        assertEquals(secondConfig, config.read(99))
        assertStateEmpty(state, 42)
        assertEquals(PenWidgetInstanceConfig.DEFAULT, config.read(42))
    }

    @Test(expected = IllegalArgumentException::class)
    fun remapRejectsMismatchedArrayLengths() = runBlocking {
        stateRepository().remapWidgetIds(
            oldWidgetIds = intArrayOf(42),
            newWidgetIds = intArrayOf(17, 18),
        )
    }

    @Test
    fun remapLeavesUnrelatedWidgetsUntouched() = runBlocking {
        val state = stateRepository()
        val config = configRepository()
        val unrelatedConfig = PenWidgetInstanceConfig(
            pinnedProductId = "unrelated-product",
            discreet = true,
            stepSecondsOverride = 10,
        )
        val unrelatedPending = seedPendingState(state, 88, "unrelated")
        config.write(88, unrelatedConfig)
        val unrelatedStateBefore = state.read(88)
        val unrelatedConfigBefore = config.read(88)

        state.setDraftSeconds(42, 30)
        state.remapWidgetIds(oldWidgetIds = intArrayOf(42), newWidgetIds = intArrayOf(17))
        config.remapWidgetIds(oldWidgetIds = intArrayOf(42), newWidgetIds = intArrayOf(17))

        assertEquals(unrelatedStateBefore, state.read(88))
        assertEquals(unrelatedConfigBefore, config.read(88))
        assertEquals(unrelatedPending, state.read(88).pendingCommit)
        assertTrue(state.read(88).lastQueuedAtMillis != null)
        assertEquals(30, state.read(17).draftSeconds)
        assertFalse(state.read(42).pendingCommit != null)
    }

    private fun stateRepository() = PenWidgetStateRepository(InMemoryPreferencesDataStore())

    private fun configRepository() = PenWidgetConfigRepository(InMemoryPreferencesDataStore())

    private suspend fun seedPendingState(
        repository: PenWidgetStateRepository,
        appWidgetId: Int,
        prefix: String,
    ): PenWidgetCommitPayload {
        val completed = payload("$prefix-completed", seconds = 20)
        assertTrue(repository.submitCommit(appWidgetId, completed))
        val claim = requireNotNull(
            repository.claimCommit(appWidgetId, completed.commitId, nowMillis = 5_000L),
        )
        assertTrue(
            repository.completeCommit(
                appWidgetId = appWidgetId,
                commitId = completed.commitId,
                claimId = claim.claimId,
                nowMillis = 9_000L,
            ),
        )

        val pending = payload("$prefix-pending", seconds = 30)
        assertTrue(repository.submitCommit(appWidgetId, pending))
        return pending
    }

    private fun payload(prefix: String, seconds: Int) = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = "$prefix-commit",
        eventId = "$prefix-event",
        submittedAtEpochMillis = 1_000L,
        commitAtEpochMillis = 2_000L,
        productId = "$prefix-product",
        productUuid = "$prefix-uuid",
        inputKind = DeferredPenInputKind.DURATION_SECONDS,
        seconds = seconds,
        secondsPerUse = 10.0,
        restoreDraftSeconds = seconds,
        uses = seconds / 10.0,
        date = "2026-08-20",
        time = "12:00",
    )

    private suspend fun assertStateEmpty(repository: PenWidgetStateRepository, appWidgetId: Int) {
        assertEquals(PenWidgetStoredState(0, null, null), repository.read(appWidgetId))
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
