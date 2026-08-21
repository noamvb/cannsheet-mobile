package com.example.widget.multi

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.example.R
import com.example.widget.ACTION_OPEN_LOG
import com.example.widget.ACTION_UNDO
import com.example.widget.COMMIT_GRACE_MILLIS

/** Builds the fixed-button RemoteViews used by the multi-cart widget. */
object MultiCartRenderer {
    fun buildRemoteViews(
        context: Context,
        appWidgetId: Int,
        model: MultiCartUiModel,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_multi_cart).apply {
        setTextViewText(
            R.id.widget_multi_cart_title,
            context.getString(R.string.multi_cart_widget_title),
        )
        setContentDescription(
            R.id.widget_multi_cart_root,
            if (model.pending == null) {
                context.getString(R.string.multi_cart_widget_label)
            } else {
                context.getString(
                    R.string.pen_widget_undo_seconds,
                    model.pending.restoreDraftSeconds ?: 0,
                )
            },
        )

        setViewVisibility(
            R.id.widget_multi_cart_grid,
            if (model.pending == null) View.VISIBLE else View.GONE,
        )
        setViewVisibility(
            R.id.widget_multi_cart_overflow,
            if (model.pending == null && model.overflowCount > 0) View.VISIBLE else View.GONE,
        )
        setViewVisibility(
            R.id.widget_multi_cart_undo_state,
            if (model.pending == null) View.GONE else View.VISIBLE,
        )

        CART_BUTTON_IDS.forEachIndexed { index, viewId ->
            val entry = model.entries.getOrNull(index)
            if (entry == null || model.pending != null) {
                setViewVisibility(viewId, View.GONE)
            } else {
                setViewVisibility(viewId, View.VISIBLE)
                val seconds = context.getString(R.string.pen_widget_seconds_short, entry.seconds)
                setTextViewText(viewId, "${entry.name}\n$seconds")
                setContentDescription(viewId, "${entry.name}, $seconds")
                setOnClickPendingIntent(
                    viewId,
                    multiCartPendingIntent(
                        context = context,
                        appWidgetId = appWidgetId,
                        action = CART_ACTIONS[index],
                    ),
                )
            }
        }

        if (model.pending == null) {
            if (model.overflowCount > 0) {
                setTextViewText(
                    R.id.widget_multi_cart_overflow,
                    context.getString(R.string.multi_cart_more, model.overflowCount),
                )
                setContentDescription(
                    R.id.widget_multi_cart_overflow,
                    context.getString(R.string.multi_cart_more, model.overflowCount),
                )
                setOnClickPendingIntent(
                    R.id.widget_multi_cart_overflow,
                    multiCartPendingIntent(
                        context = context,
                        appWidgetId = appWidgetId,
                        action = ACTION_OPEN_LOG,
                    ),
                )
            }
        } else {
            val pending = model.pending
            val remainingMillis = (
                pending.commitAtEpochMillis - COMMIT_GRACE_MILLIS - System.currentTimeMillis()
            ).coerceAtLeast(0L)
            val base = SystemClock.elapsedRealtime() + remainingMillis
            setChronometer(R.id.widget_multi_cart_undo_countdown, base, "%s", true)
            setChronometerCountDown(R.id.widget_multi_cart_undo_countdown, true)
            setViewVisibility(R.id.widget_multi_cart_undo_countdown, View.VISIBLE)
            setTextViewText(
                R.id.widget_multi_cart_undo,
                context.getString(R.string.pen_widget_undo_symbol),
            )
            setContentDescription(
                R.id.widget_multi_cart_undo,
                context.getString(
                    R.string.pen_widget_undo_seconds,
                    pending.restoreDraftSeconds ?: 0,
                ),
            )
            setOnClickPendingIntent(
                R.id.widget_multi_cart_undo,
                multiCartPendingIntent(
                    context = context,
                    appWidgetId = appWidgetId,
                    action = ACTION_UNDO,
                    commitId = pending.commitId,
                ),
            )
        }
    }

    private val CART_BUTTON_IDS = listOf(
        R.id.widget_multi_cart_button_1,
        R.id.widget_multi_cart_button_2,
        R.id.widget_multi_cart_button_3,
        R.id.widget_multi_cart_button_4,
    )
}
