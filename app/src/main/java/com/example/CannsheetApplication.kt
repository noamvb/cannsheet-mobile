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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
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
                reconcileCurrentDepth = {
                    graph.syncPreferences.reconcileCurrentQueueDepth(
                        readPendingActionCount = {
                            graph.repository.pendingActionCount.first()
                        },
                    )
                },
            )
        }
    }
}

internal const val QUEUE_DEPTH_OBSERVATION_RETRY_MILLIS = 30_000L

/**
 * Treats Room emissions as reconciliation triggers and retries a failed transition without
 * requiring another database mutation. A newer count supersedes the old retry immediately.
 */
internal suspend fun collectQueueDepthChanges(
    countChanges: Flow<Int>,
    reconcileCurrentDepth: suspend () -> Unit,
    retryDelay: suspend () -> Unit = { delay(QUEUE_DEPTH_OBSERVATION_RETRY_MILLIS) },
) {
    val observerJob = requireNotNull(currentCoroutineContext()[Job])
    countChanges
        .distinctUntilChanged()
        .collectLatest {
            while (true) {
                try {
                    reconcileCurrentDepth()
                    break
                } catch (error: CancellationException) {
                    // collectLatest treats an explicitly thrown CancellationException as a normal
                    // child completion. Cancel the observer itself when its child is still active;
                    // a newer emission already marks the old child inactive and must not stop it.
                    if (currentCoroutineContext().isActive) observerJob.cancel(error)
                    throw error
                } catch (error: Throwable) {
                    retryDelay()
                }
            }
        }
}
