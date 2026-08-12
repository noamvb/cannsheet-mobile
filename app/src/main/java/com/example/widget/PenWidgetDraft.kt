package com.example.widget

import com.squareup.moshi.JsonClass

const val STEP_SECONDS = 10
const val MAX_SECONDS = 600
const val UNDO_WINDOW_MILLIS = 5_000L
const val QUEUED_SUBTITLE_WINDOW_MILLIS = 8_000L
const val PEN_WIDGET_PAYLOAD_VERSION = 1

sealed interface PenWidgetDraft {
    data class Composing(val seconds: Int) : PenWidgetDraft

    data class AwaitingCommit(val payload: PenWidgetCommitPayload) : PenWidgetDraft
}

@JsonClass(generateAdapter = true)
data class PenWidgetCommitPayload(
    val version: Int,
    val commitId: String,
    val commitAtEpochMillis: Long,
    val productId: String,
    val productUuid: String?,
    val seconds: Int,
    val secondsPerUse: Double,
    val uses: Double,
    val date: String,
    val time: String,
)

fun stepSeconds(current: Int, delta: Int): Int =
    (current.toLong() + delta.toLong())
        .coerceIn(0L, MAX_SECONDS.toLong())
        .toInt()
