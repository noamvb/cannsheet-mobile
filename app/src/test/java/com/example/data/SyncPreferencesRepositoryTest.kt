package com.example.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.sync.QUEUE_STUCK_THRESHOLD_MILLIS
import com.example.data.sync.QueueAlertClaim
import com.example.data.sync.QueueAlertReason
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPreferencesRepositoryTest {
    @Test
    fun defaultsKeepSyncEnabledAndQueueAlertsOptedOut() {
        val preferences = SyncPreferences()

        assertTrue(preferences.enabled)
        assertNull(preferences.lastMeaningfulSyncAtEpochMillis)
        assertNull(preferences.lastResult)
        assertFalse(preferences.queueAlertsEnabled)
        assertNull(preferences.queueNonEmptySinceEpochMillis)
        assertNull(preferences.lastQueueAlertReason)
        assertNull(preferences.lastQueueAlertAtEpochMillis)
        assertNull(preferences.lastQueueAlertClaimId)
    }

    @Test
    fun emptyDataStoreDecodesTheSameSafeDefaults() = runBlocking {
        val preferences = SyncPreferencesRepository(RecordingPreferencesDataStore())
            .preferences
            .first()

        assertEquals(SyncPreferences(), preferences)
    }

    @Test
    fun usesStableDedicatedDataStoreAndKeyNames() {
        assertEquals("sync_preferences", SYNC_PREFERENCES_DATA_STORE_NAME)
        assertEquals("background_sync_enabled", BACKGROUND_SYNC_ENABLED_KEY)
        assertEquals("last_background_sync_epoch_millis", LAST_BACKGROUND_SYNC_EPOCH_MILLIS_KEY)
        assertEquals("last_background_sync_result", LAST_BACKGROUND_SYNC_RESULT_KEY)
        assertEquals("queue_alerts_enabled", QUEUE_ALERTS_ENABLED_KEY)
        assertEquals(
            "queue_non_empty_since_epoch_millis",
            QUEUE_NON_EMPTY_SINCE_EPOCH_MILLIS_KEY,
        )
        assertEquals("last_queue_alert_reason", LAST_QUEUE_ALERT_REASON_KEY)
        assertEquals("last_queue_alert_at_epoch_millis", LAST_QUEUE_ALERT_AT_EPOCH_MILLIS_KEY)
        assertEquals("last_queue_alert_claim_id", LAST_QUEUE_ALERT_CLAIM_ID_KEY)
    }

    @Test
    fun resultEnumKeepsDistinctTerminalStatesForSettings() {
        assertEquals(
            setOf(
                BackgroundSyncResult.SUCCESS,
                BackgroundSyncResult.PARTIAL_REJECTIONS,
                BackgroundSyncResult.BACKEND_CAPABILITY_PENDING,
                BackgroundSyncResult.COMPLETED_WITHOUT_ACK,
                BackgroundSyncResult.RETRY_EXHAUSTED,
                BackgroundSyncResult.ENVIRONMENT_MISMATCH,
            ),
            BackgroundSyncResult.entries.toSet(),
        )
    }

    @Test
    fun firstNonEmptyObservationSetsWatermarkAndLaterObservationsDoNotRestartIt() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())

        repository.observeQueueDepthForTest(pendingActionCount = 2, nowEpochMillis = 100L)
        repository.observeQueueDepthForTest(pendingActionCount = 3, nowEpochMillis = 200L)

        assertEquals(100L, repository.preferences.first().queueNonEmptySinceEpochMillis)
    }

    @Test
    fun drainedQueueClearsWatermarkAndAlertBookkeeping() = runBlocking {
        val dataStore = RecordingPreferencesDataStore(
            preferencesOf(
                longPreferencesKey(QUEUE_NON_EMPTY_SINCE_EPOCH_MILLIS_KEY) to 100L,
                stringPreferencesKey(LAST_QUEUE_ALERT_REASON_KEY) to
                    QueueAlertReason.STUCK_QUEUE.name,
                longPreferencesKey(LAST_QUEUE_ALERT_AT_EPOCH_MILLIS_KEY) to 200L,
            ),
        )
        val repository = SyncPreferencesRepository(dataStore)

        repository.observeQueueDepthForTest(pendingActionCount = 0, nowEpochMillis = 300L)

        val preferences = repository.preferences.first()
        assertNull(preferences.queueNonEmptySinceEpochMillis)
        assertNull(preferences.lastQueueAlertReason)
        assertNull(preferences.lastQueueAlertAtEpochMillis)
    }

    @Test
    fun backwardsClockRebasesTheWatermark() = runBlocking {
        val dataStore = RecordingPreferencesDataStore(
            preferencesOf(
                longPreferencesKey(QUEUE_NON_EMPTY_SINCE_EPOCH_MILLIS_KEY) to 200L,
            ),
        )
        val repository = SyncPreferencesRepository(dataStore)

        repository.observeQueueDepthForTest(pendingActionCount = 1, nowEpochMillis = 100L)

        assertEquals(100L, repository.preferences.first().queueNonEmptySinceEpochMillis)
    }

    @Test
    fun currentDepthObservationReReadsAfterEveryDelayedTrigger() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())
        val currentCount = AtomicInteger(1)

        repository.reconcileCurrentQueueDepth(
            clock = { 100L },
            readPendingActionCount = currentCount::get,
        )
        assertEquals(100L, repository.preferences.first().queueNonEmptySinceEpochMillis)

        currentCount.set(0)
        repository.reconcileCurrentQueueDepth(
            clock = { 200L },
            readPendingActionCount = currentCount::get,
        )
        // Simulates a delayed trigger that originally represented a positive count. It must read
        // the current zero instead of resurrecting the old episode.
        repository.reconcileCurrentQueueDepth(
            clock = { 300L },
            readPendingActionCount = currentCount::get,
        )
        assertNull(repository.preferences.first().queueNonEmptySinceEpochMillis)

        currentCount.set(1)
        repository.reconcileCurrentQueueDepth(
            clock = { 400L },
            readPendingActionCount = currentCount::get,
        )
        // Simulates a delayed trigger that originally represented zero. It must read the current
        // positive count instead of clearing the new episode.
        repository.reconcileCurrentQueueDepth(
            clock = { 500L },
            readPendingActionCount = currentCount::get,
        )
        assertEquals(400L, repository.preferences.first().queueNonEmptySinceEpochMillis)
    }

    @Test
    fun alertReasonRoundTripsAndUnknownStoredReasonDecodesToNull() = runBlocking {
        val dataStore = RecordingPreferencesDataStore()
        val repository = SyncPreferencesRepository(dataStore)
        repository.setQueueAlertsEnabled(true)
        repository.observeQueueDepthForTest(1, NOW - QUEUE_STUCK_THRESHOLD_MILLIS)
        val claim = repository.evaluateAndClaimQueueAlertForTest(1, NOW)

        assertEquals(
            QueueAlertReason.STUCK_QUEUE,
            repository.preferences.first().lastQueueAlertReason,
        )
        assertEquals(claim.claimId, repository.preferences.first().lastQueueAlertClaimId)
        assertTrue(claim.shouldPresent)

        val unknownRepository = SyncPreferencesRepository(
            RecordingPreferencesDataStore(
                preferencesOf(
                    stringPreferencesKey(LAST_QUEUE_ALERT_REASON_KEY) to "FUTURE_REASON",
                ),
            ),
        )
        assertNull(unknownRepository.preferences.first().lastQueueAlertReason)
    }

    @Test
    fun unknownStoredBackgroundResultStillDecodesToNull() = runBlocking {
        val repository = SyncPreferencesRepository(
            RecordingPreferencesDataStore(
                preferencesOf(
                    stringPreferencesKey(LAST_BACKGROUND_SYNC_RESULT_KEY) to "FUTURE_RESULT",
                ),
            ),
        )

        assertNull(repository.preferences.first().lastResult)
    }

    @Test
    fun evaluateAndClaimAllowsOnlyOneConcurrentPresenter() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())
        repository.setQueueAlertsEnabled(true)
        repository.observeQueueDepthForTest(1, NOW - QUEUE_STUCK_THRESHOLD_MILLIS)

        val evaluations = coroutineScope {
            List(8) {
                async { repository.evaluateAndClaimQueueAlertForTest(1, NOW) }
            }.awaitAll()
        }

        assertEquals(1, evaluations.count { it.shouldPresent })
        assertTrue(evaluations.all { it.activeAlert?.reason == QueueAlertReason.STUCK_QUEUE })
    }

    @Test
    fun failedDeliveryReleasesOnlyItsExactClaimAndCanRetryImmediately() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())
        repository.setQueueAlertsEnabled(true)
        repository.observeQueueDepthForTest(1, NOW - QUEUE_STUCK_THRESHOLD_MILLIS)

        val first = repository.evaluateAndClaimQueueAlertForTest(1, NOW)
        repository.releaseQueueAlertClaim(requireNotNull(first.claimId))

        val second = repository.evaluateAndClaimQueueAlertForTest(1, NOW)
        assertTrue(second.shouldPresent)
        assertTrue(second.claimId != first.claimId)
    }

    @Test
    fun completedDeliveryRetainsSuppressionButRetiresClaimToken() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())
        repository.setQueueAlertsEnabled(true)
        repository.observeQueueDepthForTest(1, NOW - QUEUE_STUCK_THRESHOLD_MILLIS)

        val claim = repository.evaluateAndClaimQueueAlertForTest(1, NOW)
        assertTrue(repository.completeQueueAlertClaim(requireNotNull(claim.claimId)))

        val preferences = repository.preferences.first()
        assertEquals(QueueAlertReason.STUCK_QUEUE, preferences.lastQueueAlertReason)
        assertEquals(NOW, preferences.lastQueueAlertAtEpochMillis)
        assertNull(preferences.lastQueueAlertClaimId)
        assertFalse(repository.evaluateAndClaimQueueAlertForTest(1, NOW + 1).shouldPresent)
    }

    @Test
    fun lateFailedClaimCannotClearANewerEscalation() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())
        repository.setQueueAlertsEnabled(true)
        repository.observeQueueDepthForTest(1, NOW - QUEUE_STUCK_THRESHOLD_MILLIS)
        val oldClaim = repository.evaluateAndClaimQueueAlertForTest(1, NOW)

        repository.recordMeaningfulResult(
            result = BackgroundSyncResult.ENVIRONMENT_MISMATCH,
            completedAtEpochMillis = NOW + 1,
        )
        val newClaim = repository.evaluateAndClaimQueueAlertForTest(1, NOW + 1)
        assertFalse(repository.completeQueueAlertClaim(requireNotNull(oldClaim.claimId)))
        repository.releaseQueueAlertClaim(requireNotNull(oldClaim.claimId))

        val preferences = repository.preferences.first()
        assertEquals(QueueAlertReason.ENVIRONMENT_MISMATCH, preferences.lastQueueAlertReason)
        assertEquals(newClaim.claimId, preferences.lastQueueAlertClaimId)
    }

    @Test
    fun terminalResultRecoversAMissingWatermarkForCurrentNonEmptyQueue() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())
        repository.setQueueAlertsEnabled(true)

        repository.recordMeaningfulResultForCurrentQueue(
            result = BackgroundSyncResult.PARTIAL_REJECTIONS,
            readPendingActionCount = { 2 },
            clock = { NOW },
        )

        assertEquals(NOW, repository.preferences.first().queueNonEmptySinceEpochMillis)
        val claim = repository.evaluateAndClaimQueueAlertForTest(2, NOW)
        assertEquals(QueueAlertReason.PARTIAL_REJECTIONS, claim.activeAlert?.reason)
        assertEquals(0L, claim.activeAlert?.queueAgeMillis)
    }

    @Test
    fun terminalResultDoesNotInventAQueueEpisodeWhenTheCurrentQueueIsEmpty() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())

        repository.recordMeaningfulResultForCurrentQueue(
            result = BackgroundSyncResult.SUCCESS,
            readPendingActionCount = { 0 },
            clock = { NOW },
        )

        assertNull(repository.preferences.first().queueNonEmptySinceEpochMillis)
    }

    @Test
    fun disablingAlertsClearsSuppressionButKeepsTheQueueEpisode() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())
        repository.setQueueAlertsEnabled(true)
        repository.observeQueueDepthForTest(1, NOW - QUEUE_STUCK_THRESHOLD_MILLIS)
        repository.evaluateAndClaimQueueAlertForTest(1, NOW)

        repository.setQueueAlertsEnabled(false)

        val preferences = repository.preferences.first()
        assertFalse(preferences.queueAlertsEnabled)
        assertEquals(
            NOW - QUEUE_STUCK_THRESHOLD_MILLIS,
            preferences.queueNonEmptySinceEpochMillis,
        )
        assertNull(preferences.lastQueueAlertReason)
        assertNull(preferences.lastQueueAlertAtEpochMillis)
        assertNull(preferences.lastQueueAlertClaimId)
    }

    @Test
    fun disablingBackgroundSyncClearsSuppressionButKeepsTheQueueEpisode() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())
        repository.setQueueAlertsEnabled(true)
        repository.observeQueueDepthForTest(1, NOW - QUEUE_STUCK_THRESHOLD_MILLIS)
        repository.evaluateAndClaimQueueAlertForTest(1, NOW)

        repository.setEnabled(false)

        val preferences = repository.preferences.first()
        assertFalse(preferences.enabled)
        assertEquals(
            NOW - QUEUE_STUCK_THRESHOLD_MILLIS,
            preferences.queueNonEmptySinceEpochMillis,
        )
        assertNull(preferences.lastQueueAlertReason)
        assertNull(preferences.lastQueueAlertAtEpochMillis)
        assertNull(preferences.lastQueueAlertClaimId)
    }

    @Test
    fun clearAlertStateKeepsEnabledSwitchAndWatermark() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())
        repository.setQueueAlertsEnabled(true)
        repository.observeQueueDepthForTest(1, NOW - QUEUE_STUCK_THRESHOLD_MILLIS)
        repository.evaluateAndClaimQueueAlertForTest(1, NOW)

        repository.clearQueueAlertState()

        val preferences = repository.preferences.first()
        assertTrue(preferences.queueAlertsEnabled)
        assertEquals(
            NOW - QUEUE_STUCK_THRESHOLD_MILLIS,
            preferences.queueNonEmptySinceEpochMillis,
        )
        assertNull(preferences.lastQueueAlertReason)
        assertNull(preferences.lastQueueAlertAtEpochMillis)
        assertNull(preferences.lastQueueAlertClaimId)
    }

    @Test
    fun resolvedConditionClearsOldSuppressionState() = runBlocking {
        val dataStore = RecordingPreferencesDataStore(
            preferencesOf(
                booleanPreferencesKey(QUEUE_ALERTS_ENABLED_KEY) to true,
                longPreferencesKey(QUEUE_NON_EMPTY_SINCE_EPOCH_MILLIS_KEY) to NOW - 1,
                stringPreferencesKey(LAST_QUEUE_ALERT_REASON_KEY) to
                    QueueAlertReason.STUCK_QUEUE.name,
                longPreferencesKey(LAST_QUEUE_ALERT_AT_EPOCH_MILLIS_KEY) to NOW,
            ),
        )
        val repository = SyncPreferencesRepository(dataStore)

        val evaluation = repository.evaluateAndClaimQueueAlertForTest(1, NOW)

        assertNull(evaluation.activeAlert)
        assertNull(repository.preferences.first().lastQueueAlertReason)
        assertNull(repository.preferences.first().lastQueueAlertAtEpochMillis)
    }

    @Test
    fun currentDepthReadsAreSerializedWithTheirWrites() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())
        val firstReadStarted = CompletableDeferred<Unit>()
        val releaseFirstRead = CompletableDeferred<Unit>()
        val secondReadStarted = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            repository.reconcileCurrentQueueDepth(
                readPendingActionCount = {
                    firstReadStarted.complete(Unit)
                    releaseFirstRead.await()
                    1
                },
                clock = { 100L },
            )
        }
        firstReadStarted.await()

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            repository.reconcileCurrentQueueDepth(
                readPendingActionCount = {
                    secondReadStarted.complete(Unit)
                    0
                },
                clock = { 200L },
            )
        }

        assertFalse(secondReadStarted.isCompleted)
        releaseFirstRead.complete(Unit)
        awaitAll(first, second)

        assertTrue(secondReadStarted.isCompleted)
        assertNull(repository.preferences.first().queueNonEmptySinceEpochMillis)
    }

    @Test
    fun terminalResultDoesNotRebaseANewerQueueEpisode() = runBlocking {
        val repository = SyncPreferencesRepository(
            RecordingPreferencesDataStore(
                preferencesOf(
                    longPreferencesKey(QUEUE_NON_EMPTY_SINCE_EPOCH_MILLIS_KEY) to 200L,
                ),
            ),
        )

        repository.recordMeaningfulResultForCurrentQueue(
            result = BackgroundSyncResult.ENVIRONMENT_MISMATCH,
            readPendingActionCount = { 1 },
            clock = { 100L },
        )

        val preferences = repository.preferences.first()
        assertEquals(200L, preferences.queueNonEmptySinceEpochMillis)
        assertEquals(100L, preferences.lastMeaningfulSyncAtEpochMillis)
    }

    @Test
    fun currentAlertEvaluationReconcilesADrainedQueueBeforeClaiming() = runBlocking {
        val repository = SyncPreferencesRepository(RecordingPreferencesDataStore())
        repository.setQueueAlertsEnabled(true)
        repository.observeQueueDepthForTest(1, NOW - QUEUE_STUCK_THRESHOLD_MILLIS)
        val first = repository.evaluateAndClaimQueueAlertForTest(1, NOW)
        assertTrue(first.shouldPresent)

        val afterDrain = repository.evaluateAndClaimCurrentQueueAlert(
            readPendingActionCount = { 0 },
            clock = { NOW + 1 },
        )

        assertNull(afterDrain.activeAlert)
        val preferences = repository.preferences.first()
        assertNull(preferences.queueNonEmptySinceEpochMillis)
        assertNull(preferences.lastQueueAlertReason)
        assertNull(preferences.lastQueueAlertAtEpochMillis)
        assertNull(preferences.lastQueueAlertClaimId)
        assertFalse(repository.completeQueueAlertClaim(requireNotNull(first.claimId)))
    }

    private suspend fun SyncPreferencesRepository.observeQueueDepthForTest(
        pendingActionCount: Int,
        nowEpochMillis: Long,
    ) {
        reconcileCurrentQueueDepth(
            readPendingActionCount = { pendingActionCount },
            clock = { nowEpochMillis },
        )
    }

    private suspend fun SyncPreferencesRepository.evaluateAndClaimQueueAlertForTest(
        pendingActionCount: Int,
        nowEpochMillis: Long,
    ): QueueAlertClaim = evaluateAndClaimCurrentQueueAlert(
        readPendingActionCount = { pendingActionCount },
        clock = { nowEpochMillis },
    )

    private class RecordingPreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        val updateCount = AtomicInteger()

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            updateCount.incrementAndGet()
            transform(state.value).also { updated -> state.value = updated }
        }
    }

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
