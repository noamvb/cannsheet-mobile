package com.example.data

import android.content.Context
import com.example.domain.PenQuickLogState
import com.example.domain.buildPenQuickLogState
import kotlinx.coroutines.flow.first

/**
 * Cold-start-safe, one-shot loaded-pen state for entry points that do not own a ViewModel.
 *
 * This reads only process-wide repositories from [CannsheetGraph]. It does not start a network
 * request, mutate the loaded cart, or depend on an Activity/Compose lifecycle.
 */
object PenQuickLogDataSource {
    suspend fun load(
        context: Context,
        explicitProductId: String? = null,
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
            explicitProductId = explicitProductId ?: preferences.loadedPenProductId,
            globalPresets = preferences.quantityPresets,
            presetOverrides = preferences.quantityPresetOverrides,
            secondsPerUseOverrides = preferences.secondsPerUseOverrides,
            pendingUsesByProduct = pendingUses,
        )
    }
}
