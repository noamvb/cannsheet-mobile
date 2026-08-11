package com.example.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseDefaultsRepositoryTest {
    @Test
    fun keyNormalizesNameAndTypeUsingRootLocale() {
        assertEquals(
            PurchaseDefaultKey(name = "blue dream", type = "F"),
            PurchaseDefaultKey(name = "  BLUE DREAM  ", type = " f "),
        )
    }

    @Test
    fun keyAndValueValidationRejectsBlankOrInvalidNumbers() {
        assertFalse(isValidPurchaseDefaultKey(PurchaseDefaultKey(" ", "F")))
        assertFalse(isValidPurchaseDefaultKey(PurchaseDefaultKey("Name", "  ")))
        assertFalse(isValidPurchaseDefaultValues(PurchaseDefaultValues(-0.01, 0.2, 3.5)))
        assertFalse(isValidPurchaseDefaultValues(PurchaseDefaultValues(10.0, 1.01, 3.5)))
        assertFalse(isValidPurchaseDefaultValues(PurchaseDefaultValues(10.0, 0.2, 0.0)))
        assertFalse(isValidPurchaseDefaultValues(PurchaseDefaultValues(10.0, Double.NaN, 3.5)))
        assertTrue(isValidPurchaseDefaultValues(PurchaseDefaultValues(0.0, 0.0, 3.5)))
    }

    @Test
    fun canonicalThcFractionAcceptsZeroAndRejectsOutsideTheFractionRange() {
        assertEquals(0.0, canonicalThcFraction(-0.0))
        assertEquals(0.875, canonicalThcFraction(0.875))
        assertEquals(null, canonicalThcFraction(-0.01))
        assertEquals(null, canonicalThcFraction(1.01))
        assertEquals(null, canonicalThcFraction(Double.POSITIVE_INFINITY))
    }

    @Test
    fun submissionValidationAndDefaultProjectionArePure() {
        val submission = PurchaseSubmission(
            date = "2026-08-10",
            type = " f ",
            name = " Blue Dream ",
            cost = 20.0,
            thc = 0.0,
            grams = 3.5,
            borrowed = false,
            postTax = true,
            saveAsDefault = true,
        )

        assertTrue(isValidPurchaseSubmission(submission))
        assertEquals(PurchaseDefaultKey("blue dream", "F"), submission.defaultKey())
        assertEquals(PurchaseDefaultValues(20.0, 0.0, 3.5), submission.defaultValues())
        assertFalse(isValidPurchaseSubmission(submission.copy(date = "  ")))
    }

    @Test
    fun decodeReturnsEmptyForMissingMalformedAndUnsupportedPayloads() {
        assertEquals(emptyMap<PurchaseDefaultKey, PurchaseDefaultValues>(), decodePurchaseDefaults(null))
        assertEquals(emptyMap<PurchaseDefaultKey, PurchaseDefaultValues>(), decodePurchaseDefaults("not json"))
        assertEquals(
            emptyMap<PurchaseDefaultKey, PurchaseDefaultValues>(),
            decodePurchaseDefaults("{\"version\":2,\"defaults\":[]}"),
        )
    }

    @Test
    fun decodeSkipsInvalidRecordsKeepsExplicitZeroThcAndLetsLaterDuplicatesWin() {
        val defaults = decodePurchaseDefaults(
            """{
                "version":1,
                "defaults":[
                  {"type":"f","name":"Blue Dream","cost":12.5,"thc":0.2,"grams":3.5},
                  {"type":"F","name":" blue dream ","cost":15.0,"thc":0.0,"grams":7.0},
                  {"type":"E","name":"Invalid grams","cost":10.0,"thc":0.1,"grams":0.0},
                  {"type":"E","name":"Invalid cost type","cost":"10.0","thc":0.1,"grams":1.0},
                  {"type":"J","name":"Invalid THC","cost":10.0,"thc":1.1,"grams":1.0},
                  {"type":"S","name":"","cost":10.0,"thc":0.1,"grams":1.0}
                ]
            }""".trimIndent(),
        )

        assertEquals(
            linkedMapOf(PurchaseDefaultKey("blue dream", "F") to PurchaseDefaultValues(15.0, 0.0, 7.0)),
            defaults,
        )
    }

    @Test
    fun decodeProducesDeterministicTypeThenNameOrdering() {
        val defaults = decodePurchaseDefaults(
            """{"version":1,"defaults":[
                {"type":"f","name":"Zulu","cost":1.0,"thc":0.1,"grams":1.0},
                {"type":"e","name":"Beta","cost":1.0,"thc":0.1,"grams":1.0},
                {"type":"f","name":"Alpha","cost":1.0,"thc":0.1,"grams":1.0}
            ]}""",
        )

        assertEquals(
            listOf(
                PurchaseDefaultKey("beta", "E"),
                PurchaseDefaultKey("alpha", "F"),
                PurchaseDefaultKey("zulu", "F"),
            ),
            defaults.keys.toList(),
        )
    }

    @Test
    fun saveDefaultReplacesOnlyItsTargetAndUsesOneAtomicEdit() = runBlocking {
        val dataStore = RecordingPreferencesDataStore(
            preferencesOf(
                stringPreferencesKey(PURCHASE_DEFAULTS_JSON_KEY) to
                    """{"version":1,"defaults":[
                        {"type":"E","name":"Edible","cost":5.0,"thc":0.1,"grams":1.0},
                        {"type":"F","name":"Flower","cost":10.0,"thc":0.2,"grams":3.5}
                    ]}""",
            ),
        )
        val repository = PurchaseDefaultsRepository(dataStore)

        repository.saveDefault(
            PurchaseDefaultKey(" flower ", "f"),
            PurchaseDefaultValues(cost = 15.0, thc = 0.0, grams = 7.0),
        )

        assertEquals(1, dataStore.updateCount)
        assertTrue(dataStore.currentJson().orEmpty().contains("\"version\":1"))
        assertTrue(
            dataStore.currentJson().orEmpty().indexOf("\"type\":\"E\"") <
                dataStore.currentJson().orEmpty().indexOf("\"type\":\"F\""),
        )
        assertEquals(
            linkedMapOf(
                PurchaseDefaultKey("edible", "E") to PurchaseDefaultValues(5.0, 0.1, 1.0),
                PurchaseDefaultKey("flower", "F") to PurchaseDefaultValues(15.0, 0.0, 7.0),
            ),
            decodePurchaseDefaults(dataStore.currentJson()),
        )
    }

    @Test
    fun stateEmitsNotLoadedBeforeTheDecodedSnapshot() = runBlocking {
        val dataStore = RecordingPreferencesDataStore()
        val repository = PurchaseDefaultsRepository(dataStore)

        assertEquals(PurchaseDefaultsState.NotLoaded, repository.state.first())
        assertEquals(PurchaseDefaultsState.Loaded(emptyMap()), repository.state.first { it is PurchaseDefaultsState.Loaded })
    }

    @Test
    fun saveDefaultPropagatesDataStoreWriteFailures() {
        val repository = PurchaseDefaultsRepository(FailingPreferencesDataStore())

        assertThrows(IOException::class.java) {
            runBlocking {
                repository.saveDefault(
                    PurchaseDefaultKey("Flower", "F"),
                    PurchaseDefaultValues(10.0, 0.2, 3.5),
                )
            }
        }
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

        fun currentJson(): String? = state.value[stringPreferencesKey(PURCHASE_DEFAULTS_JSON_KEY)]
    }

    private class FailingPreferencesDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            throw IOException("simulated write failure")
        }
    }
}
