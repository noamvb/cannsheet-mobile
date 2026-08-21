package com.example.widget.sync

import com.example.R
import com.example.data.sync.QUEUE_STUCK_THRESHOLD_MILLIS
import com.example.widget.PenWidgetText

data class SyncStatusUiModel(
    val pendingCount: Int,
    val lastSyncLabel: PenWidgetText,
    val stuck: Boolean,
)

fun buildSyncStatusUiModel(
    pendingCount: Int,
    lastMeaningfulSyncAtEpochMillis: Long?,
    queueNonEmptySinceEpochMillis: Long?,
    nowMillis: Long,
): SyncStatusUiModel = SyncStatusUiModel(
    pendingCount = pendingCount.coerceAtLeast(0),
    lastSyncLabel = formatLastSyncLabel(
        lastMeaningfulSyncAtEpochMillis = lastMeaningfulSyncAtEpochMillis,
        nowMillis = nowMillis,
    ),
    stuck = queueNonEmptySinceEpochMillis?.let { since ->
        nowMillis >= since && nowMillis - since >= QUEUE_STUCK_THRESHOLD_MILLIS
    } == true,
)

private fun formatLastSyncLabel(
    lastMeaningfulSyncAtEpochMillis: Long?,
    nowMillis: Long,
): PenWidgetText {
    val lastSync = lastMeaningfulSyncAtEpochMillis
        ?: return PenWidgetText.Resource(R.string.sync_status_last_sync_never)
    val ageMinutes = (nowMillis - lastSync).coerceAtLeast(0L) / MINUTE_MILLIS
    return when {
        ageMinutes < 1L ->
            PenWidgetText.Resource(R.string.sync_status_last_sync_just_now)
        ageMinutes < MINUTES_PER_HOUR ->
            PenWidgetText.Resource(R.string.sync_status_last_sync_minutes, listOf(ageMinutes.toInt()))
        ageMinutes < MINUTES_PER_DAY ->
            PenWidgetText.Resource(R.string.sync_status_last_sync_hours, listOf((ageMinutes / MINUTES_PER_HOUR).toInt()))
        ageMinutes < MINUTES_PER_DAY * 2L ->
            PenWidgetText.Resource(R.string.sync_status_last_sync_yesterday)
        else ->
            PenWidgetText.Resource(R.string.sync_status_last_sync_days, listOf((ageMinutes / MINUTES_PER_DAY).toInt()))
    }
}

private const val MINUTE_MILLIS = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR
