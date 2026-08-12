package com.example.ui

import com.example.data.Product
import com.example.data.ProductInteraction
import com.example.data.productStatus
import com.example.data.ConsumptionPreferencesRepository
import com.example.data.ProductTypeKey

sealed interface PenQuickLogState {
    /** No selectable pen product exists; the card is hidden entirely. */
    data object Unavailable : PenQuickLogState

    /** Pen products exist but none is known to be in the battery. */
    data object NoCartLoaded : PenQuickLogState

    data class Loaded(
        val product: Product,
        val presetUses: List<Double>,
        val secondsPerUse: Double?,
        val syncedUses: Double?,
        val pendingUses: Double,
    ) : PenQuickLogState
}

/**
 * The cart in the battery. Only one pen product can be loaded at a time, so an explicit
 * choice wins and the most recently logged pen product is the fallback.
 */
internal fun resolveLoadedPenProduct(
    products: List<Product>,
    interactions: List<ProductInteraction>,
    explicitProductId: String?,
): Product? {
    val selectablePens = products.filter { product ->
        product.productStatus.isSelectable &&
            ProductTypes.normalize(product.type) == ProductTypes.PEN
    }

    explicitProductId?.let { productId ->
        selectablePens.firstOrNull { it.id == productId }?.let { return it }
    }

    val interactionsByProduct = interactions.associateBy(ProductInteraction::productId)
    return selectablePens
        .mapNotNull { product ->
            interactionsByProduct[product.id]?.let { interaction ->
                product to interaction.lastLoggedAtEpochMillis
            }
        }
        .maxByOrNull { (_, lastLoggedAt) -> lastLoggedAt }
        ?.first
}

internal fun buildPenQuickLogState(
    products: List<Product>,
    interactions: List<ProductInteraction>,
    explicitProductId: String?,
    globalPresets: List<Double>,
    presetOverrides: Map<ProductTypeKey, List<Double>>,
    secondsPerUseOverrides: Map<ProductTypeKey, Double>,
    pendingUsesByProduct: Map<String, Double>,
): PenQuickLogState {
    val selectablePenExists = products.any { product ->
        product.productStatus.isSelectable &&
            ProductTypes.normalize(product.type) == ProductTypes.PEN
    }
    if (!selectablePenExists) return PenQuickLogState.Unavailable

    val loaded = resolveLoadedPenProduct(products, interactions, explicitProductId)
        ?: return PenQuickLogState.NoCartLoaded

    return PenQuickLogState.Loaded(
        product = loaded,
        presetUses = ConsumptionPreferencesRepository.effectiveQuantityPresets(
            globalPresets = globalPresets,
            overrides = presetOverrides,
            productType = ProductTypes.PEN,
        ),
        secondsPerUse = ConsumptionPreferencesRepository.effectiveSecondsPerUse(
            overrides = secondsPerUseOverrides,
            productType = ProductTypes.PEN,
        ),
        syncedUses = loaded.totalUses?.takeIf { it.isFinite() && it >= 0.0 },
        pendingUses = pendingUsesByProduct[loaded.id]
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: 0.0,
    )
}
