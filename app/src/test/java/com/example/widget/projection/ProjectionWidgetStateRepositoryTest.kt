package com.example.widget.projection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectionWidgetStateRepositoryTest {
    @Test
    fun remapMovesModeAndClearsOldId() = runBlocking {
        val repository = repository()
        repository.writeMode(42, ProjectionWidgetConfiguration.MODE_SPEND)

        repository.remapWidgetIds(
            oldWidgetIds = intArrayOf(42),
            newWidgetIds = intArrayOf(17),
        )

        assertEquals(ProjectionWidgetConfiguration.MODE_SPEND, repository.readMode(17))
        assertNull(repository.readMode(42))
    }

    @Test
    fun remapSnapshotsOverlappingIdsBeforeWriting() = runBlocking {
        val repository = repository()
        repository.writeMode(42, ProjectionWidgetConfiguration.MODE_SPEND)
        repository.writeMode(17, ProjectionWidgetConfiguration.MODE_RUNWAY)

        repository.remapWidgetIds(
            oldWidgetIds = intArrayOf(42, 17),
            newWidgetIds = intArrayOf(17, 99),
        )

        assertEquals(ProjectionWidgetConfiguration.MODE_SPEND, repository.readMode(17))
        assertEquals(ProjectionWidgetConfiguration.MODE_RUNWAY, repository.readMode(99))
        assertNull(repository.readMode(42))
    }

    private fun repository() = ProjectionWidgetStateRepository(InMemoryPreferencesDataStore())

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
