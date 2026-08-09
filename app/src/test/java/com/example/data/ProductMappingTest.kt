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

    private fun gasProduct(totalUses: Double?): GasProduct = GasProduct(
        id = "p1",
        name = "Blue Dream",
        type = "F",
        status = 0,
        totalUses = totalUses,
    )
}
