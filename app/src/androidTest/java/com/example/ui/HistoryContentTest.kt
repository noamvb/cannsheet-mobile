package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.example.data.DataQualityDto
import com.example.data.HistoryEventDto
import com.example.data.HistoryFilters
import com.example.data.HistoryPageDto
import com.example.data.HistoryResponseDto
import com.example.data.QualityWarningsDto
import com.example.data.SourceRevisionDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HistoryContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun eventOpensReadOnlyTorontoTimeDetails() {
        val event = HistoryEventDto(
            eventUuid = "00000000-0000-4000-8000-000000000001",
            occurredAtEpochMillis = 1_752_851_800_000,
            localDate = "2026-07-18",
            localTime = "13:30:00",
            productUuid = null,
            productId = "*P1",
            productName = "Test Product",
            productType = "P",
            quantity = 1.0,
            weightCode = null,
            finished = false,
            source = "ANDROID",
        )

        composeRule.setContent {
            MaterialTheme {
                HistoryContent(
                    state = HistoryUiState(events = listOf(event)),
                    products = emptyList(),
                    pendingCount = 0,
                    isSyncing = false,
                    onSync = {},
                    onRefresh = {},
                    onLoadMore = {},
                )
            }
        }

        composeRule.onNode(hasText("Test Product") and hasClickAction()).performClick()
        composeRule.onNode(hasText("2026-07-18 13:30:00 · Toronto time")).assertIsDisplayed()
        composeRule.onNode(hasText("Event UUID: ${event.eventUuid}"))
            .assertExists()
    }

    @Test
    fun freshEnabledHistoryShowsCorrectAndVoidActions() {
        val event = HistoryEventDto(
            eventUuid = "00000000-0000-4000-8000-000000000001",
            occurredAtEpochMillis = 1_752_851_800_000,
            localDate = "2026-07-18",
            localTime = "13:30:00",
            productUuid = "00000000-0000-4000-8000-000000000002",
            productId = "*P1",
            productName = "Test Product",
            productType = "P",
            quantity = 1.0,
            weightCode = null,
            finished = false,
            source = "ANDROID",
            lifecycleState = "CORRECTED",
            correctionHeadId = "00000000-0000-4000-8000-000000000003",
            revision = 1,
        )
        val response = HistoryResponseDto(
            success = true,
            analyticsVersion = 2,
            resource = "history",
            environment = "SANDBOX",
            timeZone = "America/New_York",
            filters = HistoryFilters(),
            sort = "TIMESTAMP_DESC_CANONICAL_ROW_DESC",
            events = listOf(event),
            page = HistoryPageDto(50, false),
            dataQuality = DataQualityDto(true, QualityWarningsDto()),
            sourceRevision = SourceRevisionDto("a".repeat(64), 1, 1),
            generatedAtEpochMillis = 1L,
            serverDurationMs = 1L,
            correctionVersion = 1,
            correctionWritesEnabled = true,
        )

        composeRule.setContent {
            MaterialTheme {
                HistoryContent(
                    state = HistoryUiState(
                        events = listOf(event),
                        hasFreshCursor = true,
                        response = response,
                    ),
                    products = emptyList(),
                    pendingCount = 0,
                    isSyncing = false,
                    onSync = {},
                    onRefresh = {},
                    onLoadMore = {},
                )
            }
        }

        composeRule.onNode(hasText("Test Product") and hasClickAction()).performClick()
        composeRule.onNode(hasText("Correction state: CORRECTED"))
            .assertExists()
        composeRule.onNode(hasText("Revision: 1"))
            .assertExists()
        composeRule.onNode(hasText("Correct") and hasClickAction())
            .assertExists()
        composeRule.onNode(hasText("Void") and hasClickAction())
            .assertExists()
    }

    @Test
    fun refreshingHistoryShowsProgressInTheList() {
        val event = testHistoryEvent()

        composeRule.setContent {
            MaterialTheme {
                HistoryContent(
                    state = HistoryUiState(events = listOf(event), isRefreshing = true),
                    products = emptyList(),
                    pendingCount = 0,
                    isSyncing = false,
                    onSync = {},
                    onRefresh = {},
                    onLoadMore = {},
                )
            }
        }

        composeRule.onNode(hasText("Refreshing History…")).assertIsDisplayed()
    }

    @Test
    fun refreshingHistoryReplacesTheSheetButtonWithProgress() {
        val event = testHistoryEvent()

        composeRule.setContent {
            MaterialTheme {
                HistoryContent(
                    state = HistoryUiState(
                        events = listOf(event),
                        isFromCache = true,
                        isRefreshing = true,
                    ),
                    products = emptyList(),
                    pendingCount = 0,
                    isSyncing = false,
                    onSync = {},
                    onRefresh = {},
                    onLoadMore = {},
                )
            }
        }

        composeRule.onNode(hasText("Test Product") and hasClickAction()).performClick()
        composeRule.onAllNodes(hasText("Refreshing History…")).assertCountEquals(2)
        composeRule.onAllNodes(hasText("Refresh History") and hasClickAction())
            .assertCountEquals(0)
    }

    @Test
    fun aRefreshFailureIsVisibleInsideTheEntrySheet() {
        val event = testHistoryEvent()
        val errorText = "No connection. Showing saved data when available. (OFFLINE)"

        composeRule.setContent {
            MaterialTheme {
                HistoryContent(
                    state = HistoryUiState(
                        events = listOf(event),
                        isFromCache = true,
                        error = AnalyticsUiError(
                            code = "OFFLINE",
                            message = "No connection. Showing saved data when available.",
                            retryable = true,
                        ),
                    ),
                    products = emptyList(),
                    pendingCount = 0,
                    isSyncing = false,
                    onSync = {},
                    onRefresh = {},
                    onLoadMore = {},
                )
            }
        }

        composeRule.onNode(hasText("Test Product") and hasClickAction()).performClick()
        composeRule.onAllNodes(hasText(errorText)).assertCountEquals(2)
    }

    @Test
    fun openingAStaleEntryRequestsARefresh() {
        val event = testHistoryEvent()
        var calls = 0

        composeRule.setContent {
            MaterialTheme {
                HistoryContent(
                    state = HistoryUiState(events = listOf(event), isFromCache = true),
                    products = emptyList(),
                    pendingCount = 0,
                    isSyncing = false,
                    onSync = {},
                    onRefresh = {},
                    onLoadMore = {},
                    onRefreshIfNotCurrent = { calls++ },
                )
            }
        }

        composeRule.onNode(hasText("Test Product") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        assertEquals(1, calls)
    }

    @Test
    fun anEntryMissingFromRefreshedHistoryClosesTheSheet() {
        val event = testHistoryEvent()
        val state = mutableStateOf(HistoryUiState(events = listOf(event)))

        composeRule.setContent {
            MaterialTheme {
                HistoryContent(
                    state = state.value,
                    products = emptyList(),
                    pendingCount = 0,
                    isSyncing = false,
                    onSync = {},
                    onRefresh = {},
                    onLoadMore = {},
                )
            }
        }

        composeRule.onNode(hasText("Test Product") and hasClickAction()).performClick()
        composeRule.onNode(hasText("Event UUID: ${event.eventUuid}")).assertExists()

        state.value = state.value.copy(events = emptyList(), isRefreshing = false)
        composeRule.waitForIdle()

        composeRule.onNode(hasText("Entry not in refreshed History")).assertIsDisplayed()
    }

    private fun testHistoryEvent() = HistoryEventDto(
        eventUuid = "00000000-0000-4000-8000-000000000001",
        occurredAtEpochMillis = 1_752_851_800_000,
        localDate = "2026-07-18",
        localTime = "13:30:00",
        productUuid = null,
        productId = "*P1",
        productName = "Test Product",
        productType = "P",
        quantity = 1.0,
        weightCode = null,
        finished = false,
        source = "ANDROID",
    )
}
