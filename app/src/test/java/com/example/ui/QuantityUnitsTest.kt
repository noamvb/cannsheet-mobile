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
    fun roundsSecondsRoundTripForNonDivisorRatesAtPresentationOnly() {
        listOf(
            Triple(10.0, 7.0, "10s"),
            Triple(10.0, 3.0, "10s"),
            Triple(20.0, 3.0, "20s"),
            Triple(10.0, 7.5, "10s"),
            Triple(10.0, 10.0, "10s"),
        ).forEach { (seconds, rate, expected) ->
            val storedUses = secondsToUses(seconds, rate)
            assertEquals(expected, formatQuantityInInputUnit(storedUses, rate))
        }
    }

    @Test
    fun formatsPlainUsesWhenNoRateIsSet() {
        assertEquals("1.5", formatQuantityInInputUnit(1.5, null))
    }
}
