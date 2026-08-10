package com.example.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.BuildConfig
import com.example.data.BackgroundSyncEvent
import com.example.data.BackgroundSyncResult
import com.example.data.CannsheetGraph
import com.example.data.ProductCatalogRefreshResult
import com.example.data.SyncAcknowledgementPlan
import com.example.data.SyncOutcome

/**
 * Injectable bridge around the real graph. A test worker factory can create [SyncWorker] with a fake
 * runtime without replacing WorkManager's production configuration.
 */
interface BackgroundSyncWorkerRuntime {
    suspend fun isEnabled(): Boolean

    suspend fun run(): BackgroundSyncRunResult

    suspend fun recordMeaningfulResult(result: BackgroundSyncResult)
}

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val runtime: BackgroundSyncWorkerRuntime,
) : CoroutineWorker(appContext, params) {
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext,
        params,
        GraphBackgroundSyncWorkerRuntime(appContext),
    )

    override suspend fun doWork(): Result {
        if (!runtime.isEnabled()) return Result.success()

        return when (val result = runtime.run()) {
            BackgroundSyncRunResult.NothingToSync -> Result.success()

            is BackgroundSyncRunResult.Applied -> {
                runtime.recordMeaningfulResult(result.outcome.plan.toBackgroundSyncResult())
                Result.success()
            }

            is BackgroundSyncRunResult.EnvironmentMismatch -> {
                runtime.recordMeaningfulResult(BackgroundSyncResult.ENVIRONMENT_MISMATCH)
                Result.failure()
            }

            is BackgroundSyncRunResult.Retry -> {
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    runtime.recordMeaningfulResult(BackgroundSyncResult.RETRY_EXHAUSTED)
                    Result.success()
                }
            }
        }
    }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 5
    }
}

private class GraphBackgroundSyncWorkerRuntime(context: Context) : BackgroundSyncWorkerRuntime {
    private val graph = CannsheetGraph.get(context.applicationContext)
    private val runner = BackgroundSyncRunner(
        endpoint = BuildConfig.GAS_URL,
        operations = object : BackgroundSyncOperations {
            override suspend fun hasPendingActions(): Boolean = graph.repository.hasPendingActions()

            override suspend fun sync(endpoint: String): SyncOutcome = graph.syncEngine.sync(endpoint)

            override suspend fun refreshCatalog(endpoint: String): ProductCatalogRefreshResult =
                graph.catalogRefresher.refresh(endpoint)

            override suspend fun emitApplied(outcome: SyncOutcome.Applied) {
                graph.emitBackgroundSyncEvent(BackgroundSyncEvent(outcome))
            }
        },
    )

    override suspend fun isEnabled(): Boolean = graph.syncPreferences.isEnabled()

    override suspend fun run(): BackgroundSyncRunResult = runner.run()

    override suspend fun recordMeaningfulResult(result: BackgroundSyncResult) {
        graph.syncPreferences.recordMeaningfulResult(result)
    }
}

private fun SyncAcknowledgementPlan.toBackgroundSyncResult(): BackgroundSyncResult = when {
    finishCapabilityMissing || correctionCapabilityMissing ->
        BackgroundSyncResult.BACKEND_CAPABILITY_PENDING
    hasRejections -> BackgroundSyncResult.PARTIAL_REJECTIONS
    hasAcknowledgements -> BackgroundSyncResult.SUCCESS
    else -> BackgroundSyncResult.COMPLETED_WITHOUT_ACK
}
