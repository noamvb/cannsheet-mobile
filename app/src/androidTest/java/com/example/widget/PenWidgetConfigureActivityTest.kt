package com.example.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PenWidgetConfigureActivityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nullOverrideSelectsDefaultAndSavesNull() {
        var saved = false
        var savedStepSeconds: Int? = Int.MIN_VALUE
        composeRule.setContent {
            var selectedStepSeconds by remember {
                mutableStateOf(PenWidgetInstanceConfig.DEFAULT.stepSecondsOverride)
            }
            MyApplicationTheme {
                PenWidgetConfigureScreen(
                    products = emptyList(),
                    selectedProductId = null,
                    discreet = false,
                    stepSeconds = selectedStepSeconds,
                    isLoading = false,
                    isSaving = false,
                    onProductSelected = { },
                    onDiscreetChanged = { },
                    onStepSecondsSelected = { selectedStepSeconds = it },
                    onSave = {
                        saved = true
                        savedStepSeconds = it
                    },
                )
            }
        }

        composeRule.onNodeWithText("Default").assertIsSelected()
        composeRule.onNodeWithText("Save").performClick()

        composeRule.runOnIdle {
            assertTrue(saved)
            assertNull(savedStepSeconds)
        }
    }
}
