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
 * Pure-JVM coverage for the A6 restore-remapping contract.
 *
 * These tests intentionally call the public
 * [PenWidgetStateRepository.remapWidgetIds] API introduced for A6.
 */
class PenWidgetRestoreTest {
    @Test
    fun remapMovesDraftPendingAndConfigToTheNewId() = runBlocking {
        val repository = repository()
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

        repository.setDraftSeconds(42, 40)
        repository.writeConfig(42, draftConfig)
        val pending = seedPendingState(repository, 43, "moved", pendingConfig)

        repository.remapWidgetIds(
            oldWidgetIds = intArrayOf(42, 43),
            newWidgetIds = intArrayOf(17, 18),
        )

        assertEquals(40, repository.read(17).draftSeconds)
        assertNull(repository.read(17).pendingCommit)
        assertEquals(draftConfig, repository.readConfig(17))

        val movedPendingState = repository.read(18)
        assertEquals(pending, movedPendingState.pendingCommit)
        assertEquals(9_000L, movedPendingState.lastQueuedAtMillis)
        assertEquals(pendingConfig, repository.readConfig(18))
    }

    @Test
    fun remapClearsTheOldId() = runBlocking {
        val repository = repository()
        repository.setDraftSeconds(42, 50)
        repository.writeConfig(
            42,
            PenWidgetInstanceConfig(
                pinnedProductId = "draft-product",
                discreet = true,
                stepSecondsOverride = 30,
            ),
        )
        seedPendingState(
            repository = repository,
            appWidgetId = 43,
            prefix = "cleared",
            config = PenWidgetInstanceConfig(pinnedProductId = "pending-product"),
        )

        repository.remapWidgetIds(
            oldWidgetIds = intArrayOf(42, 43),
            newWidgetIds = intArrayOf(17, 18),
        )

        assertEmpty(repository, 42)
        assertEmpty(repository, 43)
    }

    @Test
    fun remapHandlesOverlappingOldAndNewIds() = runBlocking {
        val repository = repository()
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
        repository.setDraftSeconds(42, 40)
        repository.writeConfig(42, firstConfig)
        repository.setDraftSeconds(17, 70)
        repository.writeConfig(17, secondConfig)

        repository.remapWidgetIds(
            oldWidgetIds = intArrayOf(42, 17),
            newWidgetIds = intArrayOf(17, 99),
        )

        assertEquals(40, repository.read(17).draftSeconds)
        assertEquals(firstConfig, repository.readConfig(17))
        assertEquals(70, repository.read(99).draftSeconds)
        assertEquals(secondConfig, repository.readConfig(99))
        assertEmpty(repository, 42)
    }

    @Test(expected = IllegalArgumentException::class)
    fun remapRejectsMismatchedArrayLengths() = runBlocking {
        repository().remapWidgetIds(
            oldWidgetIds = intArrayOf(42),
            newWidgetIds = intArrayOf(17, 18),
        )
    }

    @Test
    fun remapLeavesUnrelatedWidgetsUntouched() = runBlocking {
        val repository = repository()
        val unrelatedConfig = PenWidgetInstanceConfig(
            pinnedProductId = "unrelated-product",
            discreet = true,
            stepSecondsOverride = 10,
        )
        val unrelatedPending = seedPendingState(
            repository = repository,
            appWidgetId = 88,
            prefix = "unrelated",
            config = unrelatedConfig,
        )
        val unrelatedBefore = repository.read(88)
        val unrelatedConfigBefore = repository.readConfig(88)

        repository.setDraftSeconds(42, 30)
        repository.remapWidgetIds(
            oldWidgetIds = intArrayOf(42),
            newWidgetIds = intArrayOf(17),
        )

        assertEquals(unrelatedBefore, repository.read(88))
        assertEquals(unrelatedConfigBefore, repository.readConfig(88))
        assertEquals(unrelatedPending, repository.read(88).pendingCommit)
        assertTrue(repository.read(88).lastQueuedAtMillis != null)
        assertEquals(30, repository.read(17).draftSeconds)
        assertFalse(repository.read(42).pendingCommit != null)
    }

    private fun repository() = PenWidgetStateRepository(InMemoryPreferencesDataStore())

    private suspend fun seedPendingState(
        repository: PenWidgetStateRepository,
        appWidgetId: Int,
        prefix: String,
        config: PenWidgetInstanceConfig,
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
        repository.writeConfig(appWidgetId, config)
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
        seconds = seconds,
        secondsPerUse = 10.0,
        uses = seconds / 10.0,
        date = "2026-08-20",
        time = "12:00",
    )

    private suspend fun assertEmpty(
        repository: PenWidgetStateRepository,
        appWidgetId: Int,
    ) {
        assertEquals(PenWidgetStoredState(0, null, null), repository.read(appWidgetId))
        assertEquals(PenWidgetInstanceConfig.DEFAULT, repository.readConfig(appWidgetId))
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
