package com.example.widget

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PenWidgetDefaultStepTest {
    @Test
    fun effectiveStepUsesDefaultUnlessTheWidgetOverridesIt() = runBlocking {
        val repository = repository()
        repository.writeDefaultStepSeconds(30)

        assertEquals(30, repository.effectiveStepSeconds(41))

        repository.write(41, PenWidgetInstanceConfig(stepSecondsOverride = 5))

        assertEquals(5, repository.effectiveStepSeconds(41))
    }

    @Test
    fun defaultWritesAreClampedAndInvalidStoredValuesFallBackWithoutRepair() = runBlocking {
        val repository = repository()

        repository.writeDefaultStepSeconds(0)
        assertEquals(1, repository.readDefaultStepSeconds())
        assertEquals(1, repository.defaultStepSecondsFlow().first())

        repository.writeDefaultStepSeconds(MAX_SECONDS + 1)
        assertEquals(MAX_SECONDS, repository.effectiveStepSeconds(42))

        val invalidStore = InMemoryPreferencesDataStore(
            mutablePreferencesOf(intPreferencesKey("default_step_seconds") to 0),
        )
        val invalidRepository = PenWidgetConfigRepository(invalidStore)
        assertEquals(STEP_SECONDS, invalidRepository.readDefaultStepSeconds())
        assertEquals(0, invalidStore.data.first()[intPreferencesKey("default_step_seconds")])
    }

    @Test
    fun clearingAWidgetLeavesTheAppDefaultIntact() = runBlocking {
        val repository = repository()
        repository.writeDefaultStepSeconds(30)
        repository.write(43, PenWidgetInstanceConfig(stepSecondsOverride = 5))

        repository.clear(43)

        assertEquals(30, repository.readDefaultStepSeconds())
    }

    @Test
    fun remappingWidgetIdsLeavesTheAppDefaultIntact() = runBlocking {
        val repository = repository()
        repository.writeDefaultStepSeconds(30)
        repository.write(44, PenWidgetInstanceConfig(stepSecondsOverride = 5))

        repository.remapWidgetIds(intArrayOf(44), intArrayOf(45))

        assertEquals(30, repository.readDefaultStepSeconds())
        assertEquals(5, repository.effectiveStepSeconds(45))
    }

    private fun repository() = PenWidgetConfigRepository(InMemoryPreferencesDataStore())

    private class InMemoryPreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)

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
