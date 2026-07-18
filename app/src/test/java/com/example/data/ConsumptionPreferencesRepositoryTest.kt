package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionPreferencesRepositoryTest {
    @Test
    fun defaultPresetsMatchQuickLogDefaults() {
        assertEquals(
            listOf(0.5, 1.0, 2.0),
            ConsumptionPreferencesRepository.DEFAULT_QUANTITY_PRESETS,
        )
    }

    @Test
    fun validatesOneToTenPositiveFiniteDistinctPresets() {
        assertTrue(ConsumptionPreferencesRepository.isValidQuantityPresets(listOf(1.0)))
        assertTrue(ConsumptionPreferencesRepository.isValidQuantityPresets(listOf(0.25, 1.0, 3.0)))
        assertTrue(
            ConsumptionPreferencesRepository.isValidQuantityPresets(
                (1..10).map { it.toDouble() }
            )
        )
        assertFalse(ConsumptionPreferencesRepository.isValidQuantityPresets(emptyList()))
        assertFalse(
            ConsumptionPreferencesRepository.isValidQuantityPresets(
                (1..11).map { it.toDouble() }
            )
        )
        assertFalse(ConsumptionPreferencesRepository.isValidQuantityPresets(listOf(1.0, 1.0, 2.0)))
        assertFalse(ConsumptionPreferencesRepository.isValidQuantityPresets(listOf(0.0, 1.0, 2.0)))
        assertFalse(ConsumptionPreferencesRepository.isValidQuantityPresets(listOf(Double.NaN, 1.0, 2.0)))
    }

    @Test
    fun readsLegacyThreePresetsWhenCountIsMissing() {
        val legacyValues = mapOf(1 to 0.25, 2 to 0.75, 3 to 1.5)

        assertEquals(
            listOf(0.25, 0.75, 1.5),
            ConsumptionPreferencesRepository.resolveQuantityPresets(
                storedCount = null,
                valueAt = legacyValues::get,
            ),
        )
    }

    @Test
    fun readsVariableLengthPresetsInSavedOrder() {
        val savedValues = mapOf(1 to 2.0, 2 to 0.5, 3 to 4.0, 4 to 1.0, 5 to 3.0)

        assertEquals(
            listOf(2.0, 0.5, 4.0, 1.0, 3.0),
            ConsumptionPreferencesRepository.resolveQuantityPresets(
                storedCount = 5,
                valueAt = savedValues::get,
            ),
        )
    }

    @Test
    fun invalidOrIncompleteStoredPresetsFallBackToDefaults() {
        assertEquals(
            ConsumptionPreferencesRepository.DEFAULT_QUANTITY_PRESETS,
            ConsumptionPreferencesRepository.resolveQuantityPresets(
                storedCount = 11,
                valueAt = { it.toDouble() },
            ),
        )
        assertEquals(
            ConsumptionPreferencesRepository.DEFAULT_QUANTITY_PRESETS,
            ConsumptionPreferencesRepository.resolveQuantityPresets(
                storedCount = 4,
                valueAt = { index -> if (index == 4) null else index.toDouble() },
            ),
        )
        assertEquals(
            ConsumptionPreferencesRepository.DEFAULT_QUANTITY_PRESETS,
            ConsumptionPreferencesRepository.resolveQuantityPresets(
                storedCount = 2,
                valueAt = { 1.0 },
            ),
        )
    }
}

