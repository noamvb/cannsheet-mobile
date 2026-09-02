package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PurchaseThcFormattingTest {
    @Test
    fun `thc percent text removes floating point noise`() {
        assertEquals("27.14", canonicalThcPercentText(27.140000000000004))
        assertEquals("23.45", canonicalThcPercentText(23.450000000000003))
        assertEquals("23.45", canonicalThcPercentText(23.45))
    }

    @Test
    fun `thc percent text strips insignificant zeroes without scientific notation`() {
        assertEquals("25", canonicalThcPercentText(25.0))
        assertEquals("23", canonicalThcPercentText(23.0))
        assertEquals("0", canonicalThcPercentText(0.0))
        assertEquals("20", canonicalThcPercentText(20.0))
    }

    @Test
    fun `thc percent text rounds half up to two decimal places`() {
        assertEquals("12.35", canonicalThcPercentText(12.345))
    }

    @Test
    fun `thc percent text rejects non-finite values`() {
        assertEquals("", canonicalThcPercentText(Double.NaN))
        assertEquals("", canonicalThcPercentText(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `purchase numbers retain their full precision`() {
        assertEquals("12.5", canonicalPurchaseNumber(12.5))
        assertEquals("3.5", canonicalPurchaseNumber(3.5))
        assertEquals("1234.5678", canonicalPurchaseNumber(1234.5678))
    }
}
