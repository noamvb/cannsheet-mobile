package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QuickLogQuantityEditorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addsFourthPresetAndSavesValuesInOrder() {
        var savedPresets: List<Double>? = null

        composeRule.setContent {
            MaterialTheme {
                QuickLogQuantityEditor(
                    quantityPresets = listOf(0.5, 1.0, 2.0),
                    onSave = { savedPresets = it },
                )
            }
        }

        composeRule.onNodeWithText("Add preset").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction() and hasText("Preset 4")).performTextInput("4")
        composeRule.onNodeWithText("Save quantity presets").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(0.5, 1.0, 2.0, 4.0), savedPresets)
        }
    }

    @Test
    fun keepsAtLeastOnePreset() {
        composeRule.setContent {
            MaterialTheme {
                QuickLogQuantityEditor(
                    quantityPresets = listOf(0.5),
                    onSave = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Remove preset 1", useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun stopsAddingAtTenPresets() {
        composeRule.setContent {
            MaterialTheme {
                QuickLogQuantityEditor(
                    quantityPresets = (1..10).map { it.toDouble() },
                    onSave = {},
                )
            }
        }

        composeRule.onNodeWithText("Add preset").assertIsNotEnabled()
    }
}
