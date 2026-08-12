package com.example.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondsPerUseOverridesTest {
    @Test
    fun missingPayloadSeedsThePenRate() {
        assertEquals(
            mapOf(ProductTypeKey("P") to 10.0),
            decodeSecondsPerUseOverrides(null),
        )
    }

    @Test
    fun validPayloadWithoutPenPreservesTheExplicitlyStoredMap() {
        assertEquals(
            mapOf(ProductTypeKey("E") to 60.0),
            decodeSecondsPerUseOverrides(
                """{"version":1,"overrides":[{"type":"E","secondsPerUse":60.0}]}""",
            ),
        )
    }

    @Test
    fun malformedOrUnsupportedPayloadDoesNotReseedThePenRate() {
        assertEquals(emptyMap<ProductTypeKey, Double>(), decodeSecondsPerUseOverrides("not json"))
        assertEquals(
            emptyMap<ProductTypeKey, Double>(),
            decodeSecondsPerUseOverrides("""{"version":2,"overrides":[]}"""),
        )
    }

    @Test
    fun invalidRatesAreRejectedWithoutDroppingValidRecords() {
        assertEquals(
            mapOf(ProductTypeKey("F") to 30.0),
            decodeSecondsPerUseOverrides(
                """
                {"version":1,"overrides":[
                  {"type":"P","secondsPerUse":0},
                  {"type":"E","secondsPerUse":3601},
                  {"type":"J","secondsPerUse":-1},
                  {"type":"F","secondsPerUse":30}
                ]}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun encodeAndDecodeRoundTripPreservesTheMap() {
        val original = linkedMapOf(
            ProductTypeKey("S") to 5.0,
            ProductTypeKey("P") to 10.0,
        )

        assertEquals(original, decodeSecondsPerUseOverrides(encodeSecondsPerUseOverrides(original)))
    }

    @Test
    fun clearPenRateWritesAnExplicitPayloadSoTheSeedDoesNotReturn() = runBlocking {
        val dataStore = RecordingPreferencesDataStore()
        val repository = ConsumptionPreferencesRepository(dataStore)

        repository.clearSecondsPerUseForType(ProductTypeKey("P"))

        assertEquals(1, dataStore.updateCount)
        assertEquals(emptyMap<ProductTypeKey, Double>(), decodeSecondsPerUseOverrides(dataStore.currentJson()))
        assertEquals(
            emptyMap<ProductTypeKey, Double>(),
            repository.preferences.first().secondsPerUseOverrides,
        )
        assertTrue(dataStore.currentJson()?.contains("\"version\":1") == true)
    }

    private class RecordingPreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        var updateCount = 0
            private set

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            updateCount += 1
            val updated = transform(state.value)
            state.value = updated
            return updated
        }

        fun currentJson(): String? = state.value[stringPreferencesKey(SECONDS_PER_USE_OVERRIDES_JSON_KEY)]
    }
}
