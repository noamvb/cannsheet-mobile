package com.example.widget

import com.example.ui.secondsToUses
import org.junit.Assert.assertEquals
import org.junit.Test

class PenWidgetSecondsConversionTest {
    @Test
    fun convertsSecondsToStoredUsesWithBigDecimal() {
        assertEquals(3.0, secondsToUses(30.0, 10.0), 0.0)
        assertEquals(1.0, secondsToUses(10.0, 10.0), 0.0)
        assertEquals(2.0, secondsToUses(15.0, 7.5), 0.0)
        assertEquals(2.933333, secondsToUses(22.0, 7.5), 0.0)
    }
}
