package com.example.widget.today

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayRolloverSchedulerTest {
    @Test
    fun midnightDelayIsPositiveAtEveryHourOfTheDay() {
        val utc = TimeZone.getTimeZone("UTC")
        for (hour in 0..23) {
            val nowMillis = instantAt(2026, 8, 20, hour = hour, zone = utc)

            val delayMillis = TodayRolloverScheduler.millisUntilNextMidnight(nowMillis, utc)

            assertTrue("hour $hour produced non-positive delay $delayMillis", delayMillis > 0L)
        }
    }

    @Test
    fun justBeforeMidnightSchedulesASmallDelay() {
        val utc = TimeZone.getTimeZone("UTC")
        val nowMillis = instantAt(2026, 8, 20, hour = 23, minute = 59, second = 59, millis = 999, zone = utc)

        val delayMillis = TodayRolloverScheduler.millisUntilNextMidnight(nowMillis, utc)

        assertEquals(1L, delayMillis)
    }

    @Test
    fun justAfterMidnightSchedulesNearlyAFullDay() {
        val utc = TimeZone.getTimeZone("UTC")
        val nowMillis = instantAt(2026, 8, 20, hour = 0, minute = 0, second = 0, millis = 1, zone = utc)

        val delayMillis = TodayRolloverScheduler.millisUntilNextMidnight(nowMillis, utc)

        assertEquals(ONE_DAY_MILLIS - 1L, delayMillis)
    }

    @Test
    fun delayIsComputedInTheSuppliedTimeZone() {
        val utc = TimeZone.getTimeZone("UTC")
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        // A single instant: 10:00 UTC is 19:00 in Tokyo (UTC+9, no DST), so each zone's local
        // midnight is a different distance away.
        val nowMillis = instantAt(2026, 8, 20, hour = 10, zone = utc)

        val utcDelayMillis = TodayRolloverScheduler.millisUntilNextMidnight(nowMillis, utc)
        val tokyoDelayMillis = TodayRolloverScheduler.millisUntilNextMidnight(nowMillis, tokyo)

        assertEquals(14L * 60L * 60L * 1000L, utcDelayMillis)
        assertEquals(5L * 60L * 60L * 1000L, tokyoDelayMillis)
    }

    private fun instantAt(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
        second: Int = 0,
        millis: Int = 0,
        zone: TimeZone,
    ): Long {
        val calendar = Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, millis)
        }
        return calendar.timeInMillis
    }

    private companion object {
        const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
