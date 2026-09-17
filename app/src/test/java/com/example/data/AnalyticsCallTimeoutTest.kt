package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsCallTimeoutTest {

    @Test
    fun analyticsCallTimeoutIsFortyFiveSeconds() {
        assertEquals(45L, ANALYTICS_CALL_TIMEOUT_SECONDS)
    }
}
