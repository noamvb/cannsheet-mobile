package com.example.data.sync

import com.example.data.AnalyticsRangeDto
import com.example.data.DataQualityDto
import com.example.data.HistoryEventDto
import com.example.data.HistoryFilters
import com.example.data.HistoryPageDto
import com.example.data.HistoryResponseDto
import com.example.data.InsightsRange
import com.example.data.InsightsResponseDto
import com.example.data.InventoryDto
import com.example.data.OverviewDto
import com.example.data.QualityWarningsDto
import com.example.data.SourceRevisionDto
import com.example.data.SpendBucketDto
import com.example.data.SpendingDto
import com.example.data.SyncHealthDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIXED_NOW = 2_000_000_000_000L
private const val TWO_HOURS_MILLIS = 2L * 60 * 60 * 1000

class AnalyticsPrefetcherTest {

    @Test
    fun absentCacheFetchesTheDefaultRangeAndUnfilteredHistory() = runBlocking {
        val operations = FakeOperations(
            freshInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            freshHistory = historyResponse(events = emptyList(), generatedAtEpochMillis = FIXED_NOW),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        val outcome = prefetcher.prefetch()

        assertEquals(AnalyticsPrefetchStatus.REFRESHED, outcome.insights)
        assertEquals(AnalyticsPrefetchStatus.REFRESHED, outcome.history)
        assertEquals(listOf(InsightsRange.Default), operations.fetchedInsightsRanges)
        assertEquals(listOf(HistoryFilters()), operations.fetchedHistoryFilters)
    }

    @Test
    fun freshCacheSkipsBothResourcesWithoutCallingTheNetwork() = runBlocking {
        val operations = FakeOperations(
            cachedInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            cachedHistory = historyResponse(events = emptyList(), generatedAtEpochMillis = FIXED_NOW),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        val outcome = prefetcher.prefetch()

        assertEquals(AnalyticsPrefetchStatus.SKIPPED_FRESH, outcome.insights)
        assertEquals(AnalyticsPrefetchStatus.SKIPPED_FRESH, outcome.history)
        assertTrue(operations.fetchedInsightsRanges.isEmpty())
        assertTrue(operations.fetchedHistoryFilters.isEmpty())
        assertTrue(operations.savedHistory.isEmpty())
    }

    @Test
    fun staleCacheReusesTheCachedInsightsRange() = runBlocking {
        val operations = FakeOperations(
            cachedInsights = insightsResponse(
                scope = "ALL",
                generatedAtEpochMillis = FIXED_NOW - TWO_HOURS_MILLIS - 1,
            ),
            freshInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            cachedHistory = historyResponse(events = emptyList(), generatedAtEpochMillis = FIXED_NOW),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        prefetcher.prefetch()

        assertEquals(listOf(InsightsRange.All), operations.fetchedInsightsRanges)
    }

    @Test
    fun staleCustomRangeIsRefetchedAsTheSameWindow() = runBlocking {
        val operations = FakeOperations(
            cachedInsights = insightsResponse(
                scope = "CUSTOM",
                from = "2026-06-01",
                to = "2026-06-30",
                generatedAtEpochMillis = FIXED_NOW - TWO_HOURS_MILLIS - 1,
            ),
            freshInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            cachedHistory = historyResponse(events = emptyList(), generatedAtEpochMillis = FIXED_NOW),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        prefetcher.prefetch()

        assertEquals(
            listOf(InsightsRange.Custom("2026-06-01", "2026-06-30")),
            operations.fetchedInsightsRanges,
        )
    }

    @Test
    fun staleCacheReusesTheCachedHistoryFilters() = runBlocking {
        val filters = HistoryFilters(type = "FLOWER")
        val staleHistory = historyResponse(
            events = emptyList(),
            filters = filters,
            generatedAtEpochMillis = FIXED_NOW - TWO_HOURS_MILLIS - 1,
        )
        val freshHistory = historyResponse(
            events = emptyList(),
            filters = filters,
            generatedAtEpochMillis = FIXED_NOW,
        )
        val operations = FakeOperations(
            cachedInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            cachedHistory = staleHistory,
            freshHistory = freshHistory,
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        prefetcher.prefetch()

        assertEquals(listOf(filters), operations.fetchedHistoryFilters)
        assertEquals(1, operations.savedHistory.size)
        assertEquals(filters, operations.savedHistory.single().first)
    }

    @Test
    fun clockSkewMakingTheCacheNewerThanNowIsTreatedAsFresh() = runBlocking {
        val operations = FakeOperations(
            cachedInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW + TWO_HOURS_MILLIS),
            cachedHistory = historyResponse(
                events = emptyList(),
                generatedAtEpochMillis = FIXED_NOW + TWO_HOURS_MILLIS,
            ),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        val outcome = prefetcher.prefetch()

        assertEquals(AnalyticsPrefetchStatus.SKIPPED_FRESH, outcome.insights)
        assertEquals(AnalyticsPrefetchStatus.SKIPPED_FRESH, outcome.history)
    }

    @Test
    fun cacheExactlyAtTheFreshnessFloorIsRefetched() = runBlocking {
        val operations = FakeOperations(
            cachedInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW - TWO_HOURS_MILLIS),
            freshInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            cachedHistory = historyResponse(
                events = emptyList(),
                generatedAtEpochMillis = FIXED_NOW - TWO_HOURS_MILLIS,
            ),
            freshHistory = historyResponse(events = emptyList(), generatedAtEpochMillis = FIXED_NOW),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        val outcome = prefetcher.prefetch()

        assertEquals(AnalyticsPrefetchStatus.REFRESHED, outcome.insights)
        assertEquals(AnalyticsPrefetchStatus.REFRESHED, outcome.history)
    }

    @Test
    fun historyMergeRetainsCachedEventsOlderThanTheFreshPage() = runBlocking {
        val cachedEvents = listOf(
            historyEvent("old-1", occurredAtEpochMillis = 100L),
            historyEvent("old-2", occurredAtEpochMillis = 50L),
        )
        val freshEvents = listOf(
            historyEvent("new-1", occurredAtEpochMillis = 500L),
            historyEvent("new-2", occurredAtEpochMillis = 200L),
        )
        val operations = FakeOperations(
            cachedInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            cachedHistory = historyResponse(
                events = cachedEvents,
                generatedAtEpochMillis = FIXED_NOW - TWO_HOURS_MILLIS - 1,
            ),
            freshHistory = historyResponse(events = freshEvents, hasMore = true, generatedAtEpochMillis = FIXED_NOW),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        prefetcher.prefetch()

        val saved = operations.savedHistory.single().second
        assertEquals(
            listOf("new-1", "new-2", "old-1", "old-2"),
            saved.events.map(HistoryEventDto::eventUuid),
        )
    }

    @Test
    fun historyMergeDropsCachedEventsInsideTheRefreshedWindow() = runBlocking {
        val cachedEvents = listOf(historyEvent("stale-inside-window", occurredAtEpochMillis = 250L))
        val freshEvents = listOf(historyEvent("new-1", occurredAtEpochMillis = 200L))
        val operations = FakeOperations(
            cachedInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            cachedHistory = historyResponse(
                events = cachedEvents,
                generatedAtEpochMillis = FIXED_NOW - TWO_HOURS_MILLIS - 1,
            ),
            freshHistory = historyResponse(events = freshEvents, hasMore = true, generatedAtEpochMillis = FIXED_NOW),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        prefetcher.prefetch()

        val saved = operations.savedHistory.single().second
        assertEquals(listOf("new-1"), saved.events.map(HistoryEventDto::eventUuid))
    }

    @Test
    fun historyMergeKeepsOnlyTheFreshPageWhenTheServerHasNoMorePages() = runBlocking {
        val cachedEvents = listOf(historyEvent("old-1", occurredAtEpochMillis = 10L))
        val freshEvents = listOf(historyEvent("new-1", occurredAtEpochMillis = 500L))
        val operations = FakeOperations(
            cachedInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            cachedHistory = historyResponse(
                events = cachedEvents,
                generatedAtEpochMillis = FIXED_NOW - TWO_HOURS_MILLIS - 1,
            ),
            freshHistory = historyResponse(events = freshEvents, hasMore = false, generatedAtEpochMillis = FIXED_NOW),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        prefetcher.prefetch()

        val saved = operations.savedHistory.single().second
        assertEquals(listOf("new-1"), saved.events.map(HistoryEventDto::eventUuid))
    }

    @Test
    fun historyMergePrefersTheFreshCopyOfADuplicateEvent() = runBlocking {
        val freshEvents = listOf(
            historyEvent("new-1", occurredAtEpochMillis = 500L),
            historyEvent("dup", occurredAtEpochMillis = 200L),
        )
        val cachedEvents = listOf(historyEvent("dup", occurredAtEpochMillis = 50L))
        val operations = FakeOperations(
            cachedInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            cachedHistory = historyResponse(
                events = cachedEvents,
                generatedAtEpochMillis = FIXED_NOW - TWO_HOURS_MILLIS - 1,
            ),
            freshHistory = historyResponse(events = freshEvents, hasMore = true, generatedAtEpochMillis = FIXED_NOW),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        prefetcher.prefetch()

        val saved = operations.savedHistory.single().second
        assertEquals(2, saved.events.size)
        val dupEvent = saved.events.single { it.eventUuid == "dup" }
        assertEquals(200L, dupEvent.occurredAtEpochMillis)
    }

    @Test
    fun historyMergeKeepsTheFreshResponseMetadata() = runBlocking {
        val cachedEvents = listOf(historyEvent("old-1", occurredAtEpochMillis = 10L))
        val freshEvents = listOf(historyEvent("new-1", occurredAtEpochMillis = 500L))
        val operations = FakeOperations(
            cachedInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            cachedHistory = historyResponse(
                events = cachedEvents,
                generatedAtEpochMillis = FIXED_NOW - TWO_HOURS_MILLIS - 1,
                sourceDataVersion = "b".repeat(64),
            ),
            freshHistory = historyResponse(
                events = freshEvents,
                hasMore = true,
                generatedAtEpochMillis = FIXED_NOW,
                sourceDataVersion = "c".repeat(64),
            ),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        prefetcher.prefetch()

        val saved = operations.savedHistory.single().second
        assertEquals(FIXED_NOW, saved.generatedAtEpochMillis)
        assertEquals("c".repeat(64), saved.sourceRevision.dataVersion)
    }

    @Test
    fun insightsFailureStillPrefetchesHistory() = runBlocking {
        val operations = FakeOperations(
            insightsError = IllegalStateException("boom"),
            freshHistory = historyResponse(events = emptyList(), generatedAtEpochMillis = FIXED_NOW),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        val outcome = prefetcher.prefetch()

        assertEquals(AnalyticsPrefetchStatus.FAILED, outcome.insights)
        assertEquals(AnalyticsPrefetchStatus.REFRESHED, outcome.history)
    }

    @Test
    fun historyFetchFailureDoesNotSaveAndDoesNotThrow() = runBlocking {
        val operations = FakeOperations(
            freshInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            historyError = IllegalStateException("boom"),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        val outcome = prefetcher.prefetch()

        assertEquals(AnalyticsPrefetchStatus.FAILED, outcome.history)
        assertTrue(operations.savedHistory.isEmpty())
    }

    @Test
    fun historyFilterRejectedByTheRepositoryIsReportedAsFailed() = runBlocking {
        val operations = FakeOperations(
            freshInsights = insightsResponse(generatedAtEpochMillis = FIXED_NOW),
            historyError = IllegalArgumentException("bad filter"),
        )
        val prefetcher = AnalyticsPrefetcher(operations, now = { FIXED_NOW })

        val outcome = prefetcher.prefetch()

        assertEquals(AnalyticsPrefetchStatus.FAILED, outcome.history)
        assertTrue(operations.savedHistory.isEmpty())
    }

    private fun historyEvent(id: String, occurredAtEpochMillis: Long) = HistoryEventDto(
        eventUuid = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        localDate = "2026-07-18",
        localTime = "13:30:00",
        productUuid = null,
        productId = "product-$id",
        productName = "Product $id",
        productType = "P",
        quantity = 1.0,
        weightCode = null,
        finished = false,
        source = "ANDROID",
    )

    private fun historyResponse(
        events: List<HistoryEventDto>,
        hasMore: Boolean = false,
        filters: HistoryFilters = HistoryFilters(),
        generatedAtEpochMillis: Long,
        sourceDataVersion: String = "a".repeat(64),
    ) = HistoryResponseDto(
        success = true,
        analyticsVersion = 2,
        resource = "history",
        environment = "PRODUCTION",
        timeZone = "America/New_York",
        filters = filters,
        sort = "TIMESTAMP_DESC_CANONICAL_ROW_DESC",
        events = events,
        page = HistoryPageDto(limit = 50, hasMore = hasMore, nextCursor = if (hasMore) "cursor" else null),
        dataQuality = DataQualityDto(complete = true, warnings = QualityWarningsDto()),
        sourceRevision = SourceRevisionDto(
            dataVersion = sourceDataVersion,
            purchaseRowCount = 1,
            eventRowCount = events.size,
        ),
        generatedAtEpochMillis = generatedAtEpochMillis,
        serverDurationMs = 1,
    )

    private fun insightsResponse(
        scope: String = "DEFAULT",
        from: String = "2026-07-01",
        to: String = "2026-07-30",
        generatedAtEpochMillis: Long,
    ) = InsightsResponseDto(
        success = true,
        analyticsVersion = 2,
        resource = "insights",
        environment = "PRODUCTION",
        timeZone = "America/New_York",
        range = AnalyticsRangeDto(scope = scope, from = from, to = to, dayCount = 30),
        overview = OverviewDto(logCount = 1, activeDayCount = 1, distinctProductCount = 1),
        dailyActivity = emptyList(),
        byWeekday = emptyList(),
        byHour = emptyList(),
        inventory = InventoryDto(
            activeCount = 0,
            unopenedCount = 0,
            finishedCount = 0,
            unknownStatusCount = 0,
            currentPersonalOriginalCostCents = 0,
            currentBorrowedRecordedValueCents = 0,
            unknownCurrentCostCount = 0,
        ),
        byType = emptyList(),
        products = emptyList(),
        spending = SpendingDto(allTime = spendBucket(), range = spendBucket(), byMonth = emptyList()),
        syncHealth = SyncHealthDto(
            coverage = "COMPLETE",
            acknowledgedRequestCount30d = 0,
            partialRequestCount30d = 0,
        ),
        dataQuality = DataQualityDto(complete = true, warnings = QualityWarningsDto()),
        sourceRevision = SourceRevisionDto(
            dataVersion = "a".repeat(64),
            purchaseRowCount = 1,
            eventRowCount = 1,
        ),
        generatedAtEpochMillis = generatedAtEpochMillis,
        serverDurationMs = 1,
    )

    private fun spendBucket() = SpendBucketDto(
        personalSpendCents = 0,
        personalPurchaseCount = 0,
        borrowedRecordedValueCents = 0,
        borrowedPurchaseCount = 0,
        unknownPersonalCostCount = 0,
        unknownBorrowedCostCount = 0,
        estimatedDateCount = 0,
        unknownDateCount = 0,
    )

    private class FakeOperations(
        var cachedInsights: InsightsResponseDto? = null,
        var cachedHistory: HistoryResponseDto? = null,
        var freshInsights: InsightsResponseDto? = null,
        var freshHistory: HistoryResponseDto? = null,
        var insightsError: Throwable? = null,
        var historyError: Throwable? = null,
    ) : AnalyticsPrefetchOperations {
        val fetchedInsightsRanges = mutableListOf<InsightsRange>()
        val fetchedHistoryFilters = mutableListOf<HistoryFilters>()
        val savedHistory = mutableListOf<Pair<HistoryFilters, HistoryResponseDto>>()

        override suspend fun readCachedInsights(): InsightsResponseDto? = cachedInsights

        override suspend fun readCachedHistory(): HistoryResponseDto? = cachedHistory

        override suspend fun fetchInsights(range: InsightsRange): InsightsResponseDto {
            fetchedInsightsRanges += range
            insightsError?.let { throw it }
            return requireNotNull(freshInsights)
        }

        override suspend fun fetchHistory(filters: HistoryFilters): HistoryResponseDto {
            fetchedHistoryFilters += filters
            historyError?.let { throw it }
            return requireNotNull(freshHistory)
        }

        override suspend fun saveHistory(filters: HistoryFilters, response: HistoryResponseDto) {
            savedHistory += filters to response
        }
    }
}
