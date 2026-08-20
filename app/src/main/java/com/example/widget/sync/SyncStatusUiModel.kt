package com.example.widget.sync

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
    lastSyncLabel = PenWidgetText.Literal(
        formatLastSyncLabel(
            lastMeaningfulSyncAtEpochMillis = lastMeaningfulSyncAtEpochMillis,
            nowMillis = nowMillis,
        ),
    ),
    stuck = queueNonEmptySinceEpochMillis?.let { since ->
        nowMillis >= since && nowMillis - since >= QUEUE_STUCK_THRESHOLD_MILLIS
    } == true,
)

private fun formatLastSyncLabel(
    lastMeaningfulSyncAtEpochMillis: Long?,
    nowMillis: Long,
): String {
    val lastSync = lastMeaningfulSyncAtEpochMillis ?: return "Never synced"
    val ageMillis = (nowMillis - lastSync).coerceAtLeast(0L)
    val ageMinutes = ageMillis / MINUTE_MILLIS
    return when {
        ageMinutes < 1L -> "Synced just now"
        ageMinutes < MINUTES_PER_HOUR -> "Synced ${ageMinutes}m ago"
        ageMinutes < MINUTES_PER_DAY -> "Synced ${ageMinutes / MINUTES_PER_HOUR}h ago"
        ageMinutes < MINUTES_PER_DAY * 2L -> "Synced yesterday"
        else -> "Synced ${ageMinutes / MINUTES_PER_DAY}d ago"
    }
}

private const val MINUTE_MILLIS = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR
