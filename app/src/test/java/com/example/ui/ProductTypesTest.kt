package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductTypesTest {
    @Test
    fun emptyCatalogReturnsTheSixCanonicalCodesSorted() {
        assertEquals(listOf("E", "F", "J", "K", "P", "S"), ProductTypes.options(emptyList()))
    }

    @Test
    fun optionsUnionNormalizeDeduplicateAndSortCatalogTypes() {
        assertEquals(
            listOf("E", "F", "J", "K", "P", "S", "X"),
            ProductTypes.options(listOf("x", "p", " F ")),
        )
    }

    @Test
    fun displayNameUsesLabelsForKnownCodesAndRawNormalizedCodeOtherwise() {
        assertEquals("F — Flower", ProductTypes.displayName("F"))
        assertEquals("X", ProductTypes.displayName(" x "))
    }

    @Test
    fun labelUsesHumanNameWithoutDuplicatingTheCode() {
        assertEquals("Pen", ProductTypes.label(" p "))
        assertEquals("X", ProductTypes.label(" x "))
    }

    @Test
    fun purchaseCodesKeepTheirExactOrder() {
        assertEquals(listOf("P", "E", "J", "F", "S", "K"), ProductTypes.CODES)
    }
}
