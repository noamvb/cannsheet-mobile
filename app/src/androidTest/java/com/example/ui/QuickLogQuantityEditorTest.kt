package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
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
        composeRule.onNodeWithText("Preset 4").performTextInput("4")
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
                    quantityPresets = listOf(0.5, 1.0, 2.0),
                    onSave = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Remove preset 3").performClick()
        composeRule.onNodeWithContentDescription("Remove preset 2").performClick()
        composeRule.onNodeWithContentDescription("Remove preset 1").assertIsNotEnabled()
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
