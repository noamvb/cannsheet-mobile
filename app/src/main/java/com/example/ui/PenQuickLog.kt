package com.example.ui

import com.example.data.ProductTypeKey
import com.example.data.Product
import com.example.data.ProductInteraction
import com.example.domain.PenQuickLogState

internal fun resolveLoadedPenProduct(
    products: List<Product>,
    interactions: List<ProductInteraction>,
    explicitProductId: String?,
): Product? = com.example.domain.resolveLoadedPenProduct(products, interactions, explicitProductId)

internal fun buildPenQuickLogState(
    products: List<Product>,
    interactions: List<ProductInteraction>,
    explicitProductId: String?,
    globalPresets: List<Double>,
    presetOverrides: Map<ProductTypeKey, List<Double>>,
    secondsPerUseOverrides: Map<ProductTypeKey, Double>,
    pendingUsesByProduct: Map<String, Double>,
): PenQuickLogState = com.example.domain.buildPenQuickLogState(
    products = products,
    interactions = interactions,
    explicitProductId = explicitProductId,
    globalPresets = globalPresets,
    presetOverrides = presetOverrides,
    secondsPerUseOverrides = secondsPerUseOverrides,
    pendingUsesByProduct = pendingUsesByProduct,
)
