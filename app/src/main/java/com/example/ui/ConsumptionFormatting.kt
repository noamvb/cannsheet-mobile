package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun formatHeaderDate(nowEpochMillis: Long): String =
    SimpleDateFormat("EEEE · d MMM", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }.format(Date(nowEpochMillis)).uppercase()

internal fun formatTimeAmPm(hour: Int, minute: Int): String {
    val h = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
    val amPm = if (hour < 12) "AM" else "PM"
    return String.format(Locale.US, "%d:%02d %s", h, minute, amPm)
}

internal fun formatQuantity(quantity: Double): String =
    BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString()
