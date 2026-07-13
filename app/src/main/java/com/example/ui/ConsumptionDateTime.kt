package com.example.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class SubmissionDateTime(val date: String, val time: String)

fun currentSubmissionDateTime(nowEpochMillis: Long = System.currentTimeMillis()): SubmissionDateTime {
    val instant = Date(nowEpochMillis)
    return SubmissionDateTime(
        date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(instant),
        time = SimpleDateFormat("HH:mm", Locale.US).format(instant),
    )
}

fun pickerDateToWire(selectedDateMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(selectedDateMillis))

fun currentLocalDateAsPickerMillis(nowEpochMillis: Long = System.currentTimeMillis()): Long {
    val localDate = Calendar.getInstance().apply { timeInMillis = nowEpochMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            localDate.get(Calendar.YEAR),
            localDate.get(Calendar.MONTH),
            localDate.get(Calendar.DAY_OF_MONTH),
        )
    }.timeInMillis
}

fun timeToWire(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour, minute)
