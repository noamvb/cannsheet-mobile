package com.example.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.BuildConfig
import com.example.data.BackgroundSyncEvent
import com.example.data.BackgroundSyncResult
import com.example.data.CannsheetGraph
import com.example.data.HistoryFilters
import com.example.data.HistoryResponseDto
import com.example.data.InsightsRange
import com.example.data.InsightsResponseDto
import com.example.data.ProductCatalogRefreshResult
import com.example.data.SyncAcknowledgementPlan
import com.example.data.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Injectable bridge around the real graph. A test worker factory can create [SyncWorker] with a fake
 * runtime without replacing WorkManager's production configuration.
 */
interface BackgroundSyncWorkerRuntime {
    suspend fun observeQueueDepth()

    suspend fun isEnabled(): Boolean

    suspend fun reconcileQueueAlert()

    suspend fun run(): BackgroundSyncRunResult

    suspend fun recordMeaningfulResult(result: BackgroundSyncResult)

    suspend fun prefetchAnalytics(): AnalyticsPrefetchOutcome

    fun refreshWidgets()
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
        observeQueueDepthBestEffort()
        if (!runtime.isEnabled()) {
            reconcileQueueAlertBestEffort()
            return Result.success()
        }

        val result = runtime.run()
        val workerResult = when (result) {
            BackgroundSyncRunResult.NothingToSync -> {
                com.example.data.localllm.DailyAssistantWorker.schedule(applicationContext)
                Result.success()
            }

            is BackgroundSyncRunResult.Applied -> {
                runtime.recordMeaningfulResult(result.outcome.plan.toBackgroundSyncResult())
                // A presentation refresh cannot turn an acknowledged queue delivery into a retry.
                runCatching { runtime.refreshWidgets() }
                com.example.data.localllm.DailyAssistantWorker.schedule(applicationContext)
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

        val requested = inputData.getBoolean(SyncScheduler.PREFETCH_ANALYTICS_KEY, false)
        if (shouldPrefetchAnalytics(result, requested)) prefetchAnalyticsBestEffort()
        // Notification work is advisory and cannot replace the queue result decided above.
        reconcileQueueAlertBestEffort()
        return workerResult
    }

    /**
     * The guard is deliberately total: an exception escaping here would turn a queue delivery
     * that already succeeded into a failed Worker run.
     */
    private suspend fun prefetchAnalyticsBestEffort() {
        try {
            runtime.prefetchAnalytics()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Best effort only. The next periodic run tries again.
        }
    }

    /** Queue-health bookkeeping is advisory and cannot alter an existing worker decision. */
    private suspend fun observeQueueDepthBestEffort() {
        try {
            runtime.observeQueueDepth()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // The application-level collector and a later worker can observe the queue again.
        }
    }

    /** Queue-alert delivery is advisory and cannot alter an existing worker decision. */
    private suspend fun reconcileQueueAlertBestEffort() {
        try {
            runtime.reconcileQueueAlert()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // The next application/worker reconciliation can try again.
        }
    }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 5
    }
}

/**
 * Only the periodic chain warms analytics: the one-shot chain runs after user actions, and paying
 * for two Apps Script reads on every logged entry is not worth a warmer cache. A retry means the
 * network is unhealthy and an environment mismatch means analytics would mismatch too, so neither
 * is worth a request.
 *
 * Kept as a pure function so the full gating truth table is testable on the JVM without a device.
 */
internal fun shouldPrefetchAnalytics(
    result: BackgroundSyncRunResult,
    prefetchRequested: Boolean,
): Boolean = prefetchRequested && when (result) {
    BackgroundSyncRunResult.NothingToSync,
    is BackgroundSyncRunResult.Applied,
    -> true

    is BackgroundSyncRunResult.EnvironmentMismatch,
    is BackgroundSyncRunResult.Retry,
    -> false
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
    private val prefetcher = AnalyticsPrefetcher(
        operations = object : AnalyticsPrefetchOperations {
            override suspend fun readCachedInsights(): InsightsResponseDto? =
                graph.analyticsRepository.readCachedInsights()

            override suspend fun readCachedHistory(): HistoryResponseDto? =
                graph.analyticsRepository.readCachedHistory()

            override suspend fun fetchInsights(range: InsightsRange): InsightsResponseDto =
                graph.analyticsRepository.fetchInsights(range)

            override suspend fun fetchHistory(filters: HistoryFilters): HistoryResponseDto =
                graph.analyticsRepository.fetchHistory(filters)

            override suspend fun saveHistory(
                filters: HistoryFilters,
                response: HistoryResponseDto,
            ) {
                graph.analyticsRepository.saveHistory(filters, response)
            }
        },
    )

    override suspend fun observeQueueDepth() {
        graph.syncPreferences.reconcileCurrentQueueDepth(
            readPendingActionCount = { graph.repository.pendingActionCount.first() },
        )
    }

    override suspend fun isEnabled(): Boolean = graph.syncPreferences.isEnabled()

    override suspend fun reconcileQueueAlert() {
        graph.queueAlertDelivery.reconcile()
    }

    override suspend fun run(): BackgroundSyncRunResult = runner.run()

    override suspend fun recordMeaningfulResult(result: BackgroundSyncResult) {
        graph.syncPreferences.recordMeaningfulResult(result)
    }

    override suspend fun prefetchAnalytics(): AnalyticsPrefetchOutcome = prefetcher.prefetch()

    override fun refreshWidgets() {
        graph.widgetRefresher.refreshAll()
    }
}

private fun SyncAcknowledgementPlan.toBackgroundSyncResult(): BackgroundSyncResult = when {
    finishCapabilityMissing || correctionCapabilityMissing ->
        BackgroundSyncResult.BACKEND_CAPABILITY_PENDING
    hasRejections -> BackgroundSyncResult.PARTIAL_REJECTIONS
    hasAcknowledgements -> BackgroundSyncResult.SUCCESS
    else -> BackgroundSyncResult.COMPLETED_WITHOUT_ACK
}
