package com.example.widget.multi

import com.example.data.ConsumptionPreferencesRepository
import com.example.data.Product
import com.example.data.ProductInteraction
import com.example.data.ProductTypeCodes
import com.example.data.ProductTypeKey
import com.example.data.productStatus
import com.example.domain.usesToSeconds
import com.example.widget.MAX_SECONDS
import com.example.widget.PenWidgetCommitPayload

data class MultiCartEntry(
    val productId: String,
    val productUuid: String?,
    val name: String,
    val seconds: Int,
    val secondsPerUse: Double,
)

data class MultiCartUiModel(
    val entries: List<MultiCartEntry>,
    val overflowCount: Int,
    val pending: PenWidgetCommitPayload?,
)

fun buildMultiCartUiModel(
    products: List<Product>,
    interactions: List<ProductInteraction>,
    globalPresets: List<Double>,
    presetOverrides: Map<ProductTypeKey, List<Double>>,
    secondsPerUseOverrides: Map<ProductTypeKey, Double>,
    pending: PenWidgetCommitPayload?,
    maxEntries: Int = 4,
): MultiCartUiModel {
    val selectablePens = products.filter { product ->
        product.productStatus.isSelectable &&
            ProductTypeCodes.normalize(product.type) == ProductTypeCodes.PEN
    }
    val interactionsByProduct = interactions.associateBy(ProductInteraction::productId)
    val orderedPens = selectablePens.sortedByDescending { product ->
        interactionsByProduct[product.id]?.lastLoggedAtEpochMillis ?: Long.MIN_VALUE
    }
    val entryLimit = maxEntries.coerceAtLeast(0)
    val entries = orderedPens.mapNotNull { product ->
        product.toMultiCartEntry(
            globalPresets = globalPresets,
            presetOverrides = presetOverrides,
            secondsPerUseOverrides = secondsPerUseOverrides,
        )
    }

    return MultiCartUiModel(
        entries = entries.take(entryLimit),
        overflowCount = (entries.size - entryLimit).coerceAtLeast(0),
        pending = pending,
    )
}

private fun Product.toMultiCartEntry(
    globalPresets: List<Double>,
    presetOverrides: Map<ProductTypeKey, List<Double>>,
    secondsPerUseOverrides: Map<ProductTypeKey, Double>,
): MultiCartEntry? {
    val typeKey = ProductTypeKey(type)
    val presetUses = ConsumptionPreferencesRepository.effectiveQuantityPresets(
        globalPresets = globalPresets,
        overrides = presetOverrides,
        productType = typeKey.type,
    ).firstOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val secondsPerUse = ConsumptionPreferencesRepository.effectiveSecondsPerUse(
        overrides = secondsPerUseOverrides,
        productType = typeKey.type,
    ) ?: return null
    val seconds = runCatching { usesToSeconds(presetUses, secondsPerUse) }
        .getOrNull()
        ?.toWholeDisplaySeconds()
        ?: return null

    return MultiCartEntry(
        productId = id,
        productUuid = productUuid,
        name = name,
        seconds = seconds,
        secondsPerUse = secondsPerUse,
    )
}

private fun Double.toWholeDisplaySeconds(): Int? {
    if (!isFinite()) return null
    val seconds = toInt()
    return seconds.takeIf { it in 1..MAX_SECONDS && it.toDouble() == this }
}
