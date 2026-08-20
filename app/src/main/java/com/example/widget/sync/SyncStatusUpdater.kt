package com.example.widget.sync

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.example.data.CannsheetGraph
import com.example.widget.PenWidgetRuntime
import kotlinx.coroutines.flow.first

object SyncStatusUpdater {
    /** Safe to call from any app path; users without widget instances pay no update cost. */
    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val component = ComponentName(appContext, SyncStatusWidgetProvider::class.java)
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
        val pendingCount = graph.repository.pendingActionCount.first()
        val preferences = graph.syncPreferences.preferences.first()
        val model = buildSyncStatusUiModel(
            pendingCount = pendingCount,
            lastMeaningfulSyncAtEpochMillis = preferences.lastMeaningfulSyncAtEpochMillis,
            queueNonEmptySinceEpochMillis = preferences.queueNonEmptySinceEpochMillis,
            nowMillis = System.currentTimeMillis(),
        )
        val manager = AppWidgetManager.getInstance(appContext)
        manager.updateAppWidget(
            appWidgetId,
            SyncStatusRenderer.buildRemoteViews(appContext, appWidgetId, model),
        )
    }
}
