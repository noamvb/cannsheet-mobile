package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductUsageFormattingTest {
    @Test
    fun formatsUsageWithAtMostSixDecimalPlacesAndNoTrailingZeros() {
        assertEquals("1", formatUsageAmount(1.0))
        assertEquals("3.25", formatUsageAmount(3.25))
        assertEquals("0.3", formatUsageAmount(0.30000000000000004))
        assertEquals("1.234568", formatUsageAmount(1.23456789))
        assertEquals("0", formatUsageAmount(0.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeUsage() {
        formatUsageAmount(-0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteUsage() {
        formatUsageAmount(Double.POSITIVE_INFINITY)
    }
}
