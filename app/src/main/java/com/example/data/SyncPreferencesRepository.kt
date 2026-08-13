package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.sync.QueueAlert
import com.example.data.sync.QueueAlertClaim
import com.example.data.sync.QueueAlertReason
import com.example.data.sync.QueueHealthEvaluation
import com.example.data.sync.QueueHealthSnapshot
import com.example.data.sync.evaluateQueueHealth
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val SYNC_PREFERENCES_DATA_STORE_NAME = "sync_preferences"
internal const val BACKGROUND_SYNC_ENABLED_KEY = "background_sync_enabled"
internal const val LAST_BACKGROUND_SYNC_EPOCH_MILLIS_KEY = "last_background_sync_epoch_millis"
internal const val LAST_BACKGROUND_SYNC_RESULT_KEY = "last_background_sync_result"
internal const val QUEUE_ALERTS_ENABLED_KEY = "queue_alerts_enabled"
internal const val QUEUE_NON_EMPTY_SINCE_EPOCH_MILLIS_KEY =
    "queue_non_empty_since_epoch_millis"
internal const val LAST_QUEUE_ALERT_REASON_KEY = "last_queue_alert_reason"
internal const val LAST_QUEUE_ALERT_AT_EPOCH_MILLIS_KEY =
    "last_queue_alert_at_epoch_millis"
internal const val LAST_QUEUE_ALERT_CLAIM_ID_KEY = "last_queue_alert_claim_id"

private val Context.syncPreferencesDataStore by preferencesDataStore(
    name = SYNC_PREFERENCES_DATA_STORE_NAME,
)

/**
 * The terminal outcome worth showing to a person. Retryable failures deliberately do not
 * overwrite this value: WorkManager may still repair those without user action.
 */
enum class BackgroundSyncResult {
    SUCCESS,
    PARTIAL_REJECTIONS,
    BACKEND_CAPABILITY_PENDING,
    COMPLETED_WITHOUT_ACK,
    RETRY_EXHAUSTED,
    ENVIRONMENT_MISMATCH,
}

data class SyncPreferences(
    val enabled: Boolean = true,
    val lastMeaningfulSyncAtEpochMillis: Long? = null,
    val lastResult: BackgroundSyncResult? = null,
    val queueAlertsEnabled: Boolean = false,
    val queueNonEmptySinceEpochMillis: Long? = null,
    val lastQueueAlertReason: QueueAlertReason? = null,
    val lastQueueAlertAtEpochMillis: Long? = null,
    val lastQueueAlertClaimId: String? = null,
)

class SyncPreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val queueObservationMutex = Mutex()

    constructor(context: Context) : this(context.applicationContext.syncPreferencesDataStore)

    val preferences: Flow<SyncPreferences> = dataStore.data
        .map(::decode)
        .distinctUntilChanged()

    suspend fun isEnabled(): Boolean = preferences.first().enabled

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { stored ->
            stored[ENABLED] = enabled
            if (!enabled) stored.clearLastQueueAlert()
        }
    }

    suspend fun setQueueAlertsEnabled(enabled: Boolean) {
        dataStore.edit { stored ->
            stored[QUEUE_ALERTS_ENABLED] = enabled
            if (!enabled) stored.clearLastQueueAlert()
        }
    }

    /**
     * Records the empty/non-empty transition. Existing watermarks are intentionally idempotent so
     * repeated observations cannot restart the stuck-queue clock.
     */
    private suspend fun observeQueueDepth(
        pendingActionCount: Int,
        nowEpochMillis: Long,
    ) {
        dataStore.edit { stored ->
            reconcileQueueDepth(stored, pendingActionCount, nowEpochMillis)
        }
    }

    /** Applies the single application collector's Room transitions in their emitted order. */
    internal suspend fun reconcileObservedQueueDepth(
        pendingActionCount: Int,
        clock: () -> Long = System::currentTimeMillis,
    ) {
        queueObservationMutex.withLock {
            observeQueueDepth(
                pendingActionCount = pendingActionCount,
                nowEpochMillis = clock(),
            )
        }
    }

    /**
     * Serializes the Room read with the DataStore transition and deliberately re-reads the current
     * count after acquiring the lock. WorkManager uses this fresh-state path; the application
     * collector preserves its emitted transitions through [reconcileObservedQueueDepth].
     */
    suspend fun reconcileCurrentQueueDepth(
        readPendingActionCount: suspend () -> Int,
        clock: () -> Long = System::currentTimeMillis,
    ) {
        queueObservationMutex.withLock {
            observeQueueDepth(
                pendingActionCount = readPendingActionCount(),
                nowEpochMillis = clock(),
            )
        }
    }

    /** Clears repeat-suppression bookkeeping without changing the enabled switch or watermark. */
    suspend fun clearQueueAlertState() {
        dataStore.edit { stored -> stored.clearLastQueueAlert() }
    }

    /**
     * Evaluates and claims presentation eligibility in one DataStore transaction. The periodic
     * and immediate WorkManager chains may overlap outside SyncEngine's mutex; this atomic claim
     * ensures at most one worker receives shouldPresent=true for the same repeat window.
     */
    suspend fun evaluateAndClaimCurrentQueueAlert(
        readPendingActionCount: suspend () -> Int,
        clock: () -> Long = System::currentTimeMillis,
    ): QueueAlertClaim = evaluateCurrentQueueAlert(
        readPendingActionCount = readPendingActionCount,
        clock = clock,
        allowClaim = true,
    )

    /**
     * Re-reads and evaluates the current queue without consuming presentation eligibility.
     * Notification clearing uses this after acquiring its own serialization mutex so a stale
     * collector decision cannot cancel a newer card.
     */
    internal suspend fun inspectCurrentQueueAlert(
        readPendingActionCount: suspend () -> Int,
        clock: () -> Long = System::currentTimeMillis,
    ): QueueAlert? = evaluateCurrentQueueAlert(
        readPendingActionCount = readPendingActionCount,
        clock = clock,
        allowClaim = false,
    ).activeAlert

    private suspend fun evaluateCurrentQueueAlert(
        readPendingActionCount: suspend () -> Int,
        clock: () -> Long,
        allowClaim: Boolean,
    ): QueueAlertClaim {
        return queueObservationMutex.withLock {
            val pendingActionCount = readPendingActionCount()
            val nowEpochMillis = clock()
            var evaluation = QueueHealthEvaluation.INACTIVE
            var claimId: String? = null
            val proposedClaimId = UUID.randomUUID().toString()
            dataStore.edit { stored ->
                reconcileQueueDepth(stored, pendingActionCount, nowEpochMillis)
                val current = decode(stored)
                evaluation = evaluateQueueHealth(
                    snapshot = QueueHealthSnapshot(
                        pendingActionCount = pendingActionCount,
                        queueNonEmptySinceEpochMillis = current.queueNonEmptySinceEpochMillis,
                        lastResult = current.lastResult,
                        lastMeaningfulSyncAtEpochMillis = current.lastMeaningfulSyncAtEpochMillis,
                        backgroundSyncEnabled = current.enabled,
                        alertsEnabled = current.queueAlertsEnabled,
                        lastAlertReason = current.lastQueueAlertReason,
                        lastAlertAtEpochMillis = current.lastQueueAlertAtEpochMillis,
                    ),
                    nowEpochMillis = nowEpochMillis,
                )
                val alert = evaluation.activeAlert
                when {
                    allowClaim && evaluation.shouldPresent && alert != null -> {
                        claimId = proposedClaimId
                        stored[LAST_QUEUE_ALERT_REASON] = alert.reason.name
                        stored[LAST_QUEUE_ALERT_AT] = nowEpochMillis
                        stored[LAST_QUEUE_ALERT_CLAIM_ID] = proposedClaimId
                    }

                    alert == null -> stored.clearLastQueueAlert()
                }
            }
            QueueAlertClaim(
                activeAlert = evaluation.activeAlert,
                claimId = claimId,
            )
        }
    }

    /** Retains repeat suppression after a successful post and retires only this exact claim. */
    suspend fun completeQueueAlertClaim(claimId: String): Boolean {
        var completed = false
        dataStore.edit { stored ->
            if (stored[LAST_QUEUE_ALERT_CLAIM_ID] == claimId) {
                stored.remove(LAST_QUEUE_ALERT_CLAIM_ID)
                completed = true
            }
        }
        return completed
    }

    /**
     * Releases only the supplied claim after a failed post. Comparing the opaque token prevents a
     * late failure from clearing a newer claim or a different escalation.
     */
    suspend fun releaseQueueAlertClaim(claimId: String) {
        dataStore.edit { stored ->
            if (stored[LAST_QUEUE_ALERT_CLAIM_ID] == claimId) {
                stored.clearLastQueueAlert()
            }
        }
    }

    suspend fun recordMeaningfulResult(
        result: BackgroundSyncResult,
        completedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        queueObservationMutex.withLock {
            recordMeaningfulResultLocked(
                result = result,
                completedAtEpochMillis = completedAtEpochMillis,
            )
        }
    }

    private suspend fun recordMeaningfulResultLocked(
        result: BackgroundSyncResult,
        completedAtEpochMillis: Long,
    ) {
        dataStore.edit { stored ->
            // Every non-success worker result proves at least one action from its sync attempt
            // remains queued. Recover a missing watermark in this required DataStore write instead
            // of adding an advisory Room read after the sync outcome has already been decided.
            if (result != BackgroundSyncResult.SUCCESS && stored[QUEUE_NON_EMPTY_SINCE] == null) {
                stored[QUEUE_NON_EMPTY_SINCE] = completedAtEpochMillis
            }
            stored[LAST_MEANINGFUL_SYNC_AT_EPOCH_MILLIS] = completedAtEpochMillis
            stored[LAST_RESULT] = result.name
        }
    }

    private fun decode(stored: Preferences): SyncPreferences = SyncPreferences(
        enabled = stored[ENABLED] ?: true,
        lastMeaningfulSyncAtEpochMillis = stored[LAST_MEANINGFUL_SYNC_AT_EPOCH_MILLIS],
        lastResult = stored[LAST_RESULT]
            ?.let { storedResult ->
                BackgroundSyncResult.entries.firstOrNull { it.name == storedResult }
            },
        queueAlertsEnabled = stored[QUEUE_ALERTS_ENABLED] ?: false,
        queueNonEmptySinceEpochMillis = stored[QUEUE_NON_EMPTY_SINCE],
        lastQueueAlertReason = stored[LAST_QUEUE_ALERT_REASON]
            ?.let { storedReason ->
                QueueAlertReason.entries.firstOrNull { it.name == storedReason }
            },
        lastQueueAlertAtEpochMillis = stored[LAST_QUEUE_ALERT_AT],
        lastQueueAlertClaimId = stored[LAST_QUEUE_ALERT_CLAIM_ID],
    )

    private fun MutablePreferences.clearLastQueueAlert() {
        remove(LAST_QUEUE_ALERT_REASON)
        remove(LAST_QUEUE_ALERT_AT)
        remove(LAST_QUEUE_ALERT_CLAIM_ID)
    }

    private fun reconcileQueueDepth(
        stored: MutablePreferences,
        pendingActionCount: Int,
        nowEpochMillis: Long,
    ) {
        if (pendingActionCount <= 0) {
            stored.remove(QUEUE_NON_EMPTY_SINCE)
            stored.clearLastQueueAlert()
        } else {
            val since = stored[QUEUE_NON_EMPTY_SINCE]
            if (since == null || nowEpochMillis < since) {
                stored[QUEUE_NON_EMPTY_SINCE] = nowEpochMillis
            }
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey(BACKGROUND_SYNC_ENABLED_KEY)
        val LAST_MEANINGFUL_SYNC_AT_EPOCH_MILLIS =
            longPreferencesKey(LAST_BACKGROUND_SYNC_EPOCH_MILLIS_KEY)
        val LAST_RESULT = stringPreferencesKey(LAST_BACKGROUND_SYNC_RESULT_KEY)
        val QUEUE_ALERTS_ENABLED = booleanPreferencesKey(QUEUE_ALERTS_ENABLED_KEY)
        val QUEUE_NON_EMPTY_SINCE = longPreferencesKey(QUEUE_NON_EMPTY_SINCE_EPOCH_MILLIS_KEY)
        val LAST_QUEUE_ALERT_REASON = stringPreferencesKey(LAST_QUEUE_ALERT_REASON_KEY)
        val LAST_QUEUE_ALERT_AT = longPreferencesKey(LAST_QUEUE_ALERT_AT_EPOCH_MILLIS_KEY)
        val LAST_QUEUE_ALERT_CLAIM_ID = stringPreferencesKey(LAST_QUEUE_ALERT_CLAIM_ID_KEY)
    }
}
