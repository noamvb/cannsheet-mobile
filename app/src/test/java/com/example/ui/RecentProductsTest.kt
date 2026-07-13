package com.example.ui

import com.example.data.Product
import com.example.data.ProductInteraction
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentProductsTest {
    private val active = Product("active", "Active product", "F", 0)
    private val unopened = Product("unopened", "Unopened product", "E", 2)
    private val finished = Product("finished", "Finished product", "P", 1)

    @Test
    fun sortsNewestFirstAndExcludesUnavailableProducts() {
        val result = buildRecentProducts(
            products = listOf(active, unopened, finished),
            interactions = listOf(
                ProductInteraction("active", 100, 1.0),
                ProductInteraction("unopened", 300, 0.5),
                ProductInteraction("finished", 400, 2.0),
                ProductInteraction("missing", 500, 9.0),
            ),
            includeUnopened = true,
        )

        assertEquals(listOf("unopened", "active"), result.map { it.product.id })
        assertEquals(0.5, result.first().lastQuantity, 0.0)
    }

    @Test
    fun hidesUnopenedProductsUntilEnabledAndHonorsLimit() {
        val hidden = buildRecentProducts(
            products = listOf(active, unopened),
            interactions = listOf(
                ProductInteraction("active", 100, 1.0),
                ProductInteraction("unopened", 200, 0.5),
            ),
            includeUnopened = false,
            limit = 1,
        )

        assertEquals(listOf("active"), hidden.map { it.product.id })
    }
}

