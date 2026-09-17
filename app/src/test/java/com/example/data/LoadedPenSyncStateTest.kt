package com.example.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadedPenSyncStateTest {
    @Test
    fun setLoadedPenAtClockTimeIsPending() = runBlocking {
        val repository = ConsumptionPreferencesRepository(RecordingPreferencesDataStore()) { 100 }

        repository.setLoadedPenProductId("*P115")

        assertEquals(PendingLoadedPenState("*P115", 100), repository.pendingLoadedPenState())
    }

    @Test
    fun markingSyncedAtTheUpdateTimeClearsPending() = runBlocking {
        val repository = ConsumptionPreferencesRepository(RecordingPreferencesDataStore()) { 100 }

        repository.setLoadedPenProductId("*P115")
        repository.markLoadedPenStateSynced(100)

        assertNull(repository.pendingLoadedPenState())
    }

    @Test
    fun laterUpdateStaysPendingWhenMarkedSyncedWithAnOlderTimestamp() = runBlocking {
        var now = 100L
        val repository = ConsumptionPreferencesRepository(RecordingPreferencesDataStore()) { now }

        repository.setLoadedPenProductId("*P115")
        repository.markLoadedPenStateSynced(100)
        now = 200L
        repository.setLoadedPenProductId("*P115")
        repository.markLoadedPenStateSynced(100)

        assertEquals(PendingLoadedPenState("*P115", 200), repository.pendingLoadedPenState())
    }

    @Test
    fun markingSyncedNeverMovesTheSyncedTimestampBackwards() = runBlocking {
        val repository = ConsumptionPreferencesRepository(RecordingPreferencesDataStore()) { 200L }

        repository.setLoadedPenProductId("*P115")
        repository.markLoadedPenStateSynced(200)
        assertNull(repository.pendingLoadedPenState())
        // A late acknowledgement for an older state must not re-open the newer one.
        repository.markLoadedPenStateSynced(100)

        assertNull(repository.pendingLoadedPenState())
    }

    @Test
    fun clearingTheLoadedPenIsAPendingNullState() = runBlocking {
        var now = 100L
        val repository = ConsumptionPreferencesRepository(RecordingPreferencesDataStore()) { now }

        repository.setLoadedPenProductId("*P115")
        now = 300L
        repository.clearLoadedPenProductId()

        assertEquals(PendingLoadedPenState(null, 300), repository.pendingLoadedPenState())
    }

    private class RecordingPreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            transform(state.value).also { updated -> state.value = updated }
        }
    }
}
