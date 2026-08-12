package com.example.domain

import com.example.data.ConsumptionPreferencesRepository
import com.example.data.Product
import com.example.data.ProductInteraction
import com.example.data.ProductTypeCodes
import com.example.data.ProductTypeKey
import com.example.data.productStatus

sealed interface PenQuickLogState {
    data object Unavailable : PenQuickLogState

    data object NoCartLoaded : PenQuickLogState

    data class Loaded(
        val product: Product,
        val presetUses: List<Double>,
        val secondsPerUse: Double?,
        val syncedUses: Double?,
        val pendingUses: Double,
    ) : PenQuickLogState
}

fun resolveLoadedPenProduct(
    products: List<Product>,
    interactions: List<ProductInteraction>,
    explicitProductId: String?,
): Product? {
    val selectablePens = products.filter { product ->
        product.productStatus.isSelectable &&
            ProductTypeCodes.normalize(product.type) == ProductTypeCodes.PEN
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

fun buildPenQuickLogState(
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
            ProductTypeCodes.normalize(product.type) == ProductTypeCodes.PEN
    }
    if (!selectablePenExists) return PenQuickLogState.Unavailable

    val loaded = resolveLoadedPenProduct(products, interactions, explicitProductId)
        ?: return PenQuickLogState.NoCartLoaded

    return PenQuickLogState.Loaded(
        product = loaded,
        presetUses = ConsumptionPreferencesRepository.effectiveQuantityPresets(
            globalPresets = globalPresets,
            overrides = presetOverrides,
            productType = ProductTypeCodes.PEN,
        ),
        secondsPerUse = ConsumptionPreferencesRepository.effectiveSecondsPerUse(
            overrides = secondsPerUseOverrides,
            productType = ProductTypeCodes.PEN,
        ),
        syncedUses = loaded.totalUses?.takeIf { it.isFinite() && it >= 0.0 },
        pendingUses = pendingUsesByProduct[loaded.id]
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: 0.0,
    )
}
