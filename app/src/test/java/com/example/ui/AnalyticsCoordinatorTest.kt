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
            awaitState { coordinator.insights.value.isInitialLoading }
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
            assertFalse(coordinator.insights.value.isRefreshing)
            request.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isStale && !coordinator.insights.value.isInitialLoading }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun markStaleDuringColdCacheLoadResultsInExactlyOneAnalyticsFetch() = runBlocking {
        // Regression test for v1.3.3 Fix 5: before the fix, loadInsightsCacheThenRefresh
        // left isInitialLoading and isRefreshing both false until its coroutine actually
        // ran. A markStale() landing in that window passed refreshInsightsIfNeeded's
        // guard and started its own refreshInsights() call, which the cache-load
        // coroutine's own refreshInsights() call then cancelled and replaced --
        // wasting one Apps Script read. The fix claims isRefreshing synchronously
        // before the coroutine is scheduled, so markStale() sees the guard held.
        val cacheGate = CompletableDeferred<Unit>()
        val repository = ControlledAnalyticsDataSource(insightsCacheGate = cacheGate)
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onInsightsVisible()
            coordinator.markStale()

            // Let the cache-load coroutine reach (and suspend on) the gated cache
            // read, and let any refresh markStale() incorrectly started run up to
            // its own network await -- both are ready coroutines at this point.
            repeat(5) { yield() }

            // Unblock the cache read. Before the fix, this is where the cache-load
            // coroutine's own refreshInsights() call cancels-and-replaces the
            // refresh markStale() already started.
            cacheGate.complete(Unit)
            repeat(5) { yield() }

            assertEquals(
                "markStale() arriving during the cache load must not cause a second fetch",
                1,
                repository.insightsFetchCount,
            )

            val request = repository.nextInsightsRequest()
            request.response.complete(insightsResponse(generatedAtEpochMillis = 1_700_000_000_000L))
            awaitState { coordinator.insights.value.data != null }
            assertFalse(repository.hasQueuedInsightsRequest())
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun warmStartOnInsightsScreenEmitsCacheImmediatelyWithoutBlockingLoader() = runBlocking {
        val cached = insightsResponse(
            scope = "DEFAULT",
            generatedAtEpochMillis = 1_700_000_000_000L,
        )
        val repository = ControlledAnalyticsDataSource(cachedInsights = cached)
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onInsightsVisible()

            awaitState {
                coordinator.insights.value.data == cached &&
                    coordinator.insights.value.isFromCache &&
                    coordinator.insights.value.isStale &&
                    !coordinator.insights.value.isInitialLoading &&
                    coordinator.insights.value.isRefreshing
            }
            assertEquals(1, repository.cachedInsightsReadCount)
            assertEquals(1_700_000_000_000L, coordinator.insights.value.lastUpdatedEpochMillis)

            val liveRequest = repository.nextInsightsRequest()
            val fresh = insightsResponse(
                scope = "DEFAULT",
                generatedAtEpochMillis = 1_700_000_001_000L,
            )
            liveRequest.response.complete(fresh)
            awaitState {
                coordinator.insights.value.data == fresh &&
                    !coordinator.insights.value.isFromCache &&
                    !coordinator.insights.value.isStale &&
                    !coordinator.insights.value.isRefreshing &&
                    !coordinator.insights.value.isInitialLoading
            }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun warmStartOnHistoryScreenEmitsCacheImmediatelyWithoutBlockingLoader() = runBlocking {
        val cached = historyResponse(
            eventIds = listOf("cached-1", "cached-2"),
            nextCursor = null,
        )
        val repository = ControlledAnalyticsDataSource(cachedHistory = cached)
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onHistoryVisible()

            awaitState {
                coordinator.history.value.events.map { it.eventUuid } == listOf("cached-1", "cached-2") &&
                    coordinator.history.value.isFromCache &&
                    coordinator.history.value.isStale &&
                    !coordinator.history.value.isInitialLoading &&
                    coordinator.history.value.isRefreshing
            }
            assertEquals(1, repository.cachedHistoryReadCount)

            val liveRequest = repository.nextHistoryRequest()
            val fresh = historyResponse(
                eventIds = listOf("fresh-1"),
                nextCursor = null,
            )
            liveRequest.response.complete(fresh)
            awaitState {
                coordinator.history.value.events.map { it.eventUuid } == listOf("fresh-1") &&
                    !coordinator.history.value.isFromCache &&
                    !coordinator.history.value.isStale &&
                    !coordinator.history.value.isRefreshing &&
                    !coordinator.history.value.isInitialLoading
            }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun coldStartWithEmptyCacheSetsInitialLoadingTrueAndRefreshingFalse() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onInsightsVisible()

            val request = repository.nextInsightsRequest()
            assertTrue(coordinator.insights.value.isInitialLoading)
            assertFalse(coordinator.insights.value.isRefreshing)
            assertEquals(null, coordinator.insights.value.data)

            val fresh = insightsResponse(generatedAtEpochMillis = 1_700_000_000_000L)
            request.response.complete(fresh)
            awaitState {
                coordinator.insights.value.data == fresh &&
                    !coordinator.insights.value.isInitialLoading &&
                    !coordinator.insights.value.isRefreshing &&
                    !coordinator.insights.value.isStale
            }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun coldStartHistoryWithEmptyCacheSetsInitialLoadingTrueAndRefreshingFalse() = runBlocking {
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onHistoryVisible()

            val request = repository.nextHistoryRequest()
            assertTrue(coordinator.history.value.isInitialLoading)
            assertFalse(coordinator.history.value.isRefreshing)
            assertTrue(coordinator.history.value.events.isEmpty())

            val fresh = historyResponse(eventIds = listOf("fresh-1"), nextCursor = null)
            request.response.complete(fresh)
            awaitState {
                coordinator.history.value.events.map { it.eventUuid } == listOf("fresh-1") &&
                    !coordinator.history.value.isInitialLoading &&
                    !coordinator.history.value.isRefreshing &&
                    !coordinator.history.value.isStale
            }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun networkRefreshCompletionClearsRefreshingAndStale() = runBlocking {
        val cached = insightsResponse(generatedAtEpochMillis = 1_700_000_000_000L)
        val repository = ControlledAnalyticsDataSource(cachedInsights = cached)
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onInsightsVisible()
            val request = repository.nextInsightsRequest()
            assertTrue(coordinator.insights.value.isRefreshing)
            assertTrue(coordinator.insights.value.isStale)

            val fresh = insightsResponse(generatedAtEpochMillis = 1_700_000_001_000L)
            request.response.complete(fresh)

            awaitState {
                coordinator.insights.value.data == fresh &&
                    !coordinator.insights.value.isRefreshing &&
                    !coordinator.insights.value.isStale &&
                    !coordinator.insights.value.isFromCache
            }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun networkErrorWithCachedDataPreservesCachedDataAndMarksStale() = runBlocking {
        val cached = insightsResponse(generatedAtEpochMillis = 1_700_000_000_000L)
        val repository = ControlledAnalyticsDataSource(cachedInsights = cached)
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onInsightsVisible()
            val request = repository.nextInsightsRequest()
            assertTrue(coordinator.insights.value.isRefreshing)

            request.response.completeExceptionally(
                AnalyticsApiException("BACKEND_BUSY", "Server busy", retryable = true),
            )

            awaitState {
                coordinator.insights.value.error != null &&
                    !coordinator.insights.value.isRefreshing &&
                    !coordinator.insights.value.isInitialLoading &&
                    coordinator.insights.value.isStale &&
                    coordinator.insights.value.data == cached
            }
            assertEquals("BACKEND_BUSY", coordinator.insights.value.error?.code)
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun networkErrorWithCachedHistoryPreservesCachedEventsAndMarksStale() = runBlocking {
        val cached = historyResponse(eventIds = listOf("cached-1"), nextCursor = null)
        val repository = ControlledAnalyticsDataSource(cachedHistory = cached)
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onHistoryVisible()
            val request = repository.nextHistoryRequest()
            assertTrue(coordinator.history.value.isRefreshing)

            request.response.completeExceptionally(
                AnalyticsApiException("BACKEND_BUSY", "Server busy", retryable = true),
            )

            awaitState {
                coordinator.history.value.error != null &&
                    !coordinator.history.value.isRefreshing &&
                    !coordinator.history.value.isInitialLoading &&
                    coordinator.history.value.isStale &&
                    coordinator.history.value.events.map { it.eventUuid } == listOf("cached-1")
            }
            assertEquals("BACKEND_BUSY", coordinator.history.value.error?.code)
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun runwayRefreshFloorEnforcesTwoMinuteIntervalOnLogScreen() = runBlocking {
        var simulatedNow = 1_000_000L
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope, clock = { simulatedNow })
        try {
            coordinator.onRunwayVisible()
            val request1 = repository.nextInsightsRequest()
            request1.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isRefreshing && !coordinator.insights.value.isStale }

            simulatedNow += 60_000L
            coordinator.markStale()
            yield()
            assertTrue(coordinator.insights.value.isStale)
            assertFalse(coordinator.insights.value.isRefreshing)
            assertFalse(repository.hasQueuedInsightsRequest())

            simulatedNow = 1_000_000L + RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS
            coordinator.markStale()
            val request2 = repository.nextInsightsRequest()
            assertTrue(coordinator.insights.value.isRefreshing)
            request2.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isRefreshing && !coordinator.insights.value.isStale }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun runwayRefreshFloorExactBoundaryCheck() = runBlocking {
        var simulatedNow = 1_000_000L
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope, clock = { simulatedNow })
        try {
            coordinator.onRunwayVisible()
            val request1 = repository.nextInsightsRequest()
            request1.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isRefreshing && !coordinator.insights.value.isStale }

            // At 119,999 ms -> rejected by floor
            simulatedNow = 1_000_000L + RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS - 1L
            coordinator.markStale()
            yield()
            assertTrue(coordinator.insights.value.isStale)
            assertFalse(coordinator.insights.value.isRefreshing)
            assertFalse(repository.hasQueuedInsightsRequest())

            // At exactly 120,000 ms -> accepted by floor
            simulatedNow = 1_000_000L + RUNWAY_ONLY_REFRESH_MIN_INTERVAL_MILLIS
            coordinator.markStale()
            val request2 = repository.nextInsightsRequest()
            assertTrue(coordinator.insights.value.isRefreshing)
            request2.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isRefreshing && !coordinator.insights.value.isStale }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun switchingFromLogToInsightsTabBypassesRunwayFloorImmediately() = runBlocking {
        var simulatedNow = 1_000_000L
        val repository = ControlledAnalyticsDataSource()
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope, clock = { simulatedNow })
        try {
            coordinator.onRunwayVisible()
            val request1 = repository.nextInsightsRequest()
            request1.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isRefreshing }

            // Stale triggered at +30s on Runway screen
            simulatedNow += 30_000L
            coordinator.markStale()
            yield()
            assertFalse(repository.hasQueuedInsightsRequest())

            // Switch to Insights screen -> fires immediate refresh bypassing 120s floor
            coordinator.onInsightsVisible()
            val request2 = repository.nextInsightsRequest()
            assertTrue(coordinator.insights.value.isRefreshing)
            request2.response.complete(insightsResponse(generatedAtEpochMillis = simulatedNow))
            awaitState { !coordinator.insights.value.isRefreshing && !coordinator.insights.value.isStale }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun historyCacheImmediatePresentationBeforeNetwork() = runBlocking {
        val cached = historyResponse(eventIds = listOf("cached-1", "cached-2"), nextCursor = null)
        val repository = ControlledAnalyticsDataSource(cachedHistory = cached)
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onHistoryVisible()

            awaitState {
                coordinator.history.value.events.map { it.eventUuid } == listOf("cached-1", "cached-2") &&
                    coordinator.history.value.isFromCache &&
                    coordinator.history.value.isStale &&
                    !coordinator.history.value.isInitialLoading &&
                    coordinator.history.value.isRefreshing &&
                    !coordinator.history.value.hasFreshCursor
            }
            assertEquals(1, repository.cachedHistoryReadCount)

            val liveRequest = repository.nextHistoryRequest()
            val fresh = historyResponse(eventIds = listOf("fresh-1", "fresh-2"), nextCursor = "cursor-fresh")
            liveRequest.response.complete(fresh)

            awaitState {
                coordinator.history.value.events.map { it.eventUuid } == listOf("fresh-1", "fresh-2") &&
                    !coordinator.history.value.isFromCache &&
                    !coordinator.history.value.isStale &&
                    !coordinator.history.value.isRefreshing &&
                    coordinator.history.value.hasFreshCursor
            }
        } finally {
            coordinatorScope.cancel()
        }
    }

    @Test
    fun offlineNetworkErrorWithCachedDataPreservesCacheWithOfflineErrorCode() = runBlocking {
        val cached = insightsResponse(generatedAtEpochMillis = 1_700_000_000_000L)
        val repository = ControlledAnalyticsDataSource(cachedInsights = cached)
        val coordinatorScope = CoroutineScope(coroutineContext + SupervisorJob())
        val coordinator = AnalyticsCoordinator(repository, coordinatorScope)
        try {
            coordinator.onInsightsVisible()
            val request = repository.nextInsightsRequest()
            assertTrue(coordinator.insights.value.isRefreshing)

            request.response.completeExceptionally(
                AnalyticsApiException("OFFLINE", "Device is offline", retryable = true),
            )

            awaitState {
                coordinator.insights.value.error != null &&
                    !coordinator.insights.value.isRefreshing &&
                    !coordinator.insights.value.isInitialLoading &&
                    coordinator.insights.value.isStale &&
                    coordinator.insights.value.isFromCache &&
                    coordinator.insights.value.data == cached
            }
            assertEquals("OFFLINE", coordinator.insights.value.error?.code)
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
        private val cachedHistory: HistoryResponseDto? = null,
        // When set, readCachedInsights() suspends here before returning, so a
        // test can control exactly when the cache-load coroutine reaches (and
        // completes) its cache read relative to other coordinator calls.
        private val insightsCacheGate: CompletableDeferred<Unit>? = null,
    ) : AnalyticsDataSource {
        private val historyRequests = Channel<HistoryRequest>(Channel.UNLIMITED)
        private val insightsRequests = Channel<InsightsRequest>(Channel.UNLIMITED)
        var cachedInsightsReadCount = 0
            private set
        var cachedHistoryReadCount = 0
            private set
        var insightsFetchCount = 0
            private set

        override suspend fun fetchInsights(range: InsightsRange): InsightsResponseDto {
            insightsFetchCount += 1
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
            insightsCacheGate?.await()
            cachedInsightsReadCount += 1
            return cachedInsights
        }

        override suspend fun readCachedHistory(): HistoryResponseDto? {
            cachedHistoryReadCount += 1
            return cachedHistory
        }

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
