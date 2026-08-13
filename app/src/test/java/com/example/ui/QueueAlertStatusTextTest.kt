package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueAlertStatusTextTest {
    @Test
    fun syncedStatusWinsForZeroOrNegativeCounts() {
        assertEquals("Everything is synced.", queueAlertStatusText(0, NOW, NOW))
        assertEquals("Everything is synced.", queueAlertStatusText(-1, null, NOW))
    }

    @Test
    fun unsafeWatermarksDoNotRenderANegativeDuration() {
        assertEquals("2 waiting to sync.", queueAlertStatusText(2, null, NOW))
        assertEquals("2 waiting to sync.", queueAlertStatusText(2, -1L, NOW))
        assertEquals("2 waiting to sync.", queueAlertStatusText(2, NOW + 1L, NOW))
    }

    @Test
    fun formatsMinuteHourAndDayBoundaries() {
        assertEquals(
            "2 waiting to sync for less than a minute.",
            queueAlertStatusText(2, NOW - 59_999L, NOW),
        )
        assertEquals(
            "1 waiting to sync for 1 minute.",
            queueAlertStatusText(1, NOW - 60_000L, NOW),
        )
        assertEquals(
            "2 waiting to sync for 2 minutes.",
            queueAlertStatusText(2, NOW - 120_000L, NOW),
        )
        assertEquals(
            "2 waiting to sync for 1 hour.",
            queueAlertStatusText(2, NOW - 3_600_000L, NOW),
        )
        assertEquals(
            "2 waiting to sync for 2 hours.",
            queueAlertStatusText(2, NOW - 7_200_000L, NOW),
        )
        assertEquals(
            "2 waiting to sync for 1 day.",
            queueAlertStatusText(2, NOW - 86_400_000L, NOW),
        )
        assertEquals(
            "2 waiting to sync for 3 days.",
            queueAlertStatusText(2, NOW - 259_200_000L, NOW),
        )
    }

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
