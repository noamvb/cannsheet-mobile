package com.example.widget.today

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.example.widget.PenWidgetRuntime

class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val appContext = context.applicationContext
        // Idempotent via ExistingWorkPolicy.REPLACE, so an existing widget on an upgraded install
        // gets armed here without waiting for onEnabled.
        TodayRolloverScheduler.scheduleNext(appContext)
        val pendingResult = goAsync()
        PenWidgetRuntime.launchReceiver(pendingResult) {
            appWidgetIds.forEach { appWidgetId ->
                TodayUpdater.update(appContext, appWidgetId)
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TodayRolloverScheduler.scheduleNext(context.applicationContext)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        TodayRolloverScheduler.cancel(context.applicationContext)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // DATE_CHANGED is deliberately not handled here: it is not on Android's
            // implicit-broadcast exception list on API 26+, so a manifest-declared receiver for it
            // is never delivered. TodayRolloverScheduler's WorkManager job is what covers midnight.
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                PenWidgetRuntime.launchReceiver(pendingResult) {
                    TodayUpdater.updateAllSuspending(appContext)
                    // A clock or timezone change moves when local midnight falls; re-arm against it.
                    TodayRolloverScheduler.scheduleNext(appContext)
                }
            }

            else -> super.onReceive(context, intent)
        }
    }
}
