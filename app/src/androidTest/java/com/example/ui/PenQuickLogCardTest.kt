package com.example.ui

import com.example.domain.PenQuickLogState

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.data.Product
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PenQuickLogCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadedCardShowsDurationChipAndEmitsUses() {
        val product = Product("p1", "Loaded cart", "P", 0)
        var loggedUses: Double? = null

        composeRule.setContent {
            MaterialTheme {
                PenQuickLogCard(
                    state = PenQuickLogState.Loaded(
                        product = product,
                        presetUses = listOf(1.0, 1.5),
                        secondsPerUse = 10.0,
                        syncedUses = null,
                        pendingUses = 0.0,
                    ),
                    onQuickLogPen = { loggedUses = it },
                    onChooseCart = {},
                )
            }
        }

        composeRule.onNode(hasText("15s")).assertIsDisplayed()
        composeRule.onNodeWithTag(PenQuickLogTestTags.quickLogChip(2)).performClick()

        composeRule.runOnIdle { assertEquals(1.5, loggedUses ?: 0.0, 0.0) }
    }

    @Test
    fun noCartLoadedShowsChooseCartButton() {
        composeRule.setContent {
            MaterialTheme {
                PenQuickLogCard(
                    state = PenQuickLogState.NoCartLoaded,
                    onQuickLogPen = {},
                    onChooseCart = {},
                )
            }
        }

        composeRule.onNodeWithTag(PenQuickLogTestTags.CHOOSE_CART).assertIsDisplayed()
    }

    @Test
    fun unavailableDoesNotRenderTheCard() {
        composeRule.setContent {
            MaterialTheme {
                PenQuickLogCard(
                    state = PenQuickLogState.Unavailable,
                    onQuickLogPen = {},
                    onChooseCart = {},
                )
            }
        }

        composeRule.onAllNodes(hasText("Which cart is in the battery?")).assertCountEquals(0)
    }
}
