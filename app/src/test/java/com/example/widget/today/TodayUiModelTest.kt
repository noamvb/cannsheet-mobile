package com.example.widget.today

import com.example.data.ConsumptionHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayUiModelTest {
    @Test
    fun todayTotalSumsOnlyTodaysEntries() {
        val model = buildTodayUiModel(
            entries = listOf(
                entry("today-one", "2026-08-20", 1.5),
                entry("today-two", "2026-08-20", 2.0),
                entry("yesterday", "2026-08-19", 100.0),
                entry("tomorrow", "2026-08-21", 200.0),
            ),
            todayDate = "2026-08-20",
            secondsPerUse = null,
        )

        assertEquals(3.5, model.todayUses, 0.0)
        assertEquals("3.5", model.todayDisplay)
        assertTrue(model.hasTodayEntries)
    }

    @Test
    fun baselineIgnoresDaysWithNoEntries() {
        val model = buildTodayUiModel(
            entries = listOf(
                entry("today", "2026-08-20", 5.0),
                entry("day-one", "2026-08-19", 2.0),
                entry("day-three", "2026-08-17", 4.0),
                entry("day-six", "2026-08-14", 6.0),
                entry("too-old", "2026-08-12", 100.0),
                entry("future", "2026-08-21", 200.0),
            ),
            todayDate = "2026-08-20",
            secondsPerUse = null,
        )

        assertEquals(4.0, model.baselineUses!!, 0.0)
        assertEquals("4", model.baselineDisplay)
        assertEquals(TodayComparison.ABOVE, model.comparison)
    }

    @Test
    fun baselineIsNullBelowThreeObservedDays() {
        val model = buildTodayUiModel(
            entries = listOf(
                entry("today", "2026-08-20", 3.0),
                entry("day-one", "2026-08-19", 2.0),
                entry("day-four", "2026-08-16", 4.0),
            ),
            todayDate = "2026-08-20",
            secondsPerUse = null,
        )

        assertNull(model.baselineUses)
        assertNull(model.baselineDisplay)
        assertEquals(TodayComparison.UNAVAILABLE, model.comparison)
    }

    @Test
    fun streakCountsConsecutiveDaysEndingToday() {
        val model = buildTodayUiModel(
            entries = listOf(
                entry("today", "2026-08-20", 1.0),
                entry("yesterday", "2026-08-19", 1.0),
                entry("two-days-ago", "2026-08-18", 1.0),
                entry("before-gap", "2026-08-16", 1.0),
            ),
            todayDate = "2026-08-20",
            secondsPerUse = null,
        )

        assertEquals(3, model.streakDays)
    }

    @Test
    fun streakSurvivesADayWithMultipleEntries() {
        val model = buildTodayUiModel(
            entries = listOf(
                entry("today", "2026-08-20", 1.0),
                entry("yesterday-one", "2026-08-19", 1.0),
                entry("yesterday-two", "2026-08-19", 2.0),
                entry("two-days-ago", "2026-08-18", 1.0),
            ),
            todayDate = "2026-08-20",
            secondsPerUse = null,
        )

        assertEquals(3, model.streakDays)
    }

    @Test
    fun streakBreaksOnAMissingDay() {
        val model = buildTodayUiModel(
            entries = listOf(
                entry("yesterday", "2026-08-19", 1.0),
                entry("two-days-ago", "2026-08-18", 1.0),
                entry("before-missing-day", "2026-08-16", 1.0),
                entry("future", "2026-08-21", 1.0),
            ),
            todayDate = "2026-08-20",
            secondsPerUse = null,
        )

        assertFalse(model.hasTodayEntries)
        assertEquals(2, model.streakDays)
    }

    @Test
    fun todayTotalIsZeroWhenTheDateAdvancesPastEveryEntry() {
        val model = buildTodayUiModel(
            entries = listOf(
                entry("yesterday-one", "2026-08-19", 1.5),
                entry("yesterday-two", "2026-08-19", 2.0),
                entry("two-days-ago", "2026-08-18", 3.0),
            ),
            todayDate = "2026-08-20",
            secondsPerUse = null,
        )

        assertEquals(0.0, model.todayUses, 0.0)
        assertEquals("No logs yet today", model.todayDisplay)
        assertFalse(model.hasTodayEntries)
    }

    @Test
    fun streakEndsYesterdayWhenTodayHasNoEntries() {
        val model = buildTodayUiModel(
            entries = listOf(
                entry("yesterday", "2026-08-19", 1.0),
                entry("two-days-ago", "2026-08-18", 1.0),
                entry("three-days-ago", "2026-08-17", 1.0),
            ),
            todayDate = "2026-08-20",
            secondsPerUse = null,
        )

        assertFalse(model.hasTodayEntries)
        assertEquals(3, model.streakDays)
    }

    @Test
    fun emptyHistoryProducesTheEmptyState() {
        val model = buildTodayUiModel(
            entries = emptyList(),
            todayDate = "2026-08-20",
            secondsPerUse = 10.0,
        )

        assertEquals(0.0, model.todayUses, 0.0)
        assertEquals("No logs yet today", model.todayDisplay)
        assertNull(model.baselineUses)
        assertNull(model.baselineDisplay)
        assertEquals(TodayComparison.UNAVAILABLE, model.comparison)
        assertEquals(0, model.streakDays)
        assertFalse(model.hasTodayEntries)
    }

    private fun entry(eventId: String, date: String, uses: Double) = ConsumptionHistoryEntry(
        eventId = eventId,
        date = date,
        time = "12:00",
        productId = "product",
        productUuid = null,
        uses = uses,
        isFinished = false,
        loggedAtEpochMillis = 0L,
    )
}
