package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductStatusTest {
    @Test
    fun mapsBackendCodesToHumanStatuses() {
        assertEquals(ProductStatus.ACTIVE, ProductStatus.fromCode(0))
        assertEquals(ProductStatus.FINISHED, ProductStatus.fromCode(1))
        assertEquals(ProductStatus.UNOPENED, ProductStatus.fromCode(2))
        assertEquals(ProductStatus.UNKNOWN, ProductStatus.fromCode(99))
    }

    @Test
    fun onlyActiveAndUnopenedProductsAreSelectable() {
        assertTrue(ProductStatus.ACTIVE.isSelectable)
        assertTrue(ProductStatus.UNOPENED.isSelectable)
        assertFalse(ProductStatus.FINISHED.isSelectable)
        assertFalse(ProductStatus.UNKNOWN.isSelectable)
    }
}

