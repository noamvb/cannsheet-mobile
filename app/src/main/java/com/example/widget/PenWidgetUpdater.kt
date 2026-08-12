package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.example.R

object PenWidgetUpdater {
    /** Safe to call from any app path; users without widget instances pay no update cost. */
    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val component = ComponentName(appContext, PenConsumptionWidgetProvider::class.java)
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
        val state = PenWidgetStateRepository(appContext).read(appWidgetId)
        val draft = state.pendingCommit?.let(PenWidgetDraft::AwaitingCommit)
            ?: PenWidgetDraft.Composing(state.draftSeconds)
        val model = buildPenWidgetUiModel(
            penState = PenWidgetDataSource.loadPenState(appContext),
            draft = draft,
            lastQueuedAtMillis = state.lastQueuedAtMillis,
            nowMillis = System.currentTimeMillis(),
        )
        val manager = AppWidgetManager.getInstance(appContext)
        val options = manager.getAppWidgetOptions(appWidgetId)
        val subtitleMinHeightDp = (
            appContext.resources.getDimension(R.dimen.widget_subtitle_min_height) /
                appContext.resources.displayMetrics.density
        ).toInt()
        val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val compact = minHeightDp in 1 until subtitleMinHeightDp
        manager.updateAppWidget(
            appWidgetId,
            PenWidgetRenderer.buildRemoteViews(
                context = appContext,
                appWidgetId = appWidgetId,
                model = model,
                compact = compact,
            ),
        )
    }
}
