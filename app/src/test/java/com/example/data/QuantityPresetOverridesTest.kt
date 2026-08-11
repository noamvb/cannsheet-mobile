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
import org.junit.Assert.assertThrows
import org.junit.Test

class QuantityPresetOverridesTest {
    @Test
    fun productTypeKeyNormalizesWithRootLocale() {
        assertEquals(ProductTypeKey("F"), ProductTypeKey(" f "))
    }

    @Test
    fun decodeReturnsEmptyForMissingMalformedAndUnsupportedPayloads() {
        assertEquals(emptyMap<ProductTypeKey, List<Double>>(), decodeQuantityPresetOverrides(null))
        assertEquals(emptyMap<ProductTypeKey, List<Double>>(), decodeQuantityPresetOverrides("not json"))
        assertEquals(
            emptyMap<ProductTypeKey, List<Double>>(),
            decodeQuantityPresetOverrides("{\"version\":2,\"overrides\":[]}"),
        )
        assertEquals(
            emptyMap<ProductTypeKey, List<Double>>(),
            decodeQuantityPresetOverrides("{\"version\":1,\"overrides\":{}}"),
        )
    }

    @Test
    fun decodeSkipsInvalidRecordsWithoutDroppingValidRecords() {
        val overrides = decodeQuantityPresetOverrides(
            """
            {
              "version":1,
              "overrides":[
                {"type":" ","presets":[1.0]},
                {"type":"E","presets":[]},
                {"type":"J","presets":[1,2,3,4,5,6,7,8,9,10,11]},
                {"type":"K","presets":[1,1]},
                {"type":"P","presets":[0,1]},
                {"type":"S","presets":[-1,1]},
                {"type":"Y","presets":[1,"2",3]},
                {"type":"F","presets":[0.25,1.0]}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            linkedMapOf(ProductTypeKey("F") to listOf(0.25, 1.0)),
            overrides,
        )
    }

    @Test
    fun decodeTreatsNonFiniteJsonNumbersAsInvalidPayloadData() {
        assertEquals(
            emptyMap<ProductTypeKey, List<Double>>(),
            decodeQuantityPresetOverrides(
                """{"version":1,"overrides":[{"type":"X","presets":[1e309,2]}]}""",
            ),
        )
    }

    @Test
    fun decodeOrdersTypesDeterministicallyAndLaterDuplicateWins() {
        val overrides = decodeQuantityPresetOverrides(
            """
            {"version":1,"overrides":[
              {"type":"f","presets":[1.0,2.0]},
              {"type":"s","presets":[0.1,0.25]},
              {"type":"E","presets":[3.0]},
              {"type":"F","presets":[4.0,5.0]}
            ]}
            """.trimIndent(),
        )

        assertEquals(
            listOf(ProductTypeKey("E"), ProductTypeKey("F"), ProductTypeKey("S")),
            overrides.keys.toList(),
        )
        assertEquals(listOf(4.0, 5.0), overrides[ProductTypeKey("F")])
    }

    @Test
    fun preferencesSnapshotIncludesOverridesWithTheGlobalValues() = runBlocking {
        val dataStore = RecordingPreferencesDataStore(
            preferencesOf(
                stringPreferencesKey(QUANTITY_PRESET_OVERRIDES_JSON_KEY) to
                    encodeQuantityPresetOverrides(
                        mapOf(ProductTypeKey("S") to listOf(0.1, 0.25)),
                    ),
            ),
        )
        val snapshot = ConsumptionPreferencesRepository(dataStore).preferences.first()

        assertEquals(ConsumptionPreferencesRepository.DEFAULT_QUANTITY_PRESETS, snapshot.quantityPresets)
        assertEquals(
            mapOf(ProductTypeKey("S") to listOf(0.1, 0.25)),
            snapshot.quantityPresetOverrides,
        )
    }

    @Test
    fun setReplacesOnlyItsTargetNormalizesTheKeyAndUsesOneAtomicEdit() = runBlocking {
        val dataStore = RecordingPreferencesDataStore(
            preferencesOf(
                stringPreferencesKey(QUANTITY_PRESET_OVERRIDES_JSON_KEY) to
                    """
                    {"version":1,"overrides":[
                      {"type":"E","presets":[1.0]},
                      {"type":"S","presets":[0.1,0.25]}
                    ]}
                    """.trimIndent(),
            ),
        )
        val repository = ConsumptionPreferencesRepository(dataStore)

        repository.setQuantityPresetsForType(ProductTypeKey(" s "), listOf(0.5, 1.0, 2.0))

        assertEquals(1, dataStore.updateCount)
        assertEquals(
            linkedMapOf(
                ProductTypeKey("E") to listOf(1.0),
                ProductTypeKey("S") to listOf(0.5, 1.0, 2.0),
            ),
            decodeQuantityPresetOverrides(dataStore.currentJson()),
        )
    }

    @Test
    fun clearRemovesOnlyItsTargetAndAlwaysRewritesThePayload() = runBlocking {
        val dataStore = RecordingPreferencesDataStore(
            preferencesOf(
                stringPreferencesKey(QUANTITY_PRESET_OVERRIDES_JSON_KEY) to
                    encodeQuantityPresetOverrides(
                        linkedMapOf(
                            ProductTypeKey("E") to listOf(1.0),
                            ProductTypeKey("F") to listOf(2.0),
                        ),
                    ),
            ),
        )
        val repository = ConsumptionPreferencesRepository(dataStore)

        repository.clearQuantityPresetsForType(ProductTypeKey(" f "))

        assertEquals(1, dataStore.updateCount)
        assertEquals(
            linkedMapOf(ProductTypeKey("E") to listOf(1.0)),
            decodeQuantityPresetOverrides(dataStore.currentJson()),
        )
    }

    @Test
    fun clearUnknownTypeStillWritesAnEmptyOrEquivalentVersionedPayload() = runBlocking {
        val dataStore = RecordingPreferencesDataStore()
        val repository = ConsumptionPreferencesRepository(dataStore)

        repository.clearQuantityPresetsForType(ProductTypeKey("X"))

        assertEquals(1, dataStore.updateCount)
        assertEquals(emptyMap<ProductTypeKey, List<Double>>(), decodeQuantityPresetOverrides(dataStore.currentJson()))
        assertEquals(
            "{\"version\":1,\"overrides\":[]}",
            dataStore.currentJson(),
        )
    }

    @Test
    fun invalidTypeOrPresetsAreRejectedBeforeWriting() {
        val dataStore = RecordingPreferencesDataStore()
        val repository = ConsumptionPreferencesRepository(dataStore)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.setQuantityPresetsForType(ProductTypeKey(" "), listOf(1.0))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.setQuantityPresetsForType(ProductTypeKey("F"), listOf(0.0, 1.0))
            }
        }
        assertEquals(0, dataStore.updateCount)
    }

    @Test
    fun dataStoreWriteFailurePropagates() {
        val repository = ConsumptionPreferencesRepository(FailingPreferencesDataStore())

        assertThrows(IOException::class.java) {
            runBlocking {
                repository.setQuantityPresetsForType(ProductTypeKey("F"), listOf(1.0))
            }
        }
    }

    @Test
    fun encodeThenDecodePreservesTheOriginalMap() {
        val original = linkedMapOf(
            ProductTypeKey("S") to listOf(0.1, 0.25),
            ProductTypeKey("F") to listOf(0.5, 1.0, 2.0),
        )

        assertEquals(original, decodeQuantityPresetOverrides(encodeQuantityPresetOverrides(original)))
    }

    @Test
    fun effectivePresetsUseGlobalForMissingBlankUnknownOrInvalidTypes() {
        val global = listOf(0.5, 1.0, 2.0)
        val overrides = mapOf(
            ProductTypeKey("S") to listOf(0.1, 0.25),
            ProductTypeKey("F") to listOf(0.0, 1.0),
        )

        assertEquals(global, ConsumptionPreferencesRepository.effectiveQuantityPresets(global, overrides, null))
        assertEquals(global, ConsumptionPreferencesRepository.effectiveQuantityPresets(global, overrides, "  "))
        assertEquals(global, ConsumptionPreferencesRepository.effectiveQuantityPresets(global, overrides, "P"))
        assertEquals(
            listOf(0.1, 0.25),
            ConsumptionPreferencesRepository.effectiveQuantityPresets(global, overrides, " s "),
        )
        assertEquals(global, ConsumptionPreferencesRepository.effectiveQuantityPresets(global, overrides, "F"))
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

        fun currentJson(): String? = state.value[stringPreferencesKey(QUANTITY_PRESET_OVERRIDES_JSON_KEY)]
    }

    private class FailingPreferencesDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            throw IOException("simulated write failure")
        }
    }
}
