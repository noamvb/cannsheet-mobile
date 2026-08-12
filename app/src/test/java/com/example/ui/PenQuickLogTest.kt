package com.example.ui

import com.example.domain.PenQuickLogState

import com.example.data.Product
import com.example.data.ProductInteraction
import com.example.data.ProductTypeKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PenQuickLogTest {
    @Test
    fun explicitSelectablePenWinsOverRecency() {
        val products = listOf(pen("first"), pen("second"))
        val interactions = listOf(
            ProductInteraction("first", 100, 1.0),
            ProductInteraction("second", 200, 2.0),
        )

        assertEquals(
            "first",
            resolveLoadedPenProduct(products, interactions, explicitProductId = "first")?.id,
        )
    }

    @Test
    fun finishedExplicitPenFallsBackToMostRecentSelectablePen() {
        val products = listOf(
            pen("finished", status = 1),
            pen("available"),
        )
        val interactions = listOf(
            ProductInteraction("finished", 500, 1.0),
            ProductInteraction("available", 100, 2.0),
        )

        assertEquals(
            "available",
            resolveLoadedPenProduct(products, interactions, explicitProductId = "finished")?.id,
        )
    }

    @Test
    fun nonPenExplicitProductFallsBackToMostRecentSelectablePen() {
        val products = listOf(pen("pen"), Product("flower", "Flower", "F", 0))
        val interactions = listOf(
            ProductInteraction("pen", 100, 1.0),
            ProductInteraction("flower", 500, 2.0),
        )

        assertEquals(
            "pen",
            resolveLoadedPenProduct(products, interactions, explicitProductId = "flower")?.id,
        )
    }

    @Test
    fun noSelectablePenProductsMakeTheCardUnavailable() {
        assertEquals(
            PenQuickLogState.Unavailable,
            buildPenQuickLogState(
                products = listOf(pen("finished", status = 1)),
                interactions = emptyList(),
                explicitProductId = null,
                globalPresets = listOf(0.5, 1.0, 2.0),
                presetOverrides = emptyMap(),
                secondsPerUseOverrides = emptyMap(),
                pendingUsesByProduct = emptyMap(),
            ),
        )
    }

    @Test
    fun selectablePenProductsWithoutInteractionsHaveNoLoadedCart() {
        assertEquals(
            PenQuickLogState.NoCartLoaded,
            buildPenQuickLogState(
                products = listOf(pen("available")),
                interactions = emptyList(),
                explicitProductId = null,
                globalPresets = listOf(0.5, 1.0, 2.0),
                presetOverrides = emptyMap(),
                secondsPerUseOverrides = emptyMap(),
                pendingUsesByProduct = emptyMap(),
            ),
        )
    }

    @Test
    fun loadedStateUsesPenSpecificPresetsRateAndPendingTotal() {
        val state = buildPenQuickLogState(
            products = listOf(pen("loaded", totalUses = 3.25)),
            interactions = listOf(ProductInteraction("loaded", 100, 1.5)),
            explicitProductId = null,
            globalPresets = listOf(0.5, 1.0, 2.0),
            presetOverrides = mapOf(ProductTypeKey("P") to listOf(1.0, 1.5)),
            secondsPerUseOverrides = mapOf(ProductTypeKey("P") to 10.0),
            pendingUsesByProduct = mapOf("loaded" to 0.5),
        )

        assertEquals(
            PenQuickLogState.Loaded(
                product = pen("loaded", totalUses = 3.25),
                presetUses = listOf(1.0, 1.5),
                secondsPerUse = 10.0,
                syncedUses = 3.25,
                pendingUses = 0.5,
            ),
            state,
        )
    }

    private fun pen(
        id: String,
        status: Int = 0,
        totalUses: Double? = null,
    ) = Product(id, "Pen $id", "p", status, totalUses = totalUses)
}
