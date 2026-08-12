package com.example.widget

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.example.R

object PenWidgetRenderer {
    fun buildRemoteViews(
        context: Context,
        appWidgetId: Int,
        model: PenWidgetUiModel,
        showSubtitle: Boolean = true,
    ): RemoteViews = when (model) {
        is PenWidgetUiModel.Message -> buildMessageViews(context, appWidgetId, model)
        is PenWidgetUiModel.Composing -> buildInteractiveViews(
            context = context,
            appWidgetId = appWidgetId,
            productName = model.productName,
            subtitle = model.subtitle,
            seconds = model.seconds,
            showSubtitle = showSubtitle,
            awaitingCommit = null,
            canDecrement = model.canDecrement,
            canIncrement = model.canIncrement,
            submitEnabled = model.canSubmit,
        )

        is PenWidgetUiModel.AwaitingCommit -> buildInteractiveViews(
            context = context,
            appWidgetId = appWidgetId,
            productName = model.productName,
            subtitle = model.subtitle,
            seconds = model.frozenSeconds,
            showSubtitle = showSubtitle,
            awaitingCommit = model,
            canDecrement = false,
            canIncrement = false,
            submitEnabled = true,
        )
    }

    private fun buildMessageViews(
        context: Context,
        appWidgetId: Int,
        model: PenWidgetUiModel.Message,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_pen_message).apply {
        setTextViewText(R.id.widget_pen_message_title, model.title)
        setTextViewText(R.id.widget_pen_message_hint, model.hint)
        setContentDescription(R.id.widget_pen_message_root, model.hint)
        setOnClickPendingIntent(
            R.id.widget_pen_message_root,
            pendingIntent(
                context = context,
                appWidgetId = appWidgetId,
                action = when (model.openTarget) {
                    PenWidgetOpenTarget.Log -> ACTION_OPEN_LOG
                    PenWidgetOpenTarget.Settings -> ACTION_OPEN_SETTINGS
                },
            ),
        )
    }

    private fun buildInteractiveViews(
        context: Context,
        appWidgetId: Int,
        productName: String,
        subtitle: String,
        seconds: Int,
        showSubtitle: Boolean,
        awaitingCommit: PenWidgetUiModel.AwaitingCommit?,
        canDecrement: Boolean,
        canIncrement: Boolean,
        submitEnabled: Boolean,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_pen_consumption).apply {
        setTextViewText(R.id.widget_pen_name, productName)
        setTextViewText(R.id.widget_pen_subtitle, subtitle)
        setViewVisibility(
            R.id.widget_pen_subtitle,
            if (showSubtitle) View.VISIBLE else View.GONE,
        )
        setTextViewText(R.id.widget_pen_counter, "${seconds}s")
        setViewVisibility(
            R.id.widget_pen_counter,
            if (awaitingCommit == null) View.VISIBLE else View.GONE,
        )
        setViewVisibility(
            R.id.widget_pen_countdown,
            if (awaitingCommit == null) View.GONE else View.VISIBLE,
        )

        if (awaitingCommit != null) {
            val base = SystemClock.elapsedRealtime() + awaitingCommit.remainingMillis
            setChronometer(R.id.widget_pen_countdown, base, "%s", true)
            setChronometerCountDown(R.id.widget_pen_countdown, true)
            setTextViewText(R.id.widget_pen_submit, PenWidgetText.UNDO)
            setContentDescription(R.id.widget_pen_submit, context.getString(R.string.pen_widget_undo))
            setOnClickPendingIntent(
                R.id.widget_pen_submit,
                pendingIntent(context, appWidgetId, ACTION_UNDO, awaitingCommit.commitId),
            )
        } else {
            setTextViewText(R.id.widget_pen_submit, PenWidgetText.SUBMIT)
            setContentDescription(R.id.widget_pen_submit, context.getString(R.string.pen_widget_submit))
            setOnClickPendingIntent(
                R.id.widget_pen_submit,
                pendingIntent(context, appWidgetId, ACTION_SUBMIT),
            )
        }

        setContentDescription(R.id.widget_pen_counter_panel, context.getString(R.string.pen_widget_reset))
        setOnClickPendingIntent(
            R.id.widget_pen_counter_panel,
            pendingIntent(context, appWidgetId, ACTION_RESET),
        )

        setContentDescription(R.id.widget_pen_minus, context.getString(R.string.pen_widget_decrease))
        setOnClickPendingIntent(
            R.id.widget_pen_minus,
            pendingIntent(context, appWidgetId, ACTION_DECREMENT),
        )
        setStepButtonEnabled(
            remoteViews = this,
            viewId = R.id.widget_pen_minus,
            enabled = canDecrement && awaitingCommit == null,
        )

        setContentDescription(R.id.widget_pen_plus, context.getString(R.string.pen_widget_increase))
        setOnClickPendingIntent(
            R.id.widget_pen_plus,
            pendingIntent(context, appWidgetId, ACTION_INCREMENT),
        )
        setStepButtonEnabled(
            remoteViews = this,
            viewId = R.id.widget_pen_plus,
            enabled = canIncrement && awaitingCommit == null,
        )
        setSubmitButtonEnabled(this, R.id.widget_pen_submit, submitEnabled)
    }

    private fun setStepButtonEnabled(remoteViews: RemoteViews, viewId: Int, enabled: Boolean) {
        remoteViews.setBoolean(viewId, "setEnabled", enabled)
        remoteViews.setInt(
            viewId,
            "setBackgroundResource",
            if (enabled) R.drawable.widget_step_button else R.drawable.widget_step_button_disabled,
        )
    }

    private fun setSubmitButtonEnabled(remoteViews: RemoteViews, viewId: Int, enabled: Boolean) {
        remoteViews.setBoolean(viewId, "setEnabled", enabled)
        remoteViews.setInt(
            viewId,
            "setBackgroundResource",
            if (enabled) R.drawable.widget_submit_button else R.drawable.widget_submit_button_disabled,
        )
    }
}
