package com.example.widget

import android.content.Context
import com.example.data.CannsheetGraph
import com.example.data.ConsumptionLogger
import com.example.data.ProductTypeCodes
import com.example.data.sync.SyncScheduler
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Delivers a widget payload after DataStore has atomically claimed the commit. The claim remains
 * durable until Room persistence succeeds. The widget never talks to the network; it records
 * through the normal Room queue and schedules shared sync work.
 */
class PenWidgetCommitCoordinator(
    private val stateRepository: PenWidgetStateRepository,
    private val consumptionLogger: ConsumptionLogger,
    private val enqueueSync: (Context) -> Unit,
    private val updateWidget: suspend (Context, Int) -> Unit,
) {
    suspend fun commit(
        context: Context,
        appWidgetId: Int,
        commitId: String?,
        nowMillis: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): Boolean {
        val claim = stateRepository.claimCommit(
            appWidgetId = appWidgetId,
            commitId = commitId,
            nowMillis = nowMillis,
            force = force,
        )
        if (claim == null) {
            runCatching { updateWidget(context.applicationContext, appWidgetId) }
            return false
        }

        val completed = try {
            logPayload(claim.payload)
            stateRepository.completeCommit(
                appWidgetId = appWidgetId,
                commitId = claim.payload.commitId,
                claimId = claim.claimId,
                nowMillis = nowMillis,
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    stateRepository.releaseClaim(
                        appWidgetId = appWidgetId,
                        commitId = claim.payload.commitId,
                        claimId = claim.claimId,
                    )
                }
            }
            throw error
        } finally {
            runCatching { updateWidget(context.applicationContext, appWidgetId) }
        }

        if (completed) {
            // Scheduling is recoverable and must not turn a durable Room row into a failed commit.
            runCatching { enqueueSync(context.applicationContext) }
        }
        return completed
    }

    suspend fun flushOverdue(context: Context, nowMillis: Long) {
        var firstFailure: Throwable? = null
        stateRepository.pendingCommits().forEach { pending ->
            try {
                commit(
                    context = context,
                    appWidgetId = pending.appWidgetId,
                    commitId = null,
                    nowMillis = nowMillis,
                )
            } catch (error: Throwable) {
                if (firstFailure == null) firstFailure = error
            }
        }
        firstFailure?.let { throw it }
    }

    private suspend fun logPayload(payload: PenWidgetCommitPayload) {
        consumptionLogger.log(
            date = payload.date,
            time = payload.time,
            productId = payload.productId,
            productUuid = payload.productUuid,
            productType = ProductTypeCodes.PEN,
            uses = payload.uses,
            isFinished = false,
            loggedAtEpochMillis = payload.submittedAtEpochMillis,
            eventId = payload.eventId,
            updateLoadedCart = false,
        )
    }

    companion object {
        suspend fun commit(
            context: Context,
            appWidgetId: Int,
            commitId: String?,
            nowMillis: Long = System.currentTimeMillis(),
            force: Boolean = false,
        ): Boolean = forContext(context).commit(context, appWidgetId, commitId, nowMillis, force)

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
