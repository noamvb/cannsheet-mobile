package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.data.Product
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConsumptionContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recentProductThenLogUsesRememberedQuantity() {
        val product = Product("p1", "Blue Dream", "F", 0)
        var formState by mutableStateOf(ConsumptionFormState())
        var loggedProductId: String? = null
        var loggedQuantity: Double? = null

        composeRule.setContent {
            MaterialTheme {
                ConsumptionContent(
                    allProducts = listOf(product),
                    recentProducts = listOf(RecentProduct(product, 0.75)),
                    quantityPresets = listOf(0.5, 1.0, 2.0),
                    includeUnopened = false,
                    formState = formState,
                    onSelectProduct = {
                        formState = ConsumptionFormState(it, "0.75")
                    },
                    onQuantityChange = {
                        formState = formState.copy(quantityText = it)
                    },
                    onIncludeUnopenedChange = {},
                    onLog = { _, _, productId, quantity, _ ->
                        loggedProductId = productId
                        loggedQuantity = quantity
                    },
                )
            }
        }

        composeRule.onNode(hasText("Blue Dream") and hasClickAction()).performClick()
        composeRule.onNode(hasText("Log Consumption") and hasClickAction()).performClick()

        composeRule.runOnIdle {
            assertEquals("p1", loggedProductId)
            assertEquals(0.75, loggedQuantity ?: 0.0, 0.0)
        }
    }

    @Test
    fun laterQuantityPresetCanBeSelected() {
        val product = Product("p1", "Blue Dream", "F", 0)
        var formState by mutableStateOf(ConsumptionFormState(selectedProductId = "p1"))

        composeRule.setContent {
            MaterialTheme {
                ConsumptionContent(
                    allProducts = listOf(product),
                    recentProducts = emptyList(),
                    quantityPresets = listOf(0.5, 1.0, 2.0, 3.0, 4.0),
                    includeUnopened = false,
                    formState = formState,
                    onSelectProduct = {},
                    onQuantityChange = {
                        formState = formState.copy(quantityText = it)
                    },
                    onIncludeUnopenedChange = {},
                    onLog = { _, _, _, _, _ -> },
                )
            }
        }

        composeRule.onNode(hasText("4") and hasClickAction()).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals("4", formState.quantityText)
        }
    }
}

