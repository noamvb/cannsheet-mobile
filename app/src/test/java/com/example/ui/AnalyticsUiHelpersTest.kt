package com.example.ui

import com.example.data.DailyActivityDto
import com.example.data.InsightsRange
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsUiHelpersTest {
    @Test
    fun shortActivityStaysDaily() {
        val days = listOf(
            DailyActivityDto("2026-07-17", 1, 1),
            DailyActivityDto("2026-07-18", 2, 1),
        )

        assertEquals(listOf("07-17" to 1, "07-18" to 2), bucketActivity(days))
    }

    @Test
    fun mediumActivityBucketsBySevenDays() {
        val dates = (1..30).map { "2026-06-${it.toString().padStart(2, '0')}" } +
            (1..5).map { "2026-07-${it.toString().padStart(2, '0')}" }
        val days = dates.map { DailyActivityDto(it, 1, 1) }

        assertEquals(5, bucketActivity(days).size)
        assertEquals(7, bucketActivity(days).first().second)
    }

    @Test
    fun streaksUseTrailingAndLongestRuns() {
        val days = listOf(1, 1, 0, 1, 1, 1).mapIndexed { index, count ->
            DailyActivityDto("2026-07-${(index + 1).toString().padStart(2, '0')}", count, count)
        }

        assertEquals(3 to 3, calculateStreaks(days))
    }

    @Test
    fun presetRangeIsInclusive() {
        assertEquals(
            InsightsRange.Custom("2026-06-19", "2026-07-18"),
            customRangeForDays(30, "2026-07-18"),
        )
    }
}
