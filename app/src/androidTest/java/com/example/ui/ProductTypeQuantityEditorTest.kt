package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.example.data.ProductTypeKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProductTypeQuantityEditorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectingTypeEditingPresetAndSavingCapturesTypeAndValues() {
        var savedType: String? = null
        var savedPresets: List<Double>? = null

        composeRule.setContent {
            MaterialTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ProductTypeQuantityEditor(
                        productTypes = listOf("P", "S"),
                        globalPresets = listOf(0.5, 1.0, 2.0),
                        overrides = emptyMap(),
                        onSave = { type, presets ->
                            savedType = type
                            savedPresets = presets
                        },
                        onReset = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(ProductTypeQuantityEditorTestTags.TYPE_PICKER).performClick()
        composeRule.onNodeWithTag(ProductTypeQuantityEditorTestTags.typeOption("S")).performClick()
        composeRule
            .onNodeWithTag(ProductTypeQuantityEditorTestTags.presetInput(1))
            .performTextReplacement("0.1")
        composeRule.onNodeWithTag(ProductTypeQuantityEditorTestTags.SAVE_PRESETS).performClick()

        composeRule.runOnIdle {
            assertEquals("S", savedType)
            assertEquals(listOf(0.1, 1.0, 2.0), savedPresets)
        }
    }

    @Test
    fun switchingTypeReseedsTheInputFields() {
        composeRule.setContent {
            MaterialTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ProductTypeQuantityEditor(
                        productTypes = listOf("P", "S"),
                        globalPresets = listOf(0.5, 1.0, 2.0),
                        overrides = mapOf(ProductTypeKey("P") to listOf(7.0)),
                        onSave = { _, _ -> },
                        onReset = {},
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(ProductTypeQuantityEditorTestTags.presetInput(1))
            .assertEditableTextEquals("7")
        composeRule.onNodeWithTag(ProductTypeQuantityEditorTestTags.TYPE_PICKER).performClick()
        composeRule.onNodeWithTag(ProductTypeQuantityEditorTestTags.typeOption("S")).performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(ProductTypeQuantityEditorTestTags.presetInput(1))
            .assertEditableTextEquals("0.5")
        composeRule.onNodeWithTag(ProductTypeQuantityEditorTestTags.TYPE_PICKER).performClick()
        composeRule.onNodeWithTag(ProductTypeQuantityEditorTestTags.typeOption("P")).performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(ProductTypeQuantityEditorTestTags.presetInput(1))
            .assertEditableTextEquals("7")
    }

    @Test
    fun resetIsDisabledWithoutOverrideAndCallsBackForCustomType() {
        var resetType: String? = null
        composeRule.setContent {
            MaterialTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ProductTypeQuantityEditor(
                        productTypes = listOf("P", "S"),
                        globalPresets = listOf(0.5, 1.0, 2.0),
                        overrides = mapOf(ProductTypeKey("S") to listOf(0.1, 0.25)),
                        onSave = { _, _ -> },
                        onReset = { resetType = it },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(ProductTypeQuantityEditorTestTags.RESET).assertIsNotEnabled()
        composeRule.onNodeWithTag(ProductTypeQuantityEditorTestTags.TYPE_PICKER).performClick()
        composeRule.onNodeWithTag(ProductTypeQuantityEditorTestTags.typeOption("S")).performClick()
        composeRule.onNodeWithTag(ProductTypeQuantityEditorTestTags.RESET).assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals("S", resetType) }
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertEditableTextEquals(
    expected: String,
) = assert(
    SemanticsMatcher.expectValue(
        SemanticsProperties.EditableText,
        AnnotatedString(expected),
    ),
)
