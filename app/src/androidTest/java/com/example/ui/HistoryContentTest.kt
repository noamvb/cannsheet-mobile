package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.example.data.HistoryEventDto
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
        composeRule.onNode(hasText("Event UUID: ${event.eventUuid}")).assertIsDisplayed()
    }
}
