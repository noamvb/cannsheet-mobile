package com.example.data.sync

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueAlertSchedulerTest {
    @Test
    fun alertOnlyRequestHasNoNetworkConstraintAndUsesTheRemainingDelay() {
        val delay = TimeUnit.HOURS.toMillis(3)
        val request = QueueAlertScheduler.request(delay)

        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(delay, request.workSpec.initialDelay)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(10), request.workSpec.backoffDelayDuration)
    }

    @Test
    fun computesRemainingThresholdAndRunsImmediatelyOnceActionable() {
        assertEquals(
            TimeUnit.HOURS.toMillis(18),
            queueAlertCheckDelayMillis(
                backgroundSyncEnabled = true,
                queueAlertsEnabled = true,
                queueNonEmptySinceEpochMillis = NOW - TimeUnit.HOURS.toMillis(6),
                nowEpochMillis = NOW,
            ),
        )
        assertEquals(
            0L,
            queueAlertCheckDelayMillis(
                backgroundSyncEnabled = true,
                queueAlertsEnabled = true,
                queueNonEmptySinceEpochMillis = NOW - TimeUnit.HOURS.toMillis(25),
                nowEpochMillis = NOW,
            ),
        )
    }

    @Test
    fun killSwitchesEmptyQueueAndUnsafeClockDoNotScheduleAThresholdCheck() {
        assertNull(queueAlertCheckDelayMillis(false, true, NOW, NOW))
        assertNull(queueAlertCheckDelayMillis(true, false, NOW, NOW))
        assertNull(queueAlertCheckDelayMillis(true, true, null, NOW))
        assertNull(queueAlertCheckDelayMillis(true, true, NOW + 1L, NOW))
        assertNull(queueAlertCheckDelayMillis(true, true, -1L, NOW))
    }

    @Test
    fun completedAlertSchedulesTheNextRepeatWindowInsteadOfLoopingImmediately() {
        val preferences = com.example.data.SyncPreferences(
            enabled = true,
            queueAlertsEnabled = true,
            queueNonEmptySinceEpochMillis = NOW - TimeUnit.HOURS.toMillis(25),
            lastQueueAlertReason = QueueAlertReason.STUCK_QUEUE,
            lastQueueAlertAtEpochMillis = NOW - TimeUnit.HOURS.toMillis(1),
        )
        assertEquals(
            TimeUnit.HOURS.toMillis(23),
            queueAlertNextCheckDelayMillis(preferences, NOW),
        )
        assertEquals(
            QUEUE_ALERT_REPEAT_MILLIS,
            queueAlertNextCheckDelayMillis(
                preferences.copy(
                    queueNonEmptySinceEpochMillis = NOW - TimeUnit.HOURS.toMillis(49),
                    lastQueueAlertAtEpochMillis = NOW - TimeUnit.HOURS.toMillis(24),
                ),
                NOW,
            ),
        )
    }

    @Test
    fun unpresentedDueAlertGetsABoundedRecheckInsteadOfAZeroDelayLoop() {
        val preferences = com.example.data.SyncPreferences(
            enabled = true,
            queueAlertsEnabled = true,
            queueNonEmptySinceEpochMillis = NOW - TimeUnit.HOURS.toMillis(25),
        )

        assertEquals(
            QUEUE_ALERT_REPEAT_MILLIS,
            queueAlertNextCheckDelayMillis(preferences, NOW),
        )
    }

    @Test
    fun reconcileCancelsOrReplacesExactlyOneUniqueThresholdCheck() {
        val gateway = RecordingWorkGateway()

        QueueAlertScheduler.reconcile(
            workGateway = gateway,
            backgroundSyncEnabled = true,
            queueAlertsEnabled = true,
            queueNonEmptySinceEpochMillis = null,
            nowEpochMillis = NOW,
        )
        assertEquals(listOf(QueueAlertScheduler.QUEUE_ALERT_CHECK_WORK_NAME), gateway.cancelled)

        QueueAlertScheduler.reconcile(
            workGateway = gateway,
            backgroundSyncEnabled = true,
            queueAlertsEnabled = true,
            queueNonEmptySinceEpochMillis = NOW - TimeUnit.HOURS.toMillis(6),
            nowEpochMillis = NOW,
        )
        QueueAlertScheduler.reconcile(
            workGateway = gateway,
            backgroundSyncEnabled = true,
            queueAlertsEnabled = true,
            queueNonEmptySinceEpochMillis = NOW - TimeUnit.HOURS.toMillis(7),
            nowEpochMillis = NOW,
        )

        assertEquals(2, gateway.enqueued.size)
        assertEquals(
            listOf(
                TimeUnit.HOURS.toMillis(18),
                TimeUnit.HOURS.toMillis(17),
            ),
            gateway.enqueued.map { it.request.workSpec.initialDelay },
        )
        assertTrue(gateway.enqueued.all {
            it.name == QueueAlertScheduler.QUEUE_ALERT_CHECK_WORK_NAME &&
                it.policy == ExistingWorkPolicy.REPLACE
        })
    }

    private class RecordingWorkGateway : QueueAlertWorkGateway {
        data class Enqueued(
            val name: String,
            val policy: ExistingWorkPolicy,
            val request: OneTimeWorkRequest,
        )

        val cancelled = mutableListOf<String>()
        val enqueued = mutableListOf<Enqueued>()

        override fun cancelUniqueWork(name: String) {
            cancelled += name
        }

        override fun enqueueUniqueWork(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            enqueued += Enqueued(name, policy, request)
        }
    }

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
