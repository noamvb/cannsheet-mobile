package com.example.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class PenWidgetDraftTest {
    @Test
    fun stepsByTenAndClampsToZero() {
        assertEquals(20, stepSeconds(10, STEP_SECONDS))
        assertEquals(0, stepSeconds(0, -STEP_SECONDS))
        assertEquals(0, stepSeconds(5, -STEP_SECONDS))
    }

    @Test
    fun clampsAtMaximumAndDoesNotOverflow() {
        assertEquals(MAX_SECONDS, stepSeconds(MAX_SECONDS, STEP_SECONDS))
        assertEquals(MAX_SECONDS, stepSeconds(Int.MAX_VALUE, STEP_SECONDS))
        assertEquals(0, stepSeconds(Int.MIN_VALUE, -STEP_SECONDS))
    }
}
