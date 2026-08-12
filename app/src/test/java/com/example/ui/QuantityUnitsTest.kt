package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QuantityUnitsTest {
    @Test
    fun secondsAndUsesRoundTripForACommonPenRate() {
        assertEquals(15.0, usesToSeconds(1.5, 10.0), 0.0)
        assertEquals(1.5, secondsToUses(15.0, 10.0), 0.0)
    }

    @Test
    fun secondsToUsesDoesNotExposeBinaryFloatNoise() {
        assertEquals(2.2, secondsToUses(22.0, 10.0), 0.0)
    }

    @Test
    fun formatsDurationWhenARateIsSet() {
        assertEquals("15s", formatQuantityInInputUnit(1.5, 10.0))
    }

    @Test
    fun formatsPlainUsesWhenNoRateIsSet() {
        assertEquals("1.5", formatQuantityInInputUnit(1.5, null))
    }
}
