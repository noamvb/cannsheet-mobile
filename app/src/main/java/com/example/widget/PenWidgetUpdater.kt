package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
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
        val repository = PenWidgetStateRepository(appContext)
        val configRepository = PenWidgetConfigRepository(appContext)
        val config = configRepository.read(appWidgetId)
        val stepSeconds = configRepository.effectiveStepSeconds(appWidgetId)
        val state = repository.read(appWidgetId)
        val draft = state.pendingCommit?.let(PenWidgetDraft::AwaitingCommit)
            ?: PenWidgetDraft.Composing(state.draftSeconds)
        val widgetData = PenWidgetDataSource.loadWidgetData(appContext, config.pinnedProductId)
        val model = buildPenWidgetUiModel(
            penState = widgetData.penState,
            draft = draft,
            lastQueuedAtMillis = state.lastQueuedAtMillis,
            nowMillis = System.currentTimeMillis(),
            discreet = config.discreet,
            queueStuck = widgetData.queueStuck,
        )
        val manager = AppWidgetManager.getInstance(appContext)
        val options = manager.getAppWidgetOptions(appWidgetId)
        val compactBreakpointHeightDp = (
            appContext.resources.getDimension(R.dimen.widget_compact_breakpoint_height) /
                appContext.resources.displayMetrics.density
        ).toInt()
        val resolvedSpec = PenWidgetSizing.resolve(
            widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
            heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
            compactBreakpointHeightDp = compactBreakpointHeightDp,
        )
        val compactSpec = PenWidgetSizing.resolve(
            widthDp = 110,
            heightDp = 110,
            compactBreakpointHeightDp = compactBreakpointHeightDp,
        )
        val baseSpec = PenWidgetSizing.resolve(
            widthDp = 140,
            heightDp = 160,
            compactBreakpointHeightDp = compactBreakpointHeightDp,
        )
        val largeSpec = PenWidgetSizing.resolve(
            widthDp = 280,
            heightDp = 320,
            compactBreakpointHeightDp = compactBreakpointHeightDp,
        )

        val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RemoteViews(
                mapOf(
                    SizeF(110f, 110f) to PenWidgetRenderer.buildRemoteViews(
                        appContext,
                        appWidgetId,
                        model,
                        compactSpec,
                        stepSeconds,
                    ),
                    SizeF(140f, 160f) to PenWidgetRenderer.buildRemoteViews(
                        appContext,
                        appWidgetId,
                        model,
                        baseSpec,
                        stepSeconds,
                    ),
                    SizeF(280f, 320f) to PenWidgetRenderer.buildRemoteViews(
                        appContext,
                        appWidgetId,
                        model,
                        largeSpec,
                        stepSeconds,
                    ),
                ),
            )
        } else {
            PenWidgetRenderer.buildRemoteViews(
                appContext,
                appWidgetId,
                model,
                resolvedSpec,
                stepSeconds,
            )
        }

        manager.updateAppWidget(appWidgetId, views)
    }
}
