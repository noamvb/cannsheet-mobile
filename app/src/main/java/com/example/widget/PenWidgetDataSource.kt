package com.example.widget

import android.content.Context
import com.example.data.CannsheetGraph
import com.example.domain.PenQuickLogState
import com.example.domain.buildPenQuickLogState
import kotlinx.coroutines.flow.first

object PenWidgetDataSource {
    suspend fun loadPenState(
        context: Context,
        pinnedProductId: String? = null,
    ): PenQuickLogState {
        val graph = CannsheetGraph.get(context.applicationContext)
        val preferences = graph.consumptionPreferences.preferences.first()
        val pendingUses = graph.repository.pendingProductUses.first()
            .asSequence()
            .filter { it.pendingUses.isFinite() && it.pendingUses > 0.0 }
            .associate { it.productId to it.pendingUses }

        return buildPenQuickLogState(
            products = graph.repository.allProducts.first(),
            interactions = graph.repository.productInteractions.first(),
            explicitProductId = pinnedProductId ?: preferences.loadedPenProductId,
            globalPresets = preferences.quantityPresets,
            presetOverrides = preferences.quantityPresetOverrides,
            secondsPerUseOverrides = preferences.secondsPerUseOverrides,
            pendingUsesByProduct = pendingUses,
        )
    }
}
