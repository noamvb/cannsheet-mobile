package com.example.widget

import com.squareup.moshi.JsonClass

const val STEP_SECONDS = 10
const val MAX_SECONDS = 600
const val UNDO_WINDOW_MILLIS = 5_000L
const val COMMIT_GRACE_MILLIS = 1_500L
const val COMMIT_DELAY_MILLIS = UNDO_WINDOW_MILLIS + COMMIT_GRACE_MILLIS
const val MAX_PENDING_AGE_MILLIS = 10 * 60 * 1_000L
const val CLOCK_ROLLBACK_TOLERANCE_MILLIS = 10_000L
const val CLAIM_STALE_MILLIS = 60_000L
const val QUEUED_SUBTITLE_WINDOW_MILLIS = 8_000L
const val PEN_WIDGET_PAYLOAD_VERSION = 3

enum class DeferredPenInputKind {
    DURATION_SECONDS,
    DIRECT_USES,
}

sealed interface PenWidgetDraft {
    data class Composing(val seconds: Int) : PenWidgetDraft

    data class AwaitingCommit(val payload: PenWidgetCommitPayload) : PenWidgetDraft
}

@JsonClass(generateAdapter = true)
data class PenWidgetCommitPayload(
    val version: Int,
    val commitId: String,
    val eventId: String,
    val submittedAtEpochMillis: Long,
    val commitAtEpochMillis: Long,
    val claimId: String? = null,
    val claimedAtEpochMillis: Long? = null,
    val productId: String,
    val productUuid: String?,
    val inputKind: DeferredPenInputKind,
    val seconds: Int?,
    val secondsPerUse: Double?,
    val restoreDraftSeconds: Int?,
    val uses: Double,
    val date: String,
    val time: String,
)

fun stepSeconds(current: Int, delta: Int): Int =
    (current.toLong() + delta.toLong())
        .coerceIn(0L, MAX_SECONDS.toLong())
        .toInt()
