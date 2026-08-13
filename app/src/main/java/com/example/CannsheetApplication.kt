package com.example

import android.app.Application
import com.example.data.CannsheetGraph
import com.example.data.sync.SyncScheduler
import com.example.widget.PenWidgetCommitCoordinator
import com.example.widget.PenWidgetRefresher
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
        graph.installWidgetRefresher(PenWidgetRefresher(this))
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
            collectQueueDepthChanges(
                countChanges = graph.repository.pendingActionCount,
                reconcileObservedDepth = { pendingActionCount ->
                    graph.syncPreferences.reconcileObservedQueueDepth(
                        pendingActionCount = pendingActionCount,
                    )
                },
            )
        }
    }
}

internal const val QUEUE_DEPTH_OBSERVATION_RETRY_MILLIS = 30_000L

/**
 * Treats Room emissions as reconciliation triggers and retries a failed transition without
 * requiring another database mutation. Transitions are applied in emission order so a quick
 * drain/refill cannot skip the empty boundary and make a new queue inherit old alert state.
 */
internal suspend fun collectQueueDepthChanges(
    countChanges: Flow<Int>,
    reconcileObservedDepth: suspend (Int) -> Unit,
    retryDelay: suspend () -> Unit = { delay(QUEUE_DEPTH_OBSERVATION_RETRY_MILLIS) },
) {
    countChanges
        .distinctUntilChanged()
        .collect { pendingActionCount ->
            while (true) {
                try {
                    reconcileObservedDepth(pendingActionCount)
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    retryDelay()
                }
            }
        }
}
