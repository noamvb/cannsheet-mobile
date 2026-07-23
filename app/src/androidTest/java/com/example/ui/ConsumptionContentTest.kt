package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.data.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                    onLogBorrowed = { _, _, _, _, _ -> },
                    onFinishWithoutConsumption = {},
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
                    onLogBorrowed = { _, _, _, _, _ -> },
                    onFinishWithoutConsumption = {},
                )
            }
        }

        composeRule.onNode(hasText("4") and hasClickAction()).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals("4", formState.quantityText)
        }
    }

    @Test
    fun finishWithoutConsumptionOnlyCallsCallbackAfterConfirmation() {
        val product = Product("p1", "Blue Dream", "F", 0)
        var finishedProductId: String? = null

        composeRule.setContent {
            MaterialTheme {
                ConsumptionContent(
                    allProducts = listOf(product),
                    recentProducts = emptyList(),
                    quantityPresets = listOf(0.5, 1.0, 2.0),
                    includeUnopened = false,
                    formState = ConsumptionFormState(selectedProductId = product.id),
                    onSelectProduct = {},
                    onQuantityChange = {},
                    onIncludeUnopenedChange = {},
                    onLog = { _, _, _, _, _ -> },
                    onLogBorrowed = { _, _, _, _, _ -> },
                    onFinishWithoutConsumption = { finishedProductId = it },
                )
            }
        }

        composeRule.onNode(
            hasText("Finish without logging consumption") and hasClickAction(),
        ).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(null, finishedProductId)
        }
        composeRule.onNode(hasText("Finish Blue Dream?")).assertIsDisplayed()

        composeRule.onNode(hasText("Finish product") and hasClickAction()).performClick()

        composeRule.runOnIdle {
            assertEquals(product.id, finishedProductId)
        }
    }

    @Test
    fun unopenedProductCanBeFinishedWithoutConsumption() {
        val product = Product("p1", "Blue Dream", "F", 2)

        composeRule.setContent {
            MaterialTheme {
                ConsumptionContent(
                    allProducts = listOf(product),
                    recentProducts = emptyList(),
                    quantityPresets = listOf(0.5, 1.0, 2.0),
                    includeUnopened = true,
                    formState = ConsumptionFormState(selectedProductId = product.id),
                    onSelectProduct = {},
                    onQuantityChange = {},
                    onIncludeUnopenedChange = {},
                    onLog = { _, _, _, _, _ -> },
                    onLogBorrowed = { _, _, _, _, _ -> },
                    onFinishWithoutConsumption = {},
                )
            }
        }

        composeRule.onNode(
            hasText("Finish without logging consumption") and hasClickAction(),
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun cancellingFinishWithoutConsumptionDoesNotCallCallback() {
        val product = Product("p1", "Blue Dream", "F", 0)
        var finishedProductId: String? = null

        composeRule.setContent {
            MaterialTheme {
                ConsumptionContent(
                    allProducts = listOf(product),
                    recentProducts = emptyList(),
                    quantityPresets = listOf(0.5, 1.0, 2.0),
                    includeUnopened = false,
                    formState = ConsumptionFormState(selectedProductId = product.id),
                    onSelectProduct = {},
                    onQuantityChange = {},
                    onIncludeUnopenedChange = {},
                    onLog = { _, _, _, _, _ -> },
                    onLogBorrowed = { _, _, _, _, _ -> },
                    onFinishWithoutConsumption = { finishedProductId = it },
                )
            }
        }

        composeRule.onNode(
            hasText("Finish without logging consumption") and hasClickAction(),
        ).performScrollTo().performClick()
        composeRule.onNode(hasText("Cancel") and hasClickAction()).performClick()

        composeRule.runOnIdle {
            assertEquals(null, finishedProductId)
        }
    }

    @Test
    fun borrowedProductDialogOpensAndCancels() {
        composeRule.setContent {
            MaterialTheme {
                ConsumptionContent(
                    allProducts = emptyList(),
                    recentProducts = emptyList(),
                    quantityPresets = listOf(0.5, 1.0),
                    includeUnopened = false,
                    formState = ConsumptionFormState(quantityText = "1"),
                    onSelectProduct = {},
                    onQuantityChange = {},
                    onIncludeUnopenedChange = {},
                    onLog = { _, _, _, _, _ -> },
                    onLogBorrowed = { _, _, _, _, _ -> },
                    onFinishWithoutConsumption = {},
                )
            }
        }

        composeRule.onNode(hasText("Log a borrowed product") and hasClickAction())
            .performScrollTo()
            .performClick()

        composeRule.onNode(hasText("Product name") and hasSetTextAction()).assertIsDisplayed()
        composeRule.onNode(hasText("Product type") and hasSetTextAction()).assertIsDisplayed()
        composeRule.onNode(hasText("Purchase numbers can remain unknown when logging a borrowed product."))
            .assertIsDisplayed()
        composeRule.onNode(hasText("Cancel") and hasClickAction()).performClick()
        composeRule.onNode(hasText("Product name") and hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun borrowedProductDialogRequiresNameAndType() {
        var callbackCount = 0

        composeRule.setContent {
            MaterialTheme {
                ConsumptionContent(
                    allProducts = emptyList(),
                    recentProducts = emptyList(),
                    quantityPresets = listOf(0.5, 1.0),
                    includeUnopened = false,
                    formState = ConsumptionFormState(quantityText = "1"),
                    onSelectProduct = {},
                    onQuantityChange = {},
                    onIncludeUnopenedChange = {},
                    onLog = { _, _, _, _, _ -> },
                    onLogBorrowed = { _, _, _, _, _ -> callbackCount++ },
                    onFinishWithoutConsumption = {},
                )
            }
        }

        composeRule.onNode(hasText("Log a borrowed product") and hasClickAction())
            .performScrollTo()
            .performClick()
        composeRule.onNode(hasText("Log borrowed product") and hasClickAction()).performClick()

        composeRule.onNode(hasText("Enter both a product name and product type.")).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, callbackCount) }
    }

    @Test
    fun borrowedProductLogUsesCurrentFormQuantityAndDateTime() {
        var loggedDate: String? = null
        var loggedTime: String? = null
        var loggedType: String? = null
        var loggedName: String? = null
        var loggedUses: Double? = null

        composeRule.setContent {
            MaterialTheme {
                ConsumptionContent(
                    allProducts = emptyList(),
                    recentProducts = emptyList(),
                    quantityPresets = listOf(0.5, 1.0),
                    includeUnopened = false,
                    formState = ConsumptionFormState(quantityText = "0.75"),
                    onSelectProduct = {},
                    onQuantityChange = {},
                    onIncludeUnopenedChange = {},
                    onLog = { _, _, _, _, _ -> },
                    onLogBorrowed = { date, time, type, name, uses ->
                        loggedDate = date
                        loggedTime = time
                        loggedType = type
                        loggedName = name
                        loggedUses = uses
                    },
                    onFinishWithoutConsumption = {},
                )
            }
        }

        composeRule.onNode(hasText("Log a borrowed product") and hasClickAction())
            .performScrollTo()
            .performClick()
        composeRule.onNode(hasText("Product name") and hasSetTextAction()).performTextInput("Blue Dream")
        composeRule.onNode(hasText("Product type") and hasSetTextAction()).performTextInput("Flower")
        val expectedBeforeSubmit = currentSubmissionDateTime()
        composeRule.onNode(hasText("Log borrowed product") and hasClickAction()).performClick()

        composeRule.runOnIdle {
            val expectedAfterSubmit = currentSubmissionDateTime()
            assertEquals("Blue Dream", loggedName)
            assertEquals("Flower", loggedType)
            assertEquals(0.75, loggedUses ?: 0.0, 0.0)
            assertTrue(
                (loggedDate == expectedBeforeSubmit.date && loggedTime == expectedBeforeSubmit.time) ||
                    (loggedDate == expectedAfterSubmit.date && loggedTime == expectedAfterSubmit.time),
            )
        }
    }
}

