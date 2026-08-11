package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    QuickLogQuantityEditor(
                        quantityPresets = listOf(0.5, 1.0, 2.0),
                        onSave = {
                            savedPresets = it
                            Result.success(Unit)
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(QuickLogQuantityEditorTestTags.ADD_PRESET).performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(QuickLogQuantityEditorTestTags.presetInput(position = 4))
            .performTextInput("4")
        composeRule.onNodeWithTag(QuickLogQuantityEditorTestTags.SAVE_PRESETS).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(0.5, 1.0, 2.0, 4.0), savedPresets)
        }
    }

    @Test
    fun failedSaveDoesNotShowConfirmation() {
        composeRule.setContent {
            MaterialTheme {
                QuickLogQuantityEditor(
                    quantityPresets = listOf(0.5, 1.0, 2.0),
                    onSave = { Result.failure(IllegalStateException("write failed")) },
                )
            }
        }

        composeRule.onNodeWithTag(QuickLogQuantityEditorTestTags.SAVE_PRESETS).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasText("Quantity presets saved")).assertCountEquals(0)
    }
}
