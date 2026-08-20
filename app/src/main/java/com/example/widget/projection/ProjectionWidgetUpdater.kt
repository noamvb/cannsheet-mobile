package com.example.widget.projection

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.example.data.CannsheetGraph
import com.example.widget.PenWidgetRuntime
import kotlinx.coroutines.flow.first

/** Reads one cached Insights snapshot and renders it without fetching or refreshing analytics. */
object ProjectionWidgetUpdater {
    /** Safe to call from any app path; instances are discovered before launching widget work. */
    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val component = ComponentName(appContext, ProjectionWidgetProvider::class.java)
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
        val mode = appContext.projectionWidgetConfigurationDataStore.data
            .first()[ProjectionWidgetConfiguration.modeKey(appWidgetId)]
            .toProjectionMode()
        val snapshot = runCatching {
            CannsheetGraph.get(appContext).analyticsRepository.readCachedInsights()
        }.getOrNull()
        val model = buildProjectionUiModel(snapshot, mode)

        AppWidgetManager.getInstance(appContext).updateAppWidget(
            appWidgetId,
            ProjectionWidgetRenderer.buildRemoteViews(
                context = appContext,
                appWidgetId = appWidgetId,
                mode = mode,
                model = model,
            ),
        )
    }

    private fun String?.toProjectionMode(): ProjectionMode = when (this) {
        ProjectionWidgetConfiguration.MODE_SPEND -> ProjectionMode.SPEND
        else -> ProjectionMode.RUNWAY
    }
}
