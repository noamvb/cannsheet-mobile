package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.data.Product
import com.example.data.PurchaseDefaultKey
import com.example.data.PurchaseDefaultValues
import com.example.data.PurchaseDefaultsState
import com.example.data.PurchaseSubmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class PurchaseContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun requiresTypeBeforeShowingNormalizedDeduplicatedSuggestions() {
        val products = listOf(
            Product("1", "Zeta", "P", 0),
            Product("2", " alpha ", "p", 1),
            Product("3", "Alpha", "P", 2),
            Product("4", "Beta", "P", 0),
            Product("5", "Gamma", "P", 0),
            Product("6", "Delta", "P", 0),
            Product("7", "Epsilon", "P", 0),
            Product("8", "Wrong type", "E", 0),
        )

        setPurchaseContent(products = products)

        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).assertIsNotEnabled()
        composeRule.onNodeWithTag(PurchaseContentTestTags.SUGGESTIONS).assertDoesNotExist()

        selectType("P")
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).performClick()

        composeRule.onNodeWithTag(PurchaseContentTestTags.suggestion("Alpha")).assertExists()
        composeRule.onNodeWithTag(PurchaseContentTestTags.suggestion("Beta")).assertExists()
        composeRule.onNodeWithTag(PurchaseContentTestTags.suggestion("Epsilon")).assertExists()
        composeRule.onNodeWithTag(PurchaseContentTestTags.suggestion("Gamma")).assertExists()
        composeRule.onNodeWithTag(PurchaseContentTestTags.suggestion("Zeta")).assertDoesNotExist()
        composeRule.onNodeWithTag(PurchaseContentTestTags.suggestion("Wrong type")).assertDoesNotExist()
    }

    @Test
    fun suggestionsStayHiddenUntilDefaultsStateIsLoaded() {
        val product = Product("p1", "Blue Dream", "P", 0)

        setPurchaseContent(
            products = listOf(product),
            purchaseDefaultsState = PurchaseDefaultsState.NotLoaded,
        )
        selectType("P")
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).performClick()

        composeRule.onNodeWithTag(PurchaseContentTestTags.SUGGESTIONS).assertDoesNotExist()
    }

    @Test
    fun typingMatchingNameDoesNotAutofillUntilSuggestionIsSelected() {
        val product = Product("p1", "Blue Dream", "P", 0, cost = 15.0, thc = 0.25, grams = 3.5)

        setPurchaseContent(products = listOf(product))
        selectType("P")
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).performTextInput("Blue Dream")
        composeRule.onNodeWithTag(PurchaseContentTestTags.suggestion("Blue Dream")).assertExists()

        composeRule.onNodeWithTag(PurchaseContentTestTags.SUBMIT).performScrollTo().performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.VALIDATION_ERROR).assertIsDisplayed()
    }

    @Test
    fun selectedSuggestionPrefersCompleteSavedDefaultAndFormatsCanonicalThcPercent() {
        val product = Product("p1", "Blue Dream", "P", 0, cost = 15.0, thc = 0.25, grams = 3.5)
        val defaults = mapOf(
            PurchaseDefaultKey(" blue dream ", " p ") to PurchaseDefaultValues(
            cost = 42.0,
            thc = 0.2345,
            grams = 7.0,
            ),
        )

        setPurchaseContent(
            products = listOf(product),
            purchaseDefaultsState = PurchaseDefaultsState.Loaded(defaults),
        )
        selectType("P")
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.suggestion("Blue Dream")).performClick()

        composeRule.onNodeWithTag(PurchaseContentTestTags.APPLIED_AUTOFILL).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseContentTestTags.COST).assertTextContains("42")
        composeRule.onNodeWithTag(PurchaseContentTestTags.THC).assertTextContains("23.45")
        composeRule.onNodeWithTag(PurchaseContentTestTags.GRAMS).assertTextContains("7")
        composeRule.onNodeWithTag(PurchaseContentTestTags.BORROWED).assertIsOff()
        composeRule.onNodeWithTag(PurchaseContentTestTags.PRICE_BASIS_PRE_TAX).assertIsSelected()
        composeRule.onNodeWithTag(PurchaseContentTestTags.SAVE_AS_DEFAULT).assertIsOff()
    }

    @Test
    fun sameNameUsesDefaultsForTheSelectedTypeOnly() {
        val flower = Product("flower", "Same Name", "P", 0, cost = 11.0, grams = 1.0)
        val edible = Product("edible", "Same Name", "E", 0, cost = 22.0, grams = 2.0)
        val defaults = mapOf(
            PurchaseDefaultKey("Same Name", "P") to PurchaseDefaultValues(99.0, 0.2, 9.0),
        )

        setPurchaseContent(
            products = listOf(flower, edible),
            purchaseDefaultsState = PurchaseDefaultsState.Loaded(defaults),
        )
        selectType("P")
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.suggestion("Same Name")).performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.COST).assertTextContains("99")

        selectType("E")
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.suggestion("Same Name")).performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.COST).assertTextContains("22")
        composeRule.onNodeWithTag(PurchaseContentTestTags.APPLIED_AUTOFILL).assertDoesNotExist()
    }

    @Test
    fun catalogFallbackConvertsFractionAndRejectsInvalidThc() {
        val fraction = Product("fraction", "Fraction", "P", 0, cost = 12.0, thc = 0.25, grams = 3.5)
        val percent = Product("percent", "Percent", "P", 0, cost = 12.0, thc = 25.0, grams = 3.5)
        val invalid = Product("invalid", "Invalid", "P", 0, cost = 12.0, thc = 101.0, grams = 3.5)

        assertEquals(25.0, checkNotNull(catalogThcPercent(fraction.thc)), 0.0)
        assertEquals(25.0, checkNotNull(catalogThcPercent(percent.thc)), 0.0)
        assertNull(catalogThcPercent(invalid.thc))

        setPurchaseContent(products = listOf(fraction, percent, invalid))
        selectType("P")
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.suggestion("Fraction")).performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.THC).assertTextContains("25")
        composeRule.onNodeWithTag(PurchaseContentTestTags.APPLIED_AUTOFILL).assertDoesNotExist()
    }

    @Test
    fun changingTypeClearsFieldsButReselectingTypePreservesThem() {
        val product = Product("p1", "Blue Dream", "P", 0)
        val defaults = mapOf(
            PurchaseDefaultKey("Blue Dream", "P") to PurchaseDefaultValues(10.0, 0.2, 3.5),
        )

        setPurchaseContent(
            products = listOf(product),
            purchaseDefaultsState = PurchaseDefaultsState.Loaded(defaults),
        )
        selectType("P")
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.suggestion("Blue Dream")).performClick()

        selectType("P")
        composeRule.onNodeWithTag(PurchaseContentTestTags.APPLIED_AUTOFILL).assertExists()
        composeRule.onNodeWithTag(PurchaseContentTestTags.COST).assertTextContains("10")

        selectType("E")
        composeRule.onNodeWithTag(PurchaseContentTestTags.APPLIED_AUTOFILL).assertDoesNotExist()
        composeRule.onNodeWithTag(PurchaseContentTestTags.BORROWED).assertIsOff()
        composeRule.onNodeWithTag(PurchaseContentTestTags.PRICE_BASIS_PRE_TAX).assertIsSelected()
    }

    @Test
    fun scannedBarcodeSurvivesChoosingATypeAndIsShownAsAttached() {
        setPurchaseContent(
            initialFormState = PurchaseFormState.initial().copy(
                appliedAutofillMessage =
                    "New product. Fill this in and the barcode will be remembered.",
                pendingScanGtin = "00840773004481",
                pendingScanBatch = "26070000162",
            ),
        )

        selectType("F")

        composeRule.onNodeWithTag(PurchaseContentTestTags.SCAN_ATTACHED).assertIsDisplayed()
    }

    @Test
    fun removingAnAttachedBarcodeDetachesItWithoutClearingTheForm() {
        setPurchaseContent(
            initialFormState = PurchaseFormState.initial().copy(
                type = "F",
                name = "Entered Product",
                appliedAutofillMessage = null,
                pendingScanGtin = "00840773004481",
                pendingScanBatch = "26070000162",
            ),
        )

        composeRule.onNodeWithTag(PurchaseContentTestTags.SCAN_ATTACHED).assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseContentTestTags.SCAN_DETACH).performScrollTo().performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.SCAN_ATTACHED).assertDoesNotExist()
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).assertTextContains("Entered Product")
    }

    @Test
    fun submittingClearsTheAttachedBarcode() {
        var submission: PurchaseSubmission? = null

        setPurchaseContent(
            onQueuePurchase = { submission = it },
            initialFormState = PurchaseFormState.initial().copy(
                appliedAutofillMessage =
                    "New product. Fill this in and the barcode will be remembered.",
                pendingScanGtin = "00840773004481",
                pendingScanBatch = "26070000162",
            ),
        )
        selectType("F")
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).performTextInput("New Product")
        composeRule.onNodeWithTag(PurchaseContentTestTags.COST).performTextInput("12.5")
        composeRule.onNodeWithTag(PurchaseContentTestTags.GRAMS).performTextInput("3.5")

        composeRule.onNodeWithTag(PurchaseContentTestTags.SUBMIT).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals("F", submission?.type)
            assertEquals("New Product", submission?.name)
        }
        composeRule.onNodeWithTag(PurchaseContentTestTags.SCAN_ATTACHED).assertDoesNotExist()
    }

    @Test
    fun blankThcSubmitsImmutablePayloadAndImmediatelyResetsScrollableForm() {
        var submission: PurchaseSubmission? = null

        setPurchaseContent(onQueuePurchase = { submission = it })
        selectType("P")
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).performTextInput("New Product")
        composeRule.onNodeWithTag(PurchaseContentTestTags.COST).performTextInput("12.5")
        composeRule.onNodeWithTag(PurchaseContentTestTags.GRAMS).performTextInput("3.5")

        composeRule.onNodeWithTag(PurchaseContentTestTags.SUBMIT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(PurchaseContentTestTags.SUBMIT).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals("P", submission?.type)
            assertEquals("New Product", submission?.name)
            assertEquals(0.0, submission?.thc)
            assertEquals(false, submission?.saveAsDefault)
        }
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).assertIsNotEnabled()
        composeRule.onNodeWithTag(PurchaseContentTestTags.BORROWED).assertIsOff()
    }

    @Test
    fun saveAsDefaultAllowsZeroCostAndZeroThcOnlyWhenThcIsExplicit() {
        var submission: PurchaseSubmission? = null

        setPurchaseContent(onQueuePurchase = { submission = it })
        selectType("P")
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).performTextInput("Defaultable")
        composeRule.onNodeWithTag(PurchaseContentTestTags.COST).performTextInput("0")
        composeRule.onNodeWithTag(PurchaseContentTestTags.THC).performTextInput("0")
        composeRule.onNodeWithTag(PurchaseContentTestTags.GRAMS).performTextInput("1")
        composeRule.onNodeWithTag(PurchaseContentTestTags.SAVE_AS_DEFAULT).performScrollTo().performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.SAVE_AS_DEFAULT).assertIsOn()
        composeRule.onNodeWithTag(PurchaseContentTestTags.SUBMIT).performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(0.0, submission?.cost)
            assertEquals(0.0, submission?.thc)
            assertEquals(true, submission?.saveAsDefault)
        }
    }

    @Test
    fun invalidCheckedSubmissionDoesNotQueue() {
        var submission: PurchaseSubmission? = null

        setPurchaseContent(onQueuePurchase = { submission = it })
        selectType("P")
        composeRule.onNodeWithTag(PurchaseContentTestTags.NAME).performTextInput("Defaultable")
        composeRule.onNodeWithTag(PurchaseContentTestTags.COST).performTextInput("10")
        composeRule.onNodeWithTag(PurchaseContentTestTags.GRAMS).performTextInput("1")
        composeRule.onNodeWithTag(PurchaseContentTestTags.SAVE_AS_DEFAULT).performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.SUBMIT).performScrollTo().performClick()

        composeRule.runOnIdle { assertNull(submission) }
        composeRule.onNodeWithTag(PurchaseContentTestTags.VALIDATION_ERROR).assertIsDisplayed()
    }

    @Test
    fun costTaxPreviewFollowsSelectedPriceBasisWithoutChangingCost() {
        setPurchaseContent(taxRate = 0.13)

        composeRule.onNodeWithTag(PurchaseContentTestTags.COST).performTextInput("50")
        // The supporting text merges into the field's semantics node, so it is only
        // addressable on its own in the unmerged tree.
        composeRule.onNodeWithTag(PurchaseContentTestTags.COST_TAX_PREVIEW, useUnmergedTree = true)
            .assertTextEquals("\$56.50 with 13% tax")

        composeRule.onNodeWithTag(PurchaseContentTestTags.PRICE_BASIS_POST_TAX).performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.COST).assertTextContains("50")
        composeRule.onNodeWithTag(PurchaseContentTestTags.COST_TAX_PREVIEW, useUnmergedTree = true)
            .assertTextEquals("\$44.25 before 13% tax")
    }

    private fun setPurchaseContent(
        products: List<Product> = emptyList(),
        purchaseDefaultsState: PurchaseDefaultsState = PurchaseDefaultsState.Loaded(emptyMap()),
        onQueuePurchase: (PurchaseSubmission) -> Unit = {},
        initialFormState: PurchaseFormState = PurchaseFormState.initial(),
        taxRate: Double? = null,
    ) {
        composeRule.setContent {
            MaterialTheme {
                var formState by remember { mutableStateOf(initialFormState) }
                PurchaseContent(
                    products = products,
                    purchaseDefaultsState = purchaseDefaultsState,
                    formState = formState,
                    taxRate = taxRate,
                    onFormChange = { formState = it },
                    onQueuePurchase = onQueuePurchase,
                )
            }
        }
    }

    private fun selectType(type: String) {
        composeRule.onNodeWithTag(PurchaseContentTestTags.TYPE).performClick()
        composeRule.onNodeWithTag(PurchaseContentTestTags.typeOption(type)).performClick()
    }
}
