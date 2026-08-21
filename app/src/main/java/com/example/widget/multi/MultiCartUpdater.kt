package com.example.widget.multi

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.example.data.CannsheetGraph
import com.example.widget.PenWidgetRuntime
import com.example.widget.PenWidgetStateRepository
import kotlinx.coroutines.flow.first

/** Reads the shared catalog/preferences and refreshes every installed multi-cart widget. */
object MultiCartUpdater {
    /** Safe to call from any app path; users without this widget pay no update cost. */
    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val component = ComponentName(appContext, MultiCartWidgetProvider::class.java)
        val appWidgetIds = manager.getAppWidgetIds(component)
        if (appWidgetIds.isEmpty()) return

        PenWidgetRuntime.launchSerialized {
            appWidgetIds.forEach { appWidgetId ->
                update(appContext, appWidgetId)
            }
        }
    }

    suspend fun update(context: Context, appWidgetId: Int) {
        if (appWidgetId < 0) return

        val appContext = context.applicationContext
        val graph = CannsheetGraph.get(appContext)
        val preferences = graph.consumptionPreferences.preferences.first()
        val state = PenWidgetStateRepository(appContext).read(appWidgetId)
        val model = buildMultiCartUiModel(
            products = graph.repository.allProducts.first(),
            interactions = graph.repository.productInteractions.first(),
            globalPresets = preferences.quantityPresets,
            presetOverrides = preferences.quantityPresetOverrides,
            secondsPerUseOverrides = preferences.secondsPerUseOverrides,
            pending = state.pendingCommit,
        )

        AppWidgetManager.getInstance(appContext).updateAppWidget(
            appWidgetId,
            MultiCartRenderer.buildRemoteViews(
                context = appContext,
                appWidgetId = appWidgetId,
                model = model,
            ),
        )
    }
}
