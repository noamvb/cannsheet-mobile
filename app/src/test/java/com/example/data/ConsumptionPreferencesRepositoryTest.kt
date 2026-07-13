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
    fun validatesExactlyThreePositiveFiniteDistinctPresets() {
        assertTrue(ConsumptionPreferencesRepository.isValidQuantityPresets(listOf(0.25, 1.0, 3.0)))
        assertFalse(ConsumptionPreferencesRepository.isValidQuantityPresets(listOf(1.0, 2.0)))
        assertFalse(ConsumptionPreferencesRepository.isValidQuantityPresets(listOf(1.0, 1.0, 2.0)))
        assertFalse(ConsumptionPreferencesRepository.isValidQuantityPresets(listOf(0.0, 1.0, 2.0)))
        assertFalse(ConsumptionPreferencesRepository.isValidQuantityPresets(listOf(Double.NaN, 1.0, 2.0)))
    }
}

