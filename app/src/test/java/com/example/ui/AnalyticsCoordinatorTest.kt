package com.example.ui

import com.example.data.AnalyticsApiException
import com.example.data.AnalyticsDataSource
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
    fun coldRunwayVisibilityShowsCacheThenRefreshesTheCachedRange() = runBlocking {
        val cached = insightsResponse(
            scope = "ALL",
            generatedAtEpochMillis = 1_700_000_000_000L,
        )
        val repository = ControlledAnalyticsDataSource(cachedInsights = cached)
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onRunwayVisible()

            awaitState {
                coordinator.insights.value.data == cached &&
                    coordinator.insights.value.isFromCache &&
                    coordinator.insights.value.isStale
            }
            val liveRequest = repository.nextInsightsRequest()

            assertEquals(1, repository.cachedInsightsReadCount)
            assertEquals(InsightsRange.All, liveRequest.range)
            assertTrue(coordinator.insights.value.isRefreshing)

            val fresh = insightsResponse(
                scope = "ALL",
                generatedAtEpochMillis = 1_700_000_001_000L,
            )
            liveRequest.response.complete(fresh)
            awaitState { coordinator.insights.value.data == fresh }

            assertFalse(coordinator.insights.value.isFromCache)
            assertFalse(coordinator.insights.value.isStale)
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun duplicateRunwayVisibleIsIdempotentDuringColdLoad() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onRunwayVisible()
            assertTrue(coordinator.insights.value.isInitialLoading)
            assertTrue(coordinator.insights.value.isStale)
            coordinator.onRunwayVisible()

            val request = repository.nextInsightsRequest()
            yield()

            assertEquals(1, repository.cachedInsightsReadCount)
            assertFalse(repository.hasQueuedInsightsRequest())

            val fresh = insightsResponse(generatedAtEpochMillis = 1_700_000_001_000L)
            request.response.complete(fresh)
            awaitState { coordinator.insights.value.data == fresh }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun hiddenRunwayWaitsWhileStaleThenRefreshesWhenVisibleAgain() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onRunwayVisible()
            val initial = repository.nextInsightsRequest()
            initial.response.complete(insightsResponse(generatedAtEpochMillis = 1L))
            awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 1L }

            coordinator.onRunwayHidden()
            coordinator.markStale()
            yield()

            assertTrue(coordinator.insights.value.isStale)
            assertFalse(repository.hasQueuedInsightsRequest())

            coordinator.onRunwayVisible()
            val refresh = repository.nextInsightsRequest()
            assertEquals(InsightsRange.Default, refresh.range)

            refresh.response.complete(insightsResponse(generatedAtEpochMillis = 2L))
            awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 2L }
            assertFalse(coordinator.insights.value.isStale)
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun acknowledgementDuringAnUnrelatedInsightsRequestCannotPublishThatResponseAsFresh() =
        runBlocking {
            val repository = ControlledAnalyticsDataSource()
            val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
            val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
            try {
                coordinator.onInsightsVisible()
                val preAcknowledgementRequest = repository.nextInsightsRequest()

                coordinator.markStale()
                preAcknowledgementRequest.response.complete(
                    insightsResponse(generatedAtEpochMillis = 1L),
                )
                awaitState {
                    coordinator.insights.value.lastUpdatedEpochMillis == 1L &&
                        coordinator.insights.value.isStale
                }

                val postAcknowledgementRequest = repository.nextInsightsRequest()
                postAcknowledgementRequest.response.complete(
                    insightsResponse(generatedAtEpochMillis = 2L),
                )
                awaitState {
                    coordinator.insights.value.lastUpdatedEpochMillis == 2L &&
                        !coordinator.insights.value.isStale
                }
            } finally {
                coordinatorScope.cancel()
            }
        }

    @Test
    fun invalidatedInsightsRequestDoesNotStartAFollowUpAfterRunwayIsHidden() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onRunwayVisible()
            val invalidatedRequest = repository.nextInsightsRequest()
            coordinator.markStale()
            coordinator.onRunwayHidden()

            invalidatedRequest.response.complete(insightsResponse(generatedAtEpochMillis = 1L))
            awaitState {
                coordinator.insights.value.lastUpdatedEpochMillis == 1L &&
                    coordinator.insights.value.isStale
            }
            yield()
            assertFalse(repository.hasQueuedInsightsRequest())

            coordinator.onRunwayVisible()
            repository.nextInsightsRequest().response.complete(
                insightsResponse(generatedAtEpochMillis = 2L),
            )
            awaitState { !coordinator.insights.value.isStale }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun insightsAndRunwayTransitionOrderingKeepsTheNewConsumerVisible() = runBlocking {
        var simulatedNow = 1_000_000L
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope, clock = { simulatedNow })
        try {
            coordinator.onRunwayVisible()
            repository.nextInsightsRequest().response.complete(
                insightsResponse(generatedAtEpochMillis = 1L),
            )
            awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 1L }

            // Compose may enter the destination before disposing the source.
            coordinator.onInsightsVisible()
            coordinator.onRunwayHidden()
            coordinator.markStale()
            repository.nextInsightsRequest().response.complete(
                insightsResponse(generatedAtEpochMillis = 2L),
            )
            awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 2L }

            // It may also dispose the source first; the destination must still own visibility.
            simulatedNow += RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS
            coordinator.onInsightsHidden()
            coordinator.onRunwayVisible()
            coordinator.markStale()
            repository.nextInsightsRequest().response.complete(
                insightsResponse(generatedAtEpochMillis = 3L),
            )
            awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 3L }

            assertFalse(coordinator.insights.value.isStale)
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun enteringInsightsOverviewDoesNotRestartTheRefreshStartedByVisibility() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onRunwayVisible()
            repository.nextInsightsRequest().response.complete(
                insightsResponse(generatedAtEpochMillis = 1L),
            )
            awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 1L }

            coordinator.onRunwayHidden()
            coordinator.markStale()
            coordinator.onInsightsVisible()
            val refresh = repository.nextInsightsRequest()

            // InsightsScreen reports its selected tab separately from screen visibility.
            coordinator.onOverviewVisible()
            yield()

            assertFalse(repository.hasQueuedInsightsRequest())

            refresh.response.complete(insightsResponse(generatedAtEpochMillis = 2L))
            awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 2L }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun invalidationDuringAnActiveRunwayRefreshRequiresOnePostInvalidationResponse() = runBlocking {
        var simulatedNow = 1_000_000L
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope, clock = { simulatedNow })
        try {
            coordinator.onRunwayVisible()
            repository.nextInsightsRequest().response.complete(
                insightsResponse(generatedAtEpochMillis = 1L),
            )
            awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 1L }

            simulatedNow += RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS
            coordinator.markStale()
            val refresh = repository.nextInsightsRequest()
            coordinator.markStale()
            yield()

            assertFalse(repository.hasQueuedInsightsRequest())

            simulatedNow += RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS
            refresh.response.complete(insightsResponse(generatedAtEpochMillis = 2L))
            awaitState {
                coordinator.insights.value.lastUpdatedEpochMillis == 2L &&
                    coordinator.insights.value.isStale
            }
            val postInvalidationRefresh = repository.nextInsightsRequest()
            assertFalse(repository.hasQueuedInsightsRequest())

            postInvalidationRefresh.response.complete(
                insightsResponse(generatedAtEpochMillis = 3L),
            )
            awaitState {
                coordinator.insights.value.lastUpdatedEpochMillis == 3L &&
                    !coordinator.insights.value.isStale
            }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun visibleHistoryRefreshesHistoryWithoutRefreshingHiddenRunway() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onInsightsVisible()
            repository.nextInsightsRequest().response.complete(
                insightsResponse(generatedAtEpochMillis = 1L),
            )
            awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 1L }

            coordinator.onHistoryVisible()
            repository.nextHistoryRequest().response.complete(
                historyResponse(eventIds = listOf("old"), nextCursor = null),
            )
            awaitState { coordinator.history.value.hasFreshCursor }

            coordinator.markStale()
            val historyRefresh = repository.nextHistoryRequest()
            yield()

            assertFalse(repository.hasQueuedInsightsRequest())
            assertTrue(coordinator.history.value.isRefreshing)

            historyRefresh.response.complete(
                historyResponse(eventIds = listOf("new"), nextCursor = null),
            )
            awaitState { coordinator.history.value.events.singleOrNull()?.eventUuid == "new" }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun acknowledgementDuringAnUnrelatedHistoryRequestCannotPublishThatResponseAsFresh() =
        runBlocking {
            val repository = ControlledAnalyticsDataSource()
            val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
            val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
            try {
                coordinator.onInsightsVisible()
                repository.nextInsightsRequest().response.complete(
                    insightsResponse(generatedAtEpochMillis = 1L),
                )
                awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 1L }

                coordinator.onHistoryVisible()
                val preAcknowledgementRequest = repository.nextHistoryRequest()
                coordinator.markStale()

                preAcknowledgementRequest.response.complete(
                    historyResponse(eventIds = listOf("old"), nextCursor = "old-cursor"),
                )
                awaitState {
                    coordinator.history.value.events.singleOrNull()?.eventUuid == "old" &&
                        coordinator.history.value.isStale &&
                        !coordinator.history.value.hasFreshCursor
                }

                val postAcknowledgementRequest = repository.nextHistoryRequest()
                postAcknowledgementRequest.response.complete(
                    historyResponse(eventIds = listOf("fresh"), nextCursor = null),
                )
                awaitState {
                    coordinator.history.value.events.singleOrNull()?.eventUuid == "fresh" &&
                        !coordinator.history.value.isStale
                }
            } finally {
                coordinatorScope.cancel()
            }
        }

    @Test
    fun invalidatedHistoryRequestDoesNotStartAFollowUpAfterInsightsIsHidden() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onInsightsVisible()
            repository.nextInsightsRequest().response.complete(
                insightsResponse(generatedAtEpochMillis = 1L),
            )
            awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 1L }

            coordinator.onHistoryVisible()
            val invalidatedRequest = repository.nextHistoryRequest()
            coordinator.markStale()
            coordinator.onInsightsHidden()

            invalidatedRequest.response.complete(
                historyResponse(eventIds = listOf("old"), nextCursor = "old-cursor"),
            )
            awaitState {
                coordinator.history.value.events.singleOrNull()?.eventUuid == "old" &&
                    coordinator.history.value.isStale
            }
            yield()
            assertFalse(repository.hasQueuedHistoryRequest())

            coordinator.onInsightsVisible()
            val insightsRefresh = repository.nextInsightsRequest()
            coordinator.onHistoryVisible()
            val historyRefresh = repository.nextHistoryRequest()
            historyRefresh.response.complete(
                historyResponse(eventIds = listOf("fresh"), nextCursor = null),
            )
            awaitState { !coordinator.history.value.isStale }
            insightsRefresh.response.complete(insightsResponse(generatedAtEpochMillis = 2L))
            Unit
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun visibleRunwayStillRefreshesInsightsWhileInsightsHistoryIsVisible() = runBlocking {
        var simulatedNow = 1_000_000L
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope, clock = { simulatedNow })
        try {
            coordinator.onInsightsVisible()
            repository.nextInsightsRequest().response.complete(
                insightsResponse(generatedAtEpochMillis = 1L),
            )
            awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 1L }

            coordinator.onHistoryVisible()
            repository.nextHistoryRequest().response.complete(
                historyResponse(eventIds = listOf("old"), nextCursor = null),
            )
            awaitState { coordinator.history.value.hasFreshCursor }
            coordinator.onRunwayVisible()

            simulatedNow += RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS
            coordinator.markStale()
            val historyRefresh = repository.nextHistoryRequest()
            val insightsRefresh = repository.nextInsightsRequest()

            historyRefresh.response.complete(
                historyResponse(eventIds = listOf("new"), nextCursor = null),
            )
            insightsRefresh.response.complete(insightsResponse(generatedAtEpochMillis = 2L))
            awaitState {
                coordinator.history.value.events.singleOrNull()?.eventUuid == "new" &&
                    coordinator.insights.value.lastUpdatedEpochMillis == 2L
            }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun invalidationDuringAnActiveHistoryRefreshRequiresOnePostInvalidationResponse() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onInsightsVisible()
            repository.nextInsightsRequest().response.complete(
                insightsResponse(generatedAtEpochMillis = 1L),
            )
            awaitState { coordinator.insights.value.lastUpdatedEpochMillis == 1L }

            coordinator.onHistoryVisible()
            repository.nextHistoryRequest().response.complete(
                historyResponse(eventIds = listOf("old"), nextCursor = null),
            )
            awaitState { coordinator.history.value.hasFreshCursor }

            coordinator.markStale()
            val refresh = repository.nextHistoryRequest()
            coordinator.markStale()
            yield()

            assertFalse(repository.hasQueuedHistoryRequest())

            refresh.response.complete(
                historyResponse(eventIds = listOf("new"), nextCursor = null),
            )
            awaitState {
                coordinator.history.value.events.singleOrNull()?.eventUuid == "new" &&
                    coordinator.history.value.isStale
            }
            val postInvalidationRefresh = repository.nextHistoryRequest()
            assertFalse(repository.hasQueuedHistoryRequest())

            postInvalidationRefresh.response.complete(
                historyResponse(eventIds = listOf("fresh"), nextCursor = null),
            )
            awaitState {
                coordinator.history.value.events.singleOrNull()?.eventUuid == "fresh" &&
                    !coordinator.history.value.isStale
            }
        } finally {
            coordinatorScope.cancel()
        }
    }

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

    @Test
    fun openingAnEntryOnAStalePageStartsOneRefresh() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.refreshHistory()
            repository.nextHistoryRequest().response.complete(
                historyResponse(eventIds = listOf("cached"), nextCursor = "cursor"),
            )
            awaitState { coordinator.history.value.hasFreshCursor }

            coordinator.markStale()
            coordinator.refreshHistoryIfNotCurrent()

            val refresh = repository.nextHistoryRequest()
            assertFalse(repository.hasQueuedHistoryRequest())
            refresh.response.complete(historyResponse(eventIds = listOf("fresh"), nextCursor = null))
            awaitState { coordinator.history.value.events.singleOrNull()?.eventUuid == "fresh" }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun refreshHistoryIfNotCurrentIsIgnoredWhenTheSnapshotIsAlreadyCurrent() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.refreshHistory()
            repository.nextHistoryRequest().response.complete(
                historyResponse(eventIds = listOf("current"), nextCursor = null),
            )
            awaitState { coordinator.history.value.hasFreshCursor }

            coordinator.refreshHistoryIfNotCurrent()
            yield()

            assertFalse(repository.hasQueuedHistoryRequest())
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun refreshHistoryIfNotCurrentDoesNotRestartARefreshThatIsAlreadyRunning() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.refreshHistory()
            val initial = repository.nextHistoryRequest()
            initial.response.complete(historyResponse(eventIds = listOf("old"), nextCursor = null))
            awaitState { coordinator.history.value.hasFreshCursor }

            coordinator.refreshHistory()
            val refresh = repository.nextHistoryRequest()
            assertTrue(coordinator.history.value.isRefreshing)

            coordinator.refreshHistoryIfNotCurrent()
            yield()

            assertFalse(repository.hasQueuedHistoryRequest())
            refresh.response.complete(historyResponse(eventIds = listOf("new"), nextCursor = null))
            awaitState { coordinator.history.value.events.singleOrNull()?.eventUuid == "new" }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun markStaleWithOnlyTheLogScreenVisibleSkipsARefreshInsideTheFloor() = runBlocking {
        var simulatedNow = 1_000_000L
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope, clock = { simulatedNow })
        try {
            coordinator.onRunwayVisible()
            val initialRequest = repository.nextInsightsRequest()
            initialRequest.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isStale && !coordinator.insights.value.isRefreshing }

            // Advance clock by 30 seconds (less than 2 minutes floor)
            simulatedNow += 30_000L
            coordinator.markStale()
            yield()

            assertTrue(coordinator.insights.value.isStale)
            assertFalse(coordinator.insights.value.isRefreshing)
            assertFalse(repository.hasQueuedInsightsRequest())
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun markStaleWithOnlyTheLogScreenVisibleRefreshesOnceTheFloorElapses() = runBlocking {
        var simulatedNow = 1_000_000L
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope, clock = { simulatedNow })
        try {
            coordinator.onRunwayVisible()
            val initialRequest = repository.nextInsightsRequest()
            initialRequest.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isStale && !coordinator.insights.value.isRefreshing }

            // Advance clock by exactly RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS (2 minutes)
            simulatedNow += RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS
            coordinator.markStale()

            val secondRequest = repository.nextInsightsRequest()
            assertTrue(coordinator.insights.value.isRefreshing)
            secondRequest.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isStale && !coordinator.insights.value.isRefreshing }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun markStaleWithTheInsightsTabVisibleRefreshesImmediatelyRegardlessOfTheFloor() = runBlocking {
        var simulatedNow = 1_000_000L
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope, clock = { simulatedNow })
        try {
            coordinator.onInsightsVisible()
            val initialRequest = repository.nextInsightsRequest()
            initialRequest.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isStale && !coordinator.insights.value.isRefreshing }

            // Advance clock by just 5 seconds (well inside the 2-minute floor)
            simulatedNow += 5_000L
            coordinator.markStale()

            val secondRequest = repository.nextInsightsRequest()
            assertTrue(coordinator.insights.value.isRefreshing)
            secondRequest.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isStale && !coordinator.insights.value.isRefreshing }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun aBackwardsClockDoesNotWedgeTheRunwayOnlyFloorShut() = runBlocking {
        var simulatedNow = 1_000_000L
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope, clock = { simulatedNow })
        try {
            coordinator.onRunwayVisible()
            val initialRequest = repository.nextInsightsRequest()
            initialRequest.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isStale && !coordinator.insights.value.isRefreshing }

            // Clock moves backward
            simulatedNow = 500_000L
            coordinator.markStale()

            val secondRequest = repository.nextInsightsRequest()
            assertTrue(coordinator.insights.value.isRefreshing)
            secondRequest.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isStale && !coordinator.insights.value.isRefreshing }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun theColdStartLoadStillFetchesOnce() = runBlocking {
        val simulatedNow = 1_000_000L
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope, clock = { simulatedNow })
        try {
            coordinator.onRunwayVisible()

            val request = repository.nextInsightsRequest()
            assertTrue(coordinator.insights.value.isInitialLoading)
            assertTrue(coordinator.insights.value.isStale)
            request.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isStale && !coordinator.insights.value.isInitialLoading }
        } finally {
            coordinatorScope.cancel()
        }
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

    private fun insightsResponse(
        scope: String = "DEFAULT",
        generatedAtEpochMillis: Long,
    ) = InsightsResponseDto(
        success = true,
        analyticsVersion = 2,
        resource = "insights",
        environment = "PRODUCTION",
        timeZone = "America/New_York",
        range = AnalyticsRangeDto(
            scope = scope,
            from = "2026-07-01",
            to = "2026-07-30",
            dayCount = 30,
        ),
        overview = OverviewDto(
            logCount = 1,
            activeDayCount = 1,
            distinctProductCount = 1,
        ),
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
        spending = SpendingDto(
            allTime = spendBucket(),
            range = spendBucket(),
            byMonth = emptyList(),
        ),
        syncHealth = SyncHealthDto(
            coverage = "COMPLETE",
            acknowledgedRequestCount30d = 0,
            partialRequestCount30d = 0,
        ),
        dataQuality = DataQualityDto(
            complete = true,
            warnings = QualityWarningsDto(),
        ),
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

    private data class HistoryRequest(
        val filters: HistoryFilters,
        val cursor: String?,
        val response: CompletableDeferred<HistoryResponseDto>,
    )

    private data class InsightsRequest(
        val range: InsightsRange,
        val response: CompletableDeferred<InsightsResponseDto>,
    )

    private class ControlledAnalyticsDataSource(
        private val cachedInsights: InsightsResponseDto? = null,
    ) : AnalyticsDataSource {
        private val historyRequests = Channel<HistoryRequest>(Channel.UNLIMITED)
        private val insightsRequests = Channel<InsightsRequest>(Channel.UNLIMITED)
        var cachedInsightsReadCount = 0
            private set

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

        override suspend fun readCachedInsights(): InsightsResponseDto? {
            cachedInsightsReadCount += 1
            return cachedInsights
        }

        override suspend fun readCachedHistory(): HistoryResponseDto? = null

        suspend fun nextHistoryRequest(): HistoryRequest =
            withTimeout(2_000) { historyRequests.receive() }

        suspend fun nextInsightsRequest(): InsightsRequest =
            withTimeout(2_000) { insightsRequests.receive() }

        fun hasQueuedHistoryRequest(): Boolean =
            historyRequests.tryReceive().isSuccess

        fun hasQueuedInsightsRequest(): Boolean =
            insightsRequests.tryReceive().isSuccess
    }
}
