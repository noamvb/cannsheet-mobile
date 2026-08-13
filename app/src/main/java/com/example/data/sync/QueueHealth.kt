package com.example.data.sync

import com.example.data.BackgroundSyncResult

/** How long a continuously non-empty queue may sit before it becomes actionable. */
const val QUEUE_STUCK_THRESHOLD_MILLIS: Long = 24L * 60L * 60L * 1000L

/** How long an active reason stays ineligible after it has been claimed for presentation. */
const val QUEUE_ALERT_REPEAT_MILLIS: Long = 24L * 60L * 60L * 1000L

/** The complete set of queue conditions Cannsheet may interrupt the user about. */
enum class QueueAlertReason {
    ENVIRONMENT_MISMATCH,
    PARTIAL_REJECTIONS,
    BACKEND_CAPABILITY_PENDING,
    STUCK_QUEUE,
}

data class QueueHealthSnapshot(
    val pendingActionCount: Int,
    val queueNonEmptySinceEpochMillis: Long?,
    val lastResult: BackgroundSyncResult?,
    val lastMeaningfulSyncAtEpochMillis: Long?,
    val backgroundSyncEnabled: Boolean,
    val alertsEnabled: Boolean,
    val lastAlertReason: QueueAlertReason?,
    val lastAlertAtEpochMillis: Long?,
)

data class QueueAlert(
    val reason: QueueAlertReason,
    val pendingActionCount: Int,
    val queueAgeMillis: Long,
)

/**
 * Repository-level result of atomically evaluating and, when eligible, claiming an alert.
 * [claimId] is an exact ownership token: delivery code must complete it after a successful post or
 * release it after a failed post so a failure does not consume the repeat window.
 */
data class QueueAlertClaim(
    val activeAlert: QueueAlert?,
    val claimId: String?,
) {
    init {
        require(claimId == null || activeAlert != null) {
            "A queue alert claim cannot exist without an active condition"
        }
    }

    val shouldPresent: Boolean
        get() = claimId != null
}

/**
 * Separates an active condition from permission to present it again. This distinction lets a
 * caller keep an existing notification visible while repeat suppression is active, while a null
 * [activeAlert] is a genuine resolution signal.
 */
data class QueueHealthEvaluation(
    val activeAlert: QueueAlert?,
    val shouldPresent: Boolean,
) {
    init {
        require(!shouldPresent || activeAlert != null) {
            "A queue alert cannot be presented without an active condition"
        }
    }

    companion object {
        val INACTIVE = QueueHealthEvaluation(activeAlert = null, shouldPresent = false)
    }
}

/**
 * Pure queue-health decision function. The clock is supplied so every edge is deterministic and
 * testable without WorkManager, DataStore, or a device.
 *
 * RETRY_EXHAUSTED deliberately does not trigger by itself. Five WorkManager attempts can be
 * exhausted during an ordinary transient outage; the same queue becomes actionable only after it
 * reaches [QUEUE_STUCK_THRESHOLD_MILLIS].
 */
fun evaluateQueueHealth(
    snapshot: QueueHealthSnapshot,
    nowEpochMillis: Long,
): QueueHealthEvaluation {
    if (!snapshot.alertsEnabled) return QueueHealthEvaluation.INACTIVE
    if (!snapshot.backgroundSyncEnabled) return QueueHealthEvaluation.INACTIVE
    if (snapshot.pendingActionCount <= 0) return QueueHealthEvaluation.INACTIVE

    val since = snapshot.queueNonEmptySinceEpochMillis ?: return QueueHealthEvaluation.INACTIVE
    if (nowEpochMillis < 0L || since < 0L) return QueueHealthEvaluation.INACTIVE
    if (nowEpochMillis < since) return QueueHealthEvaluation.INACTIVE
    val queueAgeMillis = nowEpochMillis - since

    // A persisted terminal result is actionable only when it belongs to this queue episode.
    // Settings intentionally retains older results as history, so using an unscoped result here
    // would alert about a previous queue after a drain and later refill.
    val resultAt = snapshot.lastMeaningfulSyncAtEpochMillis
    val currentEpisodeResult = snapshot.lastResult.takeIf {
        resultAt != null && resultAt >= since && resultAt <= nowEpochMillis
    }

    val reason = when (currentEpisodeResult) {
        BackgroundSyncResult.ENVIRONMENT_MISMATCH -> QueueAlertReason.ENVIRONMENT_MISMATCH
        BackgroundSyncResult.PARTIAL_REJECTIONS -> QueueAlertReason.PARTIAL_REJECTIONS
        BackgroundSyncResult.BACKEND_CAPABILITY_PENDING ->
            QueueAlertReason.BACKEND_CAPABILITY_PENDING

        else -> if (queueAgeMillis >= QUEUE_STUCK_THRESHOLD_MILLIS) {
            QueueAlertReason.STUCK_QUEUE
        } else {
            return QueueHealthEvaluation.INACTIVE
        }
    }

    val alert = QueueAlert(
        reason = reason,
        pendingActionCount = snapshot.pendingActionCount,
        queueAgeMillis = queueAgeMillis,
    )
    val lastAlertAt = snapshot.lastAlertAtEpochMillis
    val repeatSuppressed = snapshot.lastAlertReason == reason &&
        lastAlertAt != null &&
        lastAlertAt >= 0L &&
        nowEpochMillis >= lastAlertAt &&
        nowEpochMillis - lastAlertAt < QUEUE_ALERT_REPEAT_MILLIS

    return QueueHealthEvaluation(
        activeAlert = alert,
        shouldPresent = !repeatSuppressed,
    )
}
