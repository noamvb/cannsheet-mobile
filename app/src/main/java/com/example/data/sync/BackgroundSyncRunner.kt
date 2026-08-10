package com.example.data.sync

import com.example.data.SyncOutcome
import com.example.data.ProductCatalogRefreshResult

/**
 * The small boundary the runner needs from the application graph. Keeping this interface free
 * of Android framework types makes the sync and retry decisions cheap to exercise on the JVM.
 */
interface BackgroundSyncOperations {
    suspend fun hasPendingActions(): Boolean

    suspend fun sync(endpoint: String): SyncOutcome

    suspend fun refreshCatalog(endpoint: String): ProductCatalogRefreshResult

    suspend fun emitApplied(outcome: SyncOutcome.Applied)
}

sealed interface BackgroundSyncRunResult {
    data object NothingToSync : BackgroundSyncRunResult

    data class Applied(
        val outcome: SyncOutcome.Applied,
        val completedPasses: Int,
    ) : BackgroundSyncRunResult

    data class Retry(val outcome: SyncOutcome) : BackgroundSyncRunResult

    data class EnvironmentMismatch(val outcome: SyncOutcome.EnvironmentMismatch) : BackgroundSyncRunResult
}

/**
 * Performs at most two queue snapshots. A second pass picks up work which became eligible after
 * a partial acknowledgement without letting a continuously changing queue keep a Worker alive.
 */
class BackgroundSyncRunner(
    private val endpoint: String,
    private val operations: BackgroundSyncOperations,
) {
    suspend fun run(): BackgroundSyncRunResult {
        if (!operations.hasPendingActions()) return BackgroundSyncRunResult.NothingToSync

        var completedPasses = 0
        var lastApplied: SyncOutcome.Applied? = null
        while (completedPasses < MAX_SYNC_PASSES) {
            when (val outcome = operations.sync(endpoint)) {
                is SyncOutcome.NothingToSync -> {
                    return lastApplied?.let { BackgroundSyncRunResult.Applied(it, completedPasses) }
                        ?: BackgroundSyncRunResult.NothingToSync
                }

                is SyncOutcome.Applied -> {
                    completedPasses += 1
                    lastApplied = outcome
                    operations.emitApplied(outcome)
                    if (outcome.plan.hasAcknowledgements && !outcome.plan.finishCapabilityMissing) {
                        // The acknowledgement is already durable. Any non-Updated result is a
                        // best-effort refresh failure, never a reason to deliver the queue again.
                        operations.refreshCatalog(endpoint)
                    }
                    if (
                        completedPasses == MAX_SYNC_PASSES ||
                        !canSafelyTakeAnotherPass(outcome) ||
                        !operations.hasPendingActions()
                    ) {
                        return BackgroundSyncRunResult.Applied(outcome, completedPasses)
                    }
                }

                is SyncOutcome.EnvironmentMismatch -> {
                    return BackgroundSyncRunResult.EnvironmentMismatch(outcome)
                }

                is SyncOutcome.HtmlResponse,
                is SyncOutcome.RequestIdMismatch,
                is SyncOutcome.Failed,
                is SyncOutcome.TransportError -> return BackgroundSyncRunResult.Retry(outcome)
            }
        }

        return checkNotNull(lastApplied).let { BackgroundSyncRunResult.Applied(it, completedPasses) }
    }

    private companion object {
        const val MAX_SYNC_PASSES = 2
    }

    private fun canSafelyTakeAnotherPass(outcome: SyncOutcome.Applied): Boolean =
        outcome.plan.hasAcknowledgements &&
            !outcome.plan.hasRejections &&
            !outcome.plan.finishCapabilityMissing &&
            !outcome.plan.correctionCapabilityMissing
}
