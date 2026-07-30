package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.data.DataQualityDto
import com.example.data.HistoryEventDto
import com.example.data.HistoryFilters
import com.example.data.HistoryPageDto
import com.example.data.HistoryResponseDto
import com.example.data.QualityWarningsDto
import com.example.data.SourceRevisionDto
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
            .performScrollTo()
            .assertIsDisplayed()
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
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNode(hasText("Revision: 1"))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNode(hasText("Correct"))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNode(hasText("Void"))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
