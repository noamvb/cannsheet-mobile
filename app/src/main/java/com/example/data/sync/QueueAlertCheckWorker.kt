package com.example.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.CannsheetGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Alert-only threshold check. Unlike queue synchronization, this work needs no network and never
 * reads, writes, acknowledges, or removes a queue row.
 */
class QueueAlertCheckWorker(
    appContext: Context,
    params: WorkerParameters,
    private val runtime: QueueAlertCheckRuntime,
) : CoroutineWorker(appContext, params) {
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext,
        params,
        GraphQueueAlertCheckRuntime(appContext),
    )

    override suspend fun doWork(): Result = try {
        runtime.reconcileQueueAlert()
        runtime.reconcileSchedule()
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.retry()
    }
}

interface QueueAlertCheckRuntime {
    suspend fun reconcileQueueAlert()

    suspend fun reconcileSchedule()
}

private class GraphQueueAlertCheckRuntime(
    private val context: Context,
) : QueueAlertCheckRuntime {
    private val graph = CannsheetGraph.get(context.applicationContext)

    override suspend fun reconcileQueueAlert() {
        graph.queueAlertDelivery.reconcile()
    }

    override suspend fun reconcileSchedule() {
        val preferences = graph.syncPreferences.preferences.first()
        QueueAlertScheduler.scheduleAfterCheck(context, preferences)
    }
}
