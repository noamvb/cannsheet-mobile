package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.PurchaseDefaultsState
import com.example.ui.screenshots.ScreenshotTestApplication
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.LEGACY)
@Config(
    application = ScreenshotTestApplication::class,
    sdk = [35],
    qualifiers = "w411dp-h891dp-xxhdpi",
)
class PurchaseRedesignTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun priceBasis_exposesTwoSelectableNodes_withPreTaxSelectedByDefault() {
        setPurchaseContent()

        composeRule.onNodeWithText("PRICE ENTERED AS").assertIsDisplayed()
        composeRule.onAllNodes(isSelectable()).assertCountEquals(2)
        composeRule.onNodeWithTag(PurchaseContentTestTags.PRICE_BASIS_PRE_TAX).assertIsSelected()
        composeRule.onNodeWithTag(PurchaseContentTestTags.PRICE_BASIS_POST_TAX).assertIsNotSelected()
    }

    @Test
    fun savePurchase_isDisplayedWithoutScrolling_atCompactPhoneSize() {
        setPurchaseContent()

        composeRule.onNodeWithTag(PurchaseContentTestTags.SUBMIT)
            .assertTextEquals("Save purchase")
            .assertIsDisplayed()
    }

    @Test
    fun taxPreview_showsEstimatedTotalWithTax_whenPreTaxCostEntered() {
        setPurchaseContent(taxRate = 0.14)

        composeRule.onNodeWithTag(PurchaseContentTestTags.COST).performTextInput("32")

        composeRule.onNodeWithTag(PurchaseContentTestTags.COST_TAX_PREVIEW)
            .assertIsDisplayed()
            .assertTextContains("Estimated total with tax · $36.48")
    }

    @Test
    fun scanButton_exists_andInvokesScanCallback() {
        var scanInvoked = false
        setPurchaseContent(onScanRequested = { scanInvoked = true })

        composeRule.onNodeWithText("LEDGER / PURCHASE").assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseContentTestTags.SCAN)
            .assertIsDisplayed()
            .performClick()

        assertTrue(scanInvoked)
    }

    private fun setPurchaseContent(
        taxRate: Double? = null,
        onScanRequested: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                var formState by remember { mutableStateOf(PurchaseFormState.initial()) }
                PurchaseContent(
                    products = emptyList(),
                    purchaseDefaultsState = PurchaseDefaultsState.Loaded(emptyMap()),
                    formState = formState,
                    taxRate = taxRate,
                    onFormChange = { formState = it },
                    onQueuePurchase = {},
                    onScanRequested = onScanRequested,
                )
            }
        }
    }
}
