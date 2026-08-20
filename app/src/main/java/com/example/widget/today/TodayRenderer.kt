package com.example.widget.today

import android.content.Context
import android.widget.RemoteViews
import com.example.R
import com.example.widget.ACTION_OPEN_LOG
import com.example.widget.pendingIntent

/** Builds the glanceable today summary shown by the home-screen widget. */
object TodayRenderer {
    fun buildRemoteViews(
        context: Context,
        appWidgetId: Int,
        model: TodayUiModel,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_today).apply {
        setTextViewText(
            R.id.widget_today_title,
            context.getString(R.string.today_widget_title),
        )
        setTextViewText(
            R.id.widget_today_total,
            model.todayDisplay,
        )
        setTextViewText(
            R.id.widget_today_average,
            model.baselineDisplay?.let { display ->
                context.getString(R.string.today_widget_average, display)
            } ?: context.getString(R.string.today_widget_average_unavailable),
        )
        setTextViewText(
            R.id.widget_today_comparison,
            context.getString(
                when (model.comparison) {
                    TodayComparison.UNAVAILABLE -> R.string.today_widget_comparison_unavailable
                    TodayComparison.ABOVE -> R.string.today_widget_comparison_above
                    TodayComparison.BELOW -> R.string.today_widget_comparison_below
                    TodayComparison.MATCH -> R.string.today_widget_comparison_match
                },
            ),
        )
        setTextViewText(
            R.id.widget_today_streak,
            if (model.streakDays > 0) {
                context.getString(R.string.today_widget_streak, model.streakDays)
            } else {
                context.getString(R.string.today_widget_no_streak)
            },
        )
        setContentDescription(
            R.id.widget_today_root,
            context.getString(R.string.today_widget_open_log),
        )
        setOnClickPendingIntent(
            R.id.widget_today_root,
            pendingIntent(context, appWidgetId, ACTION_OPEN_LOG),
        )
    }
}
