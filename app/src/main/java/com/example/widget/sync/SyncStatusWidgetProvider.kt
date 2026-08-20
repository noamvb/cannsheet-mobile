package com.example.widget.sync

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.data.sync.SyncScheduler
import com.example.widget.PenWidgetRuntime

const val ACTION_SYNC_NOW = "com.noamv.cannsheet.mobile.widget.SYNC_NOW"

class SyncStatusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        PenWidgetRuntime.launchReceiver(pendingResult) {
            appWidgetIds.forEach { appWidgetId ->
                SyncStatusUpdater.update(appContext, appWidgetId)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SYNC_NOW) {
            super.onReceive(context, intent)
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        PenWidgetRuntime.launchReceiver(pendingResult) {
            SyncScheduler.enqueueImmediate(appContext)
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                SyncStatusUpdater.update(appContext, appWidgetId)
            }
        }
    }
}

internal fun syncNowPendingIntent(
    context: Context,
    appWidgetId: Int,
): PendingIntent {
    val intent = Intent(context, SyncStatusWidgetProvider::class.java).apply {
        action = ACTION_SYNC_NOW
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        data = Uri.parse("cannsheet://sync-status-widget/$appWidgetId")
    }
    return PendingIntent.getBroadcast(
        context,
        31 * appWidgetId + ACTION_SYNC_NOW.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
