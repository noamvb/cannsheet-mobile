package com.example.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SubmissionDateTime(val date: String, val time: String)

fun currentSubmissionDateTime(nowEpochMillis: Long = System.currentTimeMillis()): SubmissionDateTime {
    val instant = Date(nowEpochMillis)
    return SubmissionDateTime(
        date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(instant),
        time = SimpleDateFormat("HH:mm", Locale.US).format(instant),
    )
}
