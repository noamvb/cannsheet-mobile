package com.example.widget.sync

import android.content.Context
import android.widget.RemoteViews
import com.example.R
import com.example.widget.PenWidgetText

object SyncStatusRenderer {
    fun buildRemoteViews(
        context: Context,
        appWidgetId: Int,
        model: SyncStatusUiModel,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_sync_status).apply {
        setTextViewText(
            R.id.widget_sync_status_title,
            context.getString(R.string.sync_status_widget_title),
        )
        setTextViewText(
            R.id.widget_sync_status_status,
            context.getString(
                when {
                    model.stuck -> R.string.sync_status_widget_stuck
                    model.pendingCount > 0 -> R.string.sync_status_widget_pending_status
                    else -> R.string.sync_status_widget_ready
                },
            ),
        )
        setTextViewText(
            R.id.widget_sync_status_pending,
            if (model.pendingCount == 0) {
                context.getString(R.string.sync_status_widget_pending_none)
            } else {
                context.resources.getQuantityString(
                    R.plurals.sync_status_widget_pending,
                    model.pendingCount,
                    model.pendingCount,
                )
            },
        )
        setTextViewText(
            R.id.widget_sync_status_last_sync,
            model.lastSyncLabel.resolve(context),
        )
        setContentDescription(
            R.id.widget_sync_status_root,
            context.getString(R.string.sync_status_widget_sync_now),
        )
        setOnClickPendingIntent(
            R.id.widget_sync_status_root,
            syncNowPendingIntent(context, appWidgetId),
        )
    }
}

private fun PenWidgetText.resolve(context: Context): String = when (this) {
    is PenWidgetText.Literal -> value
    is PenWidgetText.Resource -> context.getString(resourceId, *arguments.toTypedArray())
}
