package com.example.ui

import com.example.data.AnalyticsApiException
import com.example.data.AnalyticsDataSource
import com.example.data.DataQualityDto
import com.example.data.HistoryEventDto
import com.example.data.HistoryFilters
import com.example.data.HistoryPageDto
import com.example.data.HistoryResponseDto
import com.example.data.InsightsRange
import com.example.data.InsightsResponseDto
import com.example.data.QualityWarningsDto
import com.example.data.SourceRevisionDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsCoordinatorTest {
    @Test
    fun replacingAnInsightsRangeDoesNotShowCancellationAsAnError() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.refreshInsights(InsightsRange.Default)
            repository.nextInsightsRequest()

            coordinator.refreshInsights(InsightsRange.All)
            repository.nextInsightsRequest()
            yield()

            assertEquals(InsightsRange.All, coordinator.insights.value.pendingRange)
            assertEquals(null, coordinator.insights.value.error)
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun loadMoreIsIgnoredWhileFirstPageRefreshIsRunning() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinator = AnalyticsCoordinator(repository, this)

        coordinator.refreshHistory()
        val initial = repository.nextHistoryRequest()
        initial.response.complete(historyResponse(eventIds = listOf("old"), nextCursor = "old-cursor"))
        awaitState { coordinator.history.value.events.singleOrNull()?.eventUuid == "old" }

        coordinator.refreshHistory()
        val refresh = repository.nextHistoryRequest()
        assertTrue(coordinator.history.value.isRefreshing)

        coordinator.loadMoreHistory()
        yield()

        assertFalse(repository.hasQueuedHistoryRequest())
        assertFalse(coordinator.history.value.isLoadingMore)

        refresh.response.complete(historyResponse(eventIds = listOf("new"), nextCursor = "new-cursor"))
        awaitState { coordinator.history.value.events.singleOrNull()?.eventUuid == "new" }
    }

    @Test
    fun staleCursorAutomaticallyRestartsOnlyOnceUntilAnAppendSucceeds() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinator = AnalyticsCoordinator(repository, this)

        coordinator.refreshHistory()
        repository.nextHistoryRequest().response.complete(
            historyResponse(eventIds = listOf("first"), nextCursor = "cursor-1"),
        )
        awaitState { coordinator.history.value.hasFreshCursor }

        coordinator.loadMoreHistory()
        val firstAppend = repository.nextHistoryRequest()
        assertEquals("cursor-1", firstAppend.cursor)
        firstAppend.response.completeExceptionally(staleCursorError())

        val automaticRestart = repository.nextHistoryRequest()
        assertEquals(null, automaticRestart.cursor)
        automaticRestart.response.complete(
            historyResponse(eventIds = listOf("replacement"), nextCursor = "cursor-2"),
        )
        awaitState { coordinator.history.value.nextCursor == "cursor-2" }

        coordinator.loadMoreHistory()
        val secondAppend = repository.nextHistoryRequest()
        assertEquals("cursor-2", secondAppend.cursor)
        secondAppend.response.completeExceptionally(staleCursorError())
        awaitState { coordinator.history.value.appendError?.code == "CURSOR_STALE" }

        assertFalse(repository.hasQueuedHistoryRequest())
        assertEquals(
            "History changed again. Refresh to continue.",
            coordinator.history.value.appendError?.message,
        )
    }

    private suspend fun awaitState(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) yield()
        }
    }

    private fun staleCursorError() = AnalyticsApiException(
        code = "CURSOR_STALE",
        message = "History cursor is stale",
        retryable = true,
    )

    private fun historyResponse(
        eventIds: List<String>,
        nextCursor: String?,
    ) = HistoryResponseDto(
        success = true,
        analyticsVersion = 1,
        resource = "history",
        environment = "PRODUCTION",
        timeZone = "America/New_York",
        filters = HistoryFilters(),
        sort = "TIMESTAMP_DESC_CANONICAL_ROW_DESC",
        events = eventIds.mapIndexed { index, id ->
            HistoryEventDto(
                eventUuid = id,
                occurredAtEpochMillis = 1_700_000_000_000L - index,
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
        },
        page = HistoryPageDto(
            limit = 50,
            hasMore = nextCursor != null,
            nextCursor = nextCursor,
        ),
        dataQuality = DataQualityDto(
            complete = true,
            warnings = QualityWarningsDto(),
        ),
        sourceRevision = SourceRevisionDto(
            dataVersion = "a".repeat(64),
            purchaseRowCount = 1,
            eventRowCount = eventIds.size,
        ),
        generatedAtEpochMillis = 1_700_000_000_000L,
        serverDurationMs = 1,
    )

    private data class HistoryRequest(
        val filters: HistoryFilters,
        val cursor: String?,
        val response: CompletableDeferred<HistoryResponseDto>,
    )

    private data class InsightsRequest(
        val range: InsightsRange,
        val response: CompletableDeferred<InsightsResponseDto>,
    )

    private class ControlledAnalyticsDataSource : AnalyticsDataSource {
        private val historyRequests = Channel<HistoryRequest>(Channel.UNLIMITED)
        private val insightsRequests = Channel<InsightsRequest>(Channel.UNLIMITED)

        override suspend fun fetchInsights(range: InsightsRange): InsightsResponseDto {
            val response = CompletableDeferred<InsightsResponseDto>()
            insightsRequests.send(InsightsRequest(range, response))
            return response.await()
        }

        override suspend fun fetchHistory(
            filters: HistoryFilters,
            cursor: String?,
        ): HistoryResponseDto {
            val response = CompletableDeferred<HistoryResponseDto>()
            historyRequests.send(HistoryRequest(filters, cursor, response))
            return response.await()
        }

        override suspend fun saveHistory(
            filters: HistoryFilters,
            response: HistoryResponseDto,
        ) = Unit

        override suspend fun readCachedInsights(): InsightsResponseDto? = null

        override suspend fun readCachedHistory(): HistoryResponseDto? = null

        suspend fun nextHistoryRequest(): HistoryRequest =
            withTimeout(2_000) { historyRequests.receive() }

        suspend fun nextInsightsRequest(): InsightsRequest =
            withTimeout(2_000) { insightsRequests.receive() }

        fun hasQueuedHistoryRequest(): Boolean =
            historyRequests.tryReceive().isSuccess
    }
}
