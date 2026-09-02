package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductMappingTest {
    @Test
    fun missingTotalUsesRemainsNullableForOlderServers() {
        val product = gasProduct(totalUses = null).toProductEntity()

        assertEquals(null, product.totalUses)
    }

    @Test
    fun zeroAndFractionalTotalsArePreserved() {
        assertEquals(0.0, gasProduct(totalUses = 0.0).toProductEntity().totalUses ?: -1.0, 0.0)
        assertEquals(3.25, gasProduct(totalUses = 3.25).toProductEntity().totalUses ?: -1.0, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTotalsAreRejected() {
        gasProduct(totalUses = -1.0).toProductEntity()
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteTotalsAreRejected() {
        gasProduct(totalUses = Double.NaN).toProductEntity()
    }

    @Test
    fun postTaxBasisMapsStraightThrough() {
        assertEquals(true, gasProduct(totalUses = null, postTax = true).toProductEntity().postTax)
        assertEquals(false, gasProduct(totalUses = null, postTax = false).toProductEntity().postTax)
    }

    @Test
    fun missingPostTaxBasisRemainsUnknown() {
        // Missing must remain unknown rather than silently becoming pre-tax.
        assertEquals(null, gasProduct(totalUses = null).toProductEntity().postTax)
    }

    private fun gasProduct(totalUses: Double?, postTax: Boolean? = null): GasProduct = GasProduct(
        id = "p1",
        name = "Blue Dream",
        type = "F",
        status = 0,
        postTax = postTax,
        totalUses = totalUses,
    )
}
