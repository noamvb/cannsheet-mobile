package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ConsumptionDateTimeTest {
    @Test
    fun nowUsesTheDeviceLocalDateAndTime() {
        withDefaultTimeZone("America/New_York") {
            val local = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.JULY, 10, 21, 7, 0)
            }

            assertEquals(
                SubmissionDateTime("2026-07-10", "21:07"),
                currentSubmissionDateTime(local.timeInMillis),
            )
        }
    }

    @Test
    fun pickerDateStaysOnTheLocalDayEvenAfterUtcRollover() {
        withDefaultTimeZone("America/New_York") {
            val localEvening = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.JULY, 10, 23, 30, 0)
            }

            val pickerMillis = currentLocalDateAsPickerMillis(localEvening.timeInMillis)
            assertEquals("2026-07-10", pickerDateToWire(pickerMillis))
        }
    }

    @Test
    fun timeWireFormatIsZeroPadded() {
        assertEquals("04:09", timeToWire(4, 9))
    }

    @Test
    fun parsePickerDateToMillisParsesUtcDateAndRoundTripsAcrossTimeZones() {
        listOf("America/New_York", "Asia/Tokyo", "UTC", "America/Los_Angeles").forEach { timeZoneId ->
            withDefaultTimeZone(timeZoneId) {
                val millis = parsePickerDateToMillis("2026-07-27")
                org.junit.Assert.assertNotNull(millis)
                assertEquals("2026-07-27", pickerDateToWire(millis!!))
            }
        }
    }

    @Test
    fun parsePickerDateToMillisReturnsNullForInvalidDate() {
        org.junit.Assert.assertNull(parsePickerDateToMillis("invalid-date"))
        org.junit.Assert.assertNull(parsePickerDateToMillis(""))
    }

    private inline fun withDefaultTimeZone(id: String, block: () -> Unit) {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}

