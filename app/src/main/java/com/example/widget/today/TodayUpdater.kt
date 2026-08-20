package com.example.widget.today

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.example.data.CannsheetGraph
import com.example.data.ConsumptionPreferencesRepository
import com.example.data.ProductTypeCodes
import com.example.domain.currentSubmissionDateTime
import com.example.widget.PenWidgetRuntime
import kotlinx.coroutines.flow.first

/** Reads local consumption history and refreshes every installed today widget. */
object TodayUpdater {
    private const val HISTORY_LOOKBACK_MILLIS = 10L * 24L * 60L * 60L * 1000L

    /** Safe to call from any app path; users without this widget pay no update cost. */
    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val component = ComponentName(appContext, TodayWidgetProvider::class.java)
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
        val todayDate = currentSubmissionDateTime().date
        val fromEpochMillis = System.currentTimeMillis() - HISTORY_LOOKBACK_MILLIS
        // Local-date comparison is intentional: this table is written with the device's local
        // currentSubmissionDateTime, so analytics timezone rules do not apply here.
        val entries = graph.repository.consumptionHistorySince(fromEpochMillis).first()
        val preferences = graph.consumptionPreferences.preferences.first()
        val secondsPerUse = ConsumptionPreferencesRepository.effectiveSecondsPerUse(
            overrides = preferences.secondsPerUseOverrides,
            productType = ProductTypeCodes.PEN,
        )
        val model = buildTodayUiModel(
            entries = entries,
            todayDate = todayDate,
            secondsPerUse = secondsPerUse,
        )

        AppWidgetManager.getInstance(appContext).updateAppWidget(
            appWidgetId,
            TodayRenderer.buildRemoteViews(
                context = appContext,
                appWidgetId = appWidgetId,
                model = model,
            ),
        )
    }
}
