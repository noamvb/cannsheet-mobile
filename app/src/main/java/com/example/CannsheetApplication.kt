package com.example

import android.app.Application
import com.example.data.CannsheetGraph
import com.example.data.sync.QueueAlertScheduler
import com.example.data.sync.SyncScheduler
import com.example.notifications.QueueAlertNotifier
import com.example.widget.CannsheetWidgetRefresher
import com.example.widget.PenWidgetCommitCoordinator
import com.example.widget.PenWidgetRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CannsheetApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val graph = CannsheetGraph.get(this)
        graph.installWidgetRefresher(CannsheetWidgetRefresher(this))
        graph.installQueueAlertPresenter(QueueAlertNotifier(this))
        PenWidgetRuntime.launchSerialized {
            PenWidgetCommitCoordinator.flushOverdue(this@CannsheetApplication, System.currentTimeMillis())
        }
        applicationScope.launch {
            graph.syncPreferences.preferences
                .map { preferences -> preferences.enabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        SyncScheduler.schedulePeriodic(this@CannsheetApplication)
                        if (graph.repository.hasPendingActions()) {
                            SyncScheduler.enqueueImmediate(this@CannsheetApplication)
                        }
                    } else {
                        SyncScheduler.cancel(this@CannsheetApplication)
                    }
                }
        }
        applicationScope.launch {
            graph.syncPreferences.preferences
                .map { preferences ->
                    QueueAlertClearTrigger(
                        backgroundSyncEnabled = preferences.enabled,
                        queueAlertsEnabled = preferences.queueAlertsEnabled,
                        queueNonEmptySinceEpochMillis =
                            preferences.queueNonEmptySinceEpochMillis,
                        lastMeaningfulSyncAtEpochMillis =
                            preferences.lastMeaningfulSyncAtEpochMillis,
                        lastResult = preferences.lastResult?.name,
                    )
                }
                .distinctUntilChanged()
                .collect { state ->
                    try {
                        graph.queueAlertDelivery.clearIfCurrentAlertInactive()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        // Notification clearing is advisory; later state changes retry it.
                    }
                }
        }
        applicationScope.launch {
            graph.syncPreferences.preferences
                .map { preferences ->
                    QueueAlertScheduleTrigger(
                        backgroundSyncEnabled = preferences.enabled,
                        queueAlertsEnabled = preferences.queueAlertsEnabled,
                        queueNonEmptySinceEpochMillis =
                            preferences.queueNonEmptySinceEpochMillis,
                    )
                }
                .distinctUntilChanged()
                .collect { state ->
                    runCatching {
                        QueueAlertScheduler.reconcile(
                            context = this@CannsheetApplication,
                            backgroundSyncEnabled = state.backgroundSyncEnabled,
                            queueAlertsEnabled = state.queueAlertsEnabled,
                            queueNonEmptySinceEpochMillis =
                                state.queueNonEmptySinceEpochMillis,
                        )
                    }
                }
        }
        applicationScope.launch {
            collectQueueDepthChanges(
                countChanges = graph.repository.pendingActionCount,
                reconcileObservedDepth = { pendingActionCount ->
                    graph.syncPreferences.reconcileObservedQueueDepth(
                        pendingActionCount = pendingActionCount,
                    )
                },
                onQueueStateChanged = graph.queueAlertDelivery::clearIfCurrentAlertInactive,
            )
        }
    }
}

internal const val QUEUE_DEPTH_OBSERVATION_RETRY_MILLIS = 30_000L

private data class QueueAlertClearTrigger(
    val backgroundSyncEnabled: Boolean,
    val queueAlertsEnabled: Boolean,
    val queueNonEmptySinceEpochMillis: Long?,
    val lastMeaningfulSyncAtEpochMillis: Long?,
    val lastResult: String?,
)

private data class QueueAlertScheduleTrigger(
    val backgroundSyncEnabled: Boolean,
    val queueAlertsEnabled: Boolean,
    val queueNonEmptySinceEpochMillis: Long?,
)

/**
 * Treats Room emissions as reconciliation triggers and retries a failed transition without
 * requiring another database mutation. Transitions are applied in emission order so a quick
 * drain/refill cannot skip the empty boundary and make a new queue inherit old alert state.
 */
internal suspend fun collectQueueDepthChanges(
    countChanges: Flow<Int>,
    reconcileObservedDepth: suspend (Int) -> Unit,
    onQueueStateChanged: suspend () -> Unit = {},
    retryDelay: suspend () -> Unit = { delay(QUEUE_DEPTH_OBSERVATION_RETRY_MILLIS) },
) {
    countChanges
        .distinctUntilChanged()
        .collect { pendingActionCount ->
            while (true) {
                try {
                    reconcileObservedDepth(pendingActionCount)
                    try {
                        onQueueStateChanged()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        // The next queue/worker reconciliation can clear the advisory card.
                    }
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    retryDelay()
                }
            }
        }
}
