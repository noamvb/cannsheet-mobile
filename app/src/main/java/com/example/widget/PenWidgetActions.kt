package com.example.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.MainActivity

const val ACTION_DECREMENT = "com.noamv.cannsheet.mobile.widget.DECREMENT"
const val ACTION_INCREMENT = "com.noamv.cannsheet.mobile.widget.INCREMENT"
const val ACTION_RESET = "com.noamv.cannsheet.mobile.widget.RESET"
const val ACTION_SUBMIT = "com.noamv.cannsheet.mobile.widget.SUBMIT"
const val ACTION_UNDO = "com.noamv.cannsheet.mobile.widget.UNDO"
const val ACTION_OPEN_LOG = "com.noamv.cannsheet.mobile.widget.OPEN_LOG"
const val ACTION_OPEN_SETTINGS = "com.noamv.cannsheet.mobile.widget.OPEN_SETTINGS"
const val EXTRA_APP_WIDGET_ID = "com.noamv.cannsheet.mobile.widget.APP_WIDGET_ID"
const val EXTRA_COMMIT_ID = "com.noamv.cannsheet.mobile.widget.COMMIT_ID"
const val EXTRA_START_ROUTE = "com.noamv.cannsheet.mobile.widget.START_ROUTE"
const val INPUT_APP_WIDGET_ID = "app_widget_id"
const val INPUT_COMMIT_ID = "commit_id"

val HANDLED_ACTIONS: Set<String> = setOf(
    ACTION_DECREMENT,
    ACTION_INCREMENT,
    ACTION_RESET,
    ACTION_SUBMIT,
    ACTION_UNDO,
)

fun pendingIntent(
    context: Context,
    appWidgetId: Int,
    action: String,
    commitId: String? = null,
): PendingIntent {
    val requestCode = 31 * appWidgetId + action.hashCode()
    if (action == ACTION_OPEN_LOG || action == ACTION_OPEN_SETTINGS) {
        val startRoute = if (action == ACTION_OPEN_SETTINGS) "settings" else "consumption"
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            putExtra(EXTRA_START_ROUTE, startRoute)
            data = Uri.parse("cannsheet://pen-widget/$appWidgetId/$action")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    val intent = Intent(context, PenConsumptionWidgetProvider::class.java).apply {
        this.action = action
        putExtra(EXTRA_APP_WIDGET_ID, appWidgetId)
        commitId?.let { putExtra(EXTRA_COMMIT_ID, it) }
        // PendingIntent equality ignores extras, so every widget/action pair needs unique data.
        data = Uri.parse("cannsheet://pen-widget/$appWidgetId/$action")
    }
    return PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
