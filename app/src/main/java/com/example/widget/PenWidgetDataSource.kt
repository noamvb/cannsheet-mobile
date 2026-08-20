package com.example.widget

import android.content.Context
import com.example.data.CannsheetGraph
import com.example.data.sync.QUEUE_STUCK_THRESHOLD_MILLIS
import com.example.domain.PenQuickLogState
import com.example.domain.buildPenQuickLogState
import kotlinx.coroutines.flow.first

data class PenWidgetData(
    val penState: PenQuickLogState,
    val queueStuck: Boolean,
)

object PenWidgetDataSource {
    suspend fun loadWidgetData(
        context: Context,
        pinnedProductId: String? = null,
    ): PenWidgetData {
        val appContext = context.applicationContext
        val graph = CannsheetGraph.get(appContext)
        val pendingActionCount = graph.repository.pendingActionCount.first()
        val queueNonEmptySince = graph.syncPreferences.preferences.first()
            .queueNonEmptySinceEpochMillis
        val nowEpochMillis = System.currentTimeMillis()
        val queueStuck = pendingActionCount > 0 && queueNonEmptySince?.let { since ->
            since >= 0L && nowEpochMillis >= since &&
                nowEpochMillis - since >= QUEUE_STUCK_THRESHOLD_MILLIS
        } == true

        return PenWidgetData(
            penState = loadPenState(appContext, pinnedProductId),
            queueStuck = queueStuck,
        )
    }

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
