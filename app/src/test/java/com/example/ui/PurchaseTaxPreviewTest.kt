package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PurchaseTaxPreviewTest {
    @Test
    fun preTaxCostShowsTaxIncludedAmount() {
        assertEquals(
            "Estimated total with tax · \$56.50",
            purchaseTaxPreview(cost = "50", postTax = false, taxRate = 0.13),
        )
    }

    @Test
    fun postTaxCostShowsPreTaxAmount() {
        assertEquals(
            "Estimated total before tax · \$50.00",
            purchaseTaxPreview(cost = "56.50", postTax = true, taxRate = 0.13),
        )
        assertEquals(
            "Estimated total before tax · \$44.25",
            purchaseTaxPreview(cost = "50", postTax = true, taxRate = 0.13),
        )
    }

    @Test
    fun fractionalRateOmitsTrailingZero() {
        assertEquals(
            "Estimated total with tax · \$56.25",
            purchaseTaxPreview(cost = "50", postTax = false, taxRate = 0.125),
        )
    }

    @Test
    fun missingOrInvalidRateShowsUnavailableMessage() {
        listOf(null, 1.0, -0.01, Double.NaN).forEach { taxRate ->
            assertEquals(
                "Tax rate not synced yet",
                purchaseTaxPreview(cost = "50", postTax = false, taxRate = taxRate),
            )
        }
    }

    @Test
    fun invalidCostHasNoPreview() {
        listOf("", "abc", "-1").forEach { cost ->
            assertNull(purchaseTaxPreview(cost = cost, postTax = false, taxRate = 0.13))
        }
    }

    @Test
    fun zeroCostHasPreview() {
        assertNotNull(purchaseTaxPreview(cost = "0", postTax = false, taxRate = 0.13))
    }

    @Test
    fun absurdCostHasNoPreview() {
        assertNull(purchaseTaxPreview(cost = "999999999", postTax = false, taxRate = 0.13))
    }
}
