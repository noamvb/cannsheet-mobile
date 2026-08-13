package com.example.data.sync

import com.example.data.BackgroundSyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueHealthTest {
    @Test
    fun alertsDisabledSuppressesAStuckQueue() {
        assertInactive(snapshot(alertsEnabled = false), NOW)
    }

    @Test
    fun backgroundSyncDisabledSuppressesAStuckQueue() {
        assertInactive(snapshot(backgroundSyncEnabled = false), NOW)
    }

    @Test
    fun emptyQueueSuppressesATerminalResult() {
        assertInactive(
            snapshot(
                pendingActionCount = 0,
                lastResult = BackgroundSyncResult.PARTIAL_REJECTIONS,
                lastMeaningfulSyncAtEpochMillis = NOW,
            ),
            NOW,
        )
    }

    @Test
    fun missingWatermarkCannotScopeTheCurrentQueueEpisode() {
        assertInactive(
            snapshot(
                queueNonEmptySinceEpochMillis = null,
                lastResult = BackgroundSyncResult.ENVIRONMENT_MISMATCH,
                lastMeaningfulSyncAtEpochMillis = NOW,
            ),
            NOW,
        )
    }

    @Test
    fun futureWatermarkDoesNotProduceANegativeAge() {
        assertInactive(snapshot(queueNonEmptySinceEpochMillis = NOW + 1), NOW)
    }

    @Test
    fun environmentMismatchFromCurrentEpisodeIsImmediatelyActive() {
        assertActive(
            snapshot(
                lastResult = BackgroundSyncResult.ENVIRONMENT_MISMATCH,
                lastMeaningfulSyncAtEpochMillis = NOW,
            ),
            QueueAlertReason.ENVIRONMENT_MISMATCH,
        )
    }

    @Test
    fun partialRejectionsFromCurrentEpisodeAreImmediatelyActive() {
        assertActive(
            snapshot(
                lastResult = BackgroundSyncResult.PARTIAL_REJECTIONS,
                lastMeaningfulSyncAtEpochMillis = NOW,
            ),
            QueueAlertReason.PARTIAL_REJECTIONS,
        )
    }

    @Test
    fun backendCapabilityFromCurrentEpisodeIsImmediatelyActive() {
        assertActive(
            snapshot(
                lastResult = BackgroundSyncResult.BACKEND_CAPABILITY_PENDING,
                lastMeaningfulSyncAtEpochMillis = NOW,
            ),
            QueueAlertReason.BACKEND_CAPABILITY_PENDING,
        )
    }

    @Test
    fun terminalResultFromAnOlderQueueEpisodeIsIgnored() {
        val evaluation = evaluateQueueHealth(
            snapshot(
                queueNonEmptySinceEpochMillis = NOW - 1_000,
                lastResult = BackgroundSyncResult.ENVIRONMENT_MISMATCH,
                lastMeaningfulSyncAtEpochMillis = NOW - 1_001,
            ),
            NOW,
        )

        assertInactive(evaluation)
    }

    @Test
    fun terminalResultFromTheFutureIsIgnored() {
        assertInactive(
            snapshot(
                queueNonEmptySinceEpochMillis = NOW - 1_000,
                lastResult = BackgroundSyncResult.ENVIRONMENT_MISMATCH,
                lastMeaningfulSyncAtEpochMillis = NOW + 1,
            ),
            NOW,
        )
    }

    @Test
    fun exactlyAtStuckThresholdIsActive() {
        assertActive(snapshot(), QueueAlertReason.STUCK_QUEUE)
    }

    @Test
    fun oneMillisecondUnderStuckThresholdIsInactive() {
        assertInactive(
            snapshot(queueNonEmptySinceEpochMillis = NOW - QUEUE_STUCK_THRESHOLD_MILLIS + 1),
            NOW,
        )
    }

    @Test
    fun retryExhaustedAloneUnderThresholdIsInactive() {
        assertInactive(
            snapshot(
                queueNonEmptySinceEpochMillis = NOW - 1,
                lastResult = BackgroundSyncResult.RETRY_EXHAUSTED,
                lastMeaningfulSyncAtEpochMillis = NOW,
            ),
            NOW,
        )
    }

    @Test
    fun retryExhaustedOverThresholdFallsThroughToStuckQueue() {
        assertActive(
            snapshot(
                lastResult = BackgroundSyncResult.RETRY_EXHAUSTED,
                lastMeaningfulSyncAtEpochMillis = NOW,
            ),
            QueueAlertReason.STUCK_QUEUE,
        )
    }

    @Test
    fun successOverThresholdFallsThroughToStuckQueue() {
        assertActive(
            snapshot(
                lastResult = BackgroundSyncResult.SUCCESS,
                lastMeaningfulSyncAtEpochMillis = NOW,
            ),
            QueueAlertReason.STUCK_QUEUE,
        )
    }

    @Test
    fun sameReasonInsideRepeatWindowRemainsActiveButIsSuppressed() {
        val evaluation = evaluateQueueHealth(
            snapshot(
                lastAlertReason = QueueAlertReason.STUCK_QUEUE,
                lastAlertAtEpochMillis = NOW - QUEUE_ALERT_REPEAT_MILLIS + 1,
            ),
            NOW,
        )

        assertEquals(QueueAlertReason.STUCK_QUEUE, evaluation.activeAlert?.reason)
        assertFalse(evaluation.shouldPresent)
    }

    @Test
    fun sameReasonExactlyAtRepeatWindowCanPresentAgain() {
        val evaluation = evaluateQueueHealth(
            snapshot(
                lastAlertReason = QueueAlertReason.STUCK_QUEUE,
                lastAlertAtEpochMillis = NOW - QUEUE_ALERT_REPEAT_MILLIS,
            ),
            NOW,
        )

        assertEquals(QueueAlertReason.STUCK_QUEUE, evaluation.activeAlert?.reason)
        assertTrue(evaluation.shouldPresent)
    }

    @Test
    fun differentReasonInsideRepeatWindowCanEscalateImmediately() {
        val evaluation = evaluateQueueHealth(
            snapshot(
                lastResult = BackgroundSyncResult.ENVIRONMENT_MISMATCH,
                lastMeaningfulSyncAtEpochMillis = NOW,
                lastAlertReason = QueueAlertReason.STUCK_QUEUE,
                lastAlertAtEpochMillis = NOW - 1,
            ),
            NOW,
        )

        assertEquals(QueueAlertReason.ENVIRONMENT_MISMATCH, evaluation.activeAlert?.reason)
        assertTrue(evaluation.shouldPresent)
    }

    @Test
    fun futureLastAlertDoesNotSuppressAfterABackwardsClock() {
        val evaluation = evaluateQueueHealth(
            snapshot(
                lastAlertReason = QueueAlertReason.STUCK_QUEUE,
                lastAlertAtEpochMillis = NOW + 1,
            ),
            NOW,
        )

        assertEquals(QueueAlertReason.STUCK_QUEUE, evaluation.activeAlert?.reason)
        assertTrue(evaluation.shouldPresent)
    }

    @Test
    fun activeAlertCarriesAggregatePendingCountAndExactAge() {
        val evaluation = evaluateQueueHealth(snapshot(pendingActionCount = 7), NOW)

        assertEquals(7, evaluation.activeAlert?.pendingActionCount)
        assertEquals(
            QUEUE_STUCK_THRESHOLD_MILLIS,
            evaluation.activeAlert?.queueAgeMillis,
        )
    }

    @Test
    fun immediateTerminalAlertAtEpisodeStartCarriesZeroAge() {
        val evaluation = evaluateQueueHealth(
            snapshot(
                queueNonEmptySinceEpochMillis = NOW,
                lastResult = BackgroundSyncResult.PARTIAL_REJECTIONS,
                lastMeaningfulSyncAtEpochMillis = NOW,
            ),
            NOW,
        )

        assertEquals(QueueAlertReason.PARTIAL_REJECTIONS, evaluation.activeAlert?.reason)
        assertEquals(0L, evaluation.activeAlert?.queueAgeMillis)
    }

    @Test
    fun negativePersistedTimestampsAreInactiveInsteadOfOverflowing() {
        assertInactive(snapshot(queueNonEmptySinceEpochMillis = -1L), NOW)
        assertInactive(snapshot(queueNonEmptySinceEpochMillis = 0L), -1L)
    }

    private fun assertActive(snapshot: QueueHealthSnapshot, reason: QueueAlertReason) {
        val evaluation = evaluateQueueHealth(snapshot, NOW)
        assertEquals(reason, evaluation.activeAlert?.reason)
        assertTrue(evaluation.shouldPresent)
    }

    private fun assertInactive(snapshot: QueueHealthSnapshot, nowEpochMillis: Long) {
        assertInactive(evaluateQueueHealth(snapshot, nowEpochMillis))
    }

    private fun assertInactive(evaluation: QueueHealthEvaluation) {
        assertNull(evaluation.activeAlert)
        assertFalse(evaluation.shouldPresent)
    }

    private fun snapshot(
        pendingActionCount: Int = 1,
        queueNonEmptySinceEpochMillis: Long? = NOW - QUEUE_STUCK_THRESHOLD_MILLIS,
        lastResult: BackgroundSyncResult? = null,
        lastMeaningfulSyncAtEpochMillis: Long? = null,
        backgroundSyncEnabled: Boolean = true,
        alertsEnabled: Boolean = true,
        lastAlertReason: QueueAlertReason? = null,
        lastAlertAtEpochMillis: Long? = null,
    ) = QueueHealthSnapshot(
        pendingActionCount = pendingActionCount,
        queueNonEmptySinceEpochMillis = queueNonEmptySinceEpochMillis,
        lastResult = lastResult,
        lastMeaningfulSyncAtEpochMillis = lastMeaningfulSyncAtEpochMillis,
        backgroundSyncEnabled = backgroundSyncEnabled,
        alertsEnabled = alertsEnabled,
        lastAlertReason = lastAlertReason,
        lastAlertAtEpochMillis = lastAlertAtEpochMillis,
    )

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
