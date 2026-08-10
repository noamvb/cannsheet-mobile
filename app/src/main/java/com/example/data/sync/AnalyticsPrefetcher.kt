package com.example.data.sync

import com.example.data.HistoryEventDto
import com.example.data.HistoryFilters
import com.example.data.HistoryResponseDto
import com.example.data.InsightsRange
import com.example.data.InsightsResponseDto
import com.example.data.cachedInsightsRange
import com.example.data.runCatchingCancellable

/**
 * The small boundary the prefetcher needs from the analytics repository. Keeping it free of
 * Android framework types makes the freshness and merge decisions cheap to exercise on the JVM,
 * matching [BackgroundSyncOperations].
 */
interface AnalyticsPrefetchOperations {
    suspend fun readCachedInsights(): InsightsResponseDto?

    suspend fun readCachedHistory(): HistoryResponseDto?

    suspend fun fetchInsights(range: InsightsRange): InsightsResponseDto

    suspend fun fetchHistory(filters: HistoryFilters): HistoryResponseDto

    suspend fun saveHistory(filters: HistoryFilters, response: HistoryResponseDto)
}

enum class AnalyticsPrefetchStatus { REFRESHED, SKIPPED_FRESH, FAILED }

data class AnalyticsPrefetchOutcome(
    val insights: AnalyticsPrefetchStatus,
    val history: AnalyticsPrefetchStatus,
)

/**
 * Warms the analytics cache the Insights and History screens read on a cold open. Every step is
 * best effort: a failure here is never a reason to redeliver the queue or to overwrite the
 * background sync status a person sees in Settings. The two resources are independent, so a
 * failed Insights fetch must not prevent History from refreshing.
 */
class AnalyticsPrefetcher(
    private val operations: AnalyticsPrefetchOperations,
    private val minimumCacheAgeMillis: Long = DEFAULT_MINIMUM_CACHE_AGE_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun prefetch(): AnalyticsPrefetchOutcome = AnalyticsPrefetchOutcome(
        insights = prefetchInsights(),
        history = prefetchHistory(),
    )

    private suspend fun prefetchInsights(): AnalyticsPrefetchStatus {
        val cached = runCatchingCancellable { operations.readCachedInsights() }.getOrNull()
        if (cached != null && !isStaleEnough(cached.generatedAtEpochMillis)) {
            return AnalyticsPrefetchStatus.SKIPPED_FRESH
        }
        val range = cached?.cachedInsightsRange() ?: InsightsRange.Default
        return runCatchingCancellable { operations.fetchInsights(range) }
            .fold({ AnalyticsPrefetchStatus.REFRESHED }, { AnalyticsPrefetchStatus.FAILED })
    }

    private suspend fun prefetchHistory(): AnalyticsPrefetchStatus {
        val cached = runCatchingCancellable { operations.readCachedHistory() }.getOrNull()
        if (cached != null && !isStaleEnough(cached.generatedAtEpochMillis)) {
            return AnalyticsPrefetchStatus.SKIPPED_FRESH
        }
        val filters = cached?.filters ?: HistoryFilters()
        return runCatchingCancellable {
            val fresh = operations.fetchHistory(filters)
            operations.saveHistory(filters, mergeRetainingDepth(fresh, cached))
        }.fold({ AnalyticsPrefetchStatus.REFRESHED }, { AnalyticsPrefetchStatus.FAILED })
    }

    /**
     * History is sorted TIMESTAMP_DESC_CANONICAL_ROW_DESC, so cached events strictly older than
     * the oldest event on the fresh first page were never inside the refreshed window and are
     * still valid to keep. Anything inside that window which the server did not return again was
     * corrected, voided, or filtered out, so it must not survive. When the server reports no
     * further pages the fresh response is already complete and nothing is retained.
     */
    private fun mergeRetainingDepth(
        fresh: HistoryResponseDto,
        cached: HistoryResponseDto?,
    ): HistoryResponseDto {
        if (cached == null || !fresh.page.hasMore) return fresh
        val oldestFresh = fresh.events.minOfOrNull { it.occurredAtEpochMillis } ?: return fresh
        val retained = cached.events.filter { it.occurredAtEpochMillis < oldestFresh }
        if (retained.isEmpty()) return fresh
        // Fresh events come first so distinctBy keeps the server's current copy of any event a
        // correction moved backwards in time.
        return fresh.copy(
            events = (fresh.events + retained).distinctBy(HistoryEventDto::eventUuid),
        )
    }

    private fun isStaleEnough(generatedAtEpochMillis: Long): Boolean {
        // A negative age means the device and server clocks disagree. Treat that cache as fresh
        // rather than refetching on every wake.
        val age = now() - generatedAtEpochMillis
        return age >= minimumCacheAgeMillis
    }

    private companion object {
        const val DEFAULT_MINIMUM_CACHE_AGE_MILLIS = 2L * 60 * 60 * 1000
    }
}
