package com.example.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.SyncPreferences
import java.util.concurrent.TimeUnit

/** Owns the durable no-network alarm that evaluates a queue when it crosses the 24-hour mark. */
object QueueAlertScheduler {
    const val QUEUE_ALERT_CHECK_WORK_NAME = "cannsheet-queue-alert-check"

    fun reconcile(
        context: Context,
        backgroundSyncEnabled: Boolean,
        queueAlertsEnabled: Boolean,
        queueNonEmptySinceEpochMillis: Long?,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        reconcile(
            workGateway = AndroidQueueAlertWorkGateway(
                WorkManager.getInstance(context.applicationContext),
            ),
            backgroundSyncEnabled = backgroundSyncEnabled,
            queueAlertsEnabled = queueAlertsEnabled,
            queueNonEmptySinceEpochMillis = queueNonEmptySinceEpochMillis,
            nowEpochMillis = nowEpochMillis,
        )
    }

    internal fun reconcile(
        workGateway: QueueAlertWorkGateway,
        backgroundSyncEnabled: Boolean,
        queueAlertsEnabled: Boolean,
        queueNonEmptySinceEpochMillis: Long?,
        nowEpochMillis: Long,
    ) {
        val initialDelayMillis = queueAlertCheckDelayMillis(
            backgroundSyncEnabled = backgroundSyncEnabled,
            queueAlertsEnabled = queueAlertsEnabled,
            queueNonEmptySinceEpochMillis = queueNonEmptySinceEpochMillis,
            nowEpochMillis = nowEpochMillis,
        )
        if (initialDelayMillis == null) {
            workGateway.cancelUniqueWork(QUEUE_ALERT_CHECK_WORK_NAME)
        } else {
            workGateway.enqueueUniqueWork(
                QUEUE_ALERT_CHECK_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request(initialDelayMillis),
            )
        }
    }

    /** Appends the next eligible check behind the currently running alert-only worker. */
    fun scheduleAfterCheck(
        context: Context,
        preferences: SyncPreferences,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val delay = queueAlertNextCheckDelayMillis(preferences, nowEpochMillis) ?: return
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            QUEUE_ALERT_CHECK_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(delay),
        )
    }

    internal fun request(initialDelayMillis: Long) =
        OneTimeWorkRequestBuilder<QueueAlertCheckWorker>()
            .setInitialDelay(initialDelayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

    private const val MIN_BACKOFF_MILLIS = 10_000L
}

internal interface QueueAlertWorkGateway {
    fun cancelUniqueWork(name: String)

    fun enqueueUniqueWork(
        name: String,
        policy: ExistingWorkPolicy,
        request: androidx.work.OneTimeWorkRequest,
    )
}

private class AndroidQueueAlertWorkGateway(
    private val workManager: WorkManager,
) : QueueAlertWorkGateway {
    override fun cancelUniqueWork(name: String) {
        workManager.cancelUniqueWork(name)
    }

    override fun enqueueUniqueWork(
        name: String,
        policy: ExistingWorkPolicy,
        request: androidx.work.OneTimeWorkRequest,
    ) {
        workManager.enqueueUniqueWork(name, policy, request)
    }
}

/** Null means no alert-only work should exist; zero means the queue is already actionable. */
internal fun queueAlertCheckDelayMillis(
    backgroundSyncEnabled: Boolean,
    queueAlertsEnabled: Boolean,
    queueNonEmptySinceEpochMillis: Long?,
    nowEpochMillis: Long,
): Long? {
    if (!backgroundSyncEnabled || !queueAlertsEnabled) return null
    val since = queueNonEmptySinceEpochMillis ?: return null
    if (since < 0L || nowEpochMillis < 0L || nowEpochMillis < since) return null
    val elapsed = nowEpochMillis - since
    return (QUEUE_STUCK_THRESHOLD_MILLIS - elapsed).coerceAtLeast(0L)
}

internal fun queueAlertNextCheckDelayMillis(
    preferences: SyncPreferences,
    nowEpochMillis: Long,
): Long? {
    val initial = queueAlertCheckDelayMillis(
        backgroundSyncEnabled = preferences.enabled,
        queueAlertsEnabled = preferences.queueAlertsEnabled,
        queueNonEmptySinceEpochMillis = preferences.queueNonEmptySinceEpochMillis,
        nowEpochMillis = nowEpochMillis,
    ) ?: return null
    val since = requireNotNull(preferences.queueNonEmptySinceEpochMillis)
    val lastAlertAt = preferences.lastQueueAlertAtEpochMillis
    return if (
        preferences.lastQueueAlertReason != null &&
        lastAlertAt != null &&
        lastAlertAt >= since &&
        lastAlertAt <= nowEpochMillis
    ) {
        val remainingRepeat =
            (lastAlertAt + QUEUE_ALERT_REPEAT_MILLIS - nowEpochMillis).coerceAtLeast(0L)
        // If the due repeat was not posted, the persisted timestamp is unchanged. Bound the
        // next check instead of appending zero-delay successors. A successful repeat refreshes
        // lastAlertAt before this function runs and therefore retains its exact deadline.
        if (remainingRepeat == 0L) QUEUE_ALERT_REPEAT_MILLIS else remainingRepeat
    } else {
        // A due alert may remain unrecorded because permission/channel state changed or Android
        // rejected the post. Never append a zero-delay worker in that state: it would create an
        // unbounded alert-only chain. The initial scheduler still evaluates a newly due queue
        // immediately; only a completed check is bounded to the normal repeat cadence.
        if (initial == 0L) QUEUE_ALERT_REPEAT_MILLIS else initial
    }
}
