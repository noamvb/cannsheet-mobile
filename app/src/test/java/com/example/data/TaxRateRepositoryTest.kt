package com.example.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TaxRateRepositoryTest {
    @Test
    fun validRateRoundTrips() = runBlocking {
        val repository = repository()

        repository.record(0.13)

        assertEquals(0.13, repository.taxRate.first()!!, 0.0)
    }

    @Test
    fun absentRateLeavesPreviouslyRecordedRateInPlace() = runBlocking {
        val repository = repositoryWithRate(0.13)

        repository.record(null)

        assertEquals(0.13, repository.taxRate.first()!!, 0.0)
    }

    @Test
    fun outOfRangeRatesLeavePreviouslyRecordedRateInPlace() = runBlocking {
        val repository = repositoryWithRate(0.13)

        repository.record(1.0)
        repository.record(-0.01)

        assertEquals(0.13, repository.taxRate.first()!!, 0.0)
    }

    @Test
    fun nanLeavesPreviouslyRecordedRateInPlace() = runBlocking {
        val repository = repositoryWithRate(0.13)

        repository.record(Double.NaN)

        assertEquals(0.13, repository.taxRate.first()!!, 0.0)
    }

    @Test
    fun zeroIsStorable() = runBlocking {
        val repository = repository()

        repository.record(0.0)

        assertEquals(0.0, repository.taxRate.first()!!, 0.0)
    }

    private suspend fun repositoryWithRate(rate: Double): TaxRateRepository =
        repository().also { it.record(rate) }

    private fun repository() = TaxRateRepository(InMemoryPreferencesDataStore())

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
