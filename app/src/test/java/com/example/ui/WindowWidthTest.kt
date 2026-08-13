package com.example.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowWidthTest {
    @Test
    fun compactEndsImmediatelyBefore600Dp() {
        assertEquals(WindowWidth.COMPACT, windowWidthFor(0.dp))
        assertEquals(WindowWidth.COMPACT, windowWidthFor(599.dp))
    }

    @Test
    fun mediumRunsFrom600Until840Dp() {
        assertEquals(WindowWidth.MEDIUM, windowWidthFor(600.dp))
        assertEquals(WindowWidth.MEDIUM, windowWidthFor(839.dp))
    }

    @Test
    fun expandedStartsAt840DpAndHasNoUpperBound() {
        assertEquals(WindowWidth.EXPANDED, windowWidthFor(840.dp))
        assertEquals(WindowWidth.EXPANDED, windowWidthFor(100_000.dp))
    }
}
