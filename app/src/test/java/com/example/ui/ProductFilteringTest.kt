package com.example.ui

import com.example.data.Product
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductFilteringTest {
    private val products = listOf(
        Product("id-2", "Zulu", "F", 0),
        Product("id-1", "Alpha", "E", 2),
        Product("special-id", "Finished", "F", 1),
    )

    @Test
    fun searchesNameIdAndTypeCaseInsensitively() {
        assertEquals(
            listOf("id-2"),
            filterSelectableProducts(products, true, "zUL", null).map(Product::id),
        )
        assertEquals(
            listOf("id-1"),
            filterSelectableProducts(products, true, "ID-1", null).map(Product::id),
        )
        assertEquals(
            listOf("id-1"),
            filterSelectableProducts(products, true, "e", "E").map(Product::id),
        )
    }

    @Test
    fun excludesFinishedAndOptionallyIncludesUnopened() {
        assertEquals(
            listOf("id-2"),
            filterSelectableProducts(products, false, "", null).map(Product::id),
        )
        assertEquals(
            listOf("id-1", "id-2"),
            filterSelectableProducts(products, true, "", null).map(Product::id),
        )
    }
}

