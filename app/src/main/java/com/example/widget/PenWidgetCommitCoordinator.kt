package com.example.widget

import android.content.Context
import com.example.data.CannsheetGraph
import com.example.data.ConsumptionLogger
import com.example.data.ProductTypeCodes
import com.example.data.sync.SyncScheduler

/**
 * Delivers a widget payload after DataStore has atomically won the commit race. The widget never
 * talks to the network; it records through the normal Room queue and schedules shared sync work.
 */
class PenWidgetCommitCoordinator(
    private val stateRepository: PenWidgetStateRepository,
    private val consumptionLogger: ConsumptionLogger,
    private val enqueueSync: (Context) -> Unit,
    private val updateWidget: suspend (Context, Int) -> Unit,
) {
    suspend fun commit(context: Context, appWidgetId: Int, commitId: String?) {
        val payload = stateRepository.takeCommit(
            appWidgetId = appWidgetId,
            commitId = commitId,
            nowMillis = System.currentTimeMillis(),
        )
        if (payload != null) {
            logPayload(context, payload)
        }
        updateWidget(context.applicationContext, appWidgetId)
    }

    suspend fun flushOverdue(context: Context, nowMillis: Long) {
        stateRepository.pendingCommits().forEach { pending ->
            val payload = stateRepository.takeCommit(
                appWidgetId = pending.appWidgetId,
                commitId = null,
                nowMillis = nowMillis,
            ) ?: return@forEach
            logPayload(context, payload)
            updateWidget(context.applicationContext, pending.appWidgetId)
        }
    }

    private suspend fun logPayload(context: Context, payload: PenWidgetCommitPayload) {
        consumptionLogger.log(
            date = payload.date,
            time = payload.time,
            productId = payload.productId,
            productUuid = payload.productUuid,
            productType = ProductTypeCodes.PEN,
            uses = payload.uses,
            isFinished = false,
        )
        enqueueSync(context.applicationContext)
    }

    companion object {
        suspend fun commit(context: Context, appWidgetId: Int, commitId: String?) {
            forContext(context).commit(context, appWidgetId, commitId)
        }

        suspend fun flushOverdue(context: Context, nowMillis: Long) {
            forContext(context).flushOverdue(context, nowMillis)
        }

        private fun forContext(context: Context): PenWidgetCommitCoordinator {
            val appContext = context.applicationContext
            val graph = CannsheetGraph.get(appContext)
            return PenWidgetCommitCoordinator(
                stateRepository = PenWidgetStateRepository(appContext),
                consumptionLogger = graph.consumptionLogger,
                enqueueSync = SyncScheduler::enqueueImmediate,
                updateWidget = PenWidgetUpdater::update,
            )
        }
    }
}
