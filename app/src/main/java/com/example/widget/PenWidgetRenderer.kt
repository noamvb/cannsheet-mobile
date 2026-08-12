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
        compact: Boolean = false,
    ): RemoteViews = when (model) {
        is PenWidgetUiModel.Message -> buildMessageViews(context, appWidgetId, model)
        is PenWidgetUiModel.Composing -> buildInteractiveViews(
            context = context,
            appWidgetId = appWidgetId,
            productName = model.productName.resolve(context),
            subtitle = model.subtitle,
            seconds = model.seconds,
            compact = compact,
            recentlyQueued = model.recentlyQueued,
            awaitingCommit = null,
            canDecrement = model.canDecrement,
            canIncrement = model.canIncrement,
            submitEnabled = model.canSubmit,
        )

        is PenWidgetUiModel.AwaitingCommit -> buildInteractiveViews(
            context = context,
            appWidgetId = appWidgetId,
            productName = model.productName.resolve(context),
            subtitle = model.subtitle,
            seconds = model.frozenSeconds,
            compact = compact,
            recentlyQueued = false,
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
        setTextViewText(R.id.widget_pen_message_title, model.title.resolve(context))
        setTextViewText(R.id.widget_pen_message_hint, model.hint.resolve(context))
        setContentDescription(R.id.widget_pen_message_root, model.hint.resolve(context))
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
        subtitle: PenWidgetText,
        seconds: Int,
        compact: Boolean,
        recentlyQueued: Boolean,
        awaitingCommit: PenWidgetUiModel.AwaitingCommit?,
        canDecrement: Boolean,
        canIncrement: Boolean,
        submitEnabled: Boolean,
    ): RemoteViews = RemoteViews(
        context.packageName,
        if (compact) R.layout.widget_pen_consumption_compact else R.layout.widget_pen_consumption,
    ).apply {
        setTextViewText(R.id.widget_pen_name, productName)
        setTextViewText(R.id.widget_pen_subtitle, subtitle.resolve(context))
        setViewVisibility(
            R.id.widget_pen_subtitle,
            if (compact) View.GONE else View.VISIBLE,
        )
        val isSaving = awaitingCommit?.remainingMillis == 0L
        val showCompactConfirmation = compact && recentlyQueued && seconds == 0
        setTextViewText(
            R.id.widget_pen_counter,
            when {
                isSaving -> context.getString(R.string.pen_widget_saving)
                showCompactConfirmation -> context.getString(R.string.pen_widget_queued_symbol)
                else -> context.getString(R.string.pen_widget_seconds_short, seconds)
            },
        )
        setViewVisibility(
            R.id.widget_pen_counter,
            if (awaitingCommit == null || isSaving) View.VISIBLE else View.GONE,
        )
        setViewVisibility(
            R.id.widget_pen_countdown,
            if (awaitingCommit != null && !isSaving) View.VISIBLE else View.GONE,
        )

        if (awaitingCommit != null && !isSaving) {
            val base = SystemClock.elapsedRealtime() + awaitingCommit.remainingMillis
            setChronometer(R.id.widget_pen_countdown, base, "%s", true)
            setChronometerCountDown(R.id.widget_pen_countdown, true)
            setTextViewText(R.id.widget_pen_submit, context.getString(R.string.pen_widget_undo_symbol))
            setContentDescription(
                R.id.widget_pen_submit,
                context.resources.getQuantityString(
                    R.plurals.pen_widget_undo_seconds,
                    seconds,
                    seconds,
                ),
            )
            setOnClickPendingIntent(
                R.id.widget_pen_submit,
                pendingIntent(context, appWidgetId, ACTION_UNDO, awaitingCommit.commitId),
            )
        } else if (isSaving) {
            setChronometer(R.id.widget_pen_countdown, SystemClock.elapsedRealtime(), "%s", false)
            setTextViewText(R.id.widget_pen_submit, context.getString(R.string.pen_widget_saving_symbol))
            setContentDescription(
                R.id.widget_pen_submit,
                context.resources.getQuantityString(
                    R.plurals.pen_widget_saving_seconds_description,
                    seconds,
                    seconds,
                ),
            )
        } else {
            setTextViewText(R.id.widget_pen_submit, context.getString(R.string.pen_widget_submit_symbol))
            setContentDescription(
                R.id.widget_pen_submit,
                context.resources.getQuantityString(
                    R.plurals.pen_widget_submit_seconds,
                    seconds,
                    seconds,
                ),
            )
            setOnClickPendingIntent(
                R.id.widget_pen_submit,
                pendingIntent(context, appWidgetId, ACTION_SUBMIT),
            )
        }

        when {
            showCompactConfirmation -> {
                setContentDescription(
                    R.id.widget_pen_counter_panel,
                    context.getString(R.string.pen_widget_queued),
                )
                setBoolean(R.id.widget_pen_counter_panel, "setEnabled", true)
                setOnClickPendingIntent(
                    R.id.widget_pen_counter_panel,
                    pendingIntent(context, appWidgetId, ACTION_RESET),
                )
            }

            awaitingCommit != null && !isSaving -> {
                setContentDescription(
                    R.id.widget_pen_counter_panel,
                    context.resources.getQuantityString(
                        R.plurals.pen_widget_logging_seconds_description,
                        seconds,
                        seconds,
                    ),
                )
                setBoolean(R.id.widget_pen_counter_panel, "setEnabled", true)
                setOnClickPendingIntent(
                    R.id.widget_pen_counter_panel,
                    pendingIntent(context, appWidgetId, ACTION_UNDO, awaitingCommit.commitId),
                )
            }

            isSaving -> {
                setContentDescription(
                    R.id.widget_pen_counter_panel,
                    context.resources.getQuantityString(
                        R.plurals.pen_widget_saving_seconds_description,
                        seconds,
                        seconds,
                    ),
                )
                setBoolean(R.id.widget_pen_counter_panel, "setEnabled", false)
            }

            else -> {
                setContentDescription(
                    R.id.widget_pen_counter_panel,
                    context.resources.getQuantityString(
                        R.plurals.pen_widget_reset_seconds_description,
                        seconds,
                        seconds,
                    ),
                )
                setBoolean(R.id.widget_pen_counter_panel, "setEnabled", true)
                setOnClickPendingIntent(
                    R.id.widget_pen_counter_panel,
                    pendingIntent(context, appWidgetId, ACTION_RESET),
                )
            }
        }

        setOnClickPendingIntent(
            R.id.widget_pen_name,
            pendingIntent(context, appWidgetId, ACTION_OPEN_LOG),
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
        setSubmitButtonEnabled(this, R.id.widget_pen_submit, submitEnabled && !isSaving)
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

private fun PenWidgetText.resolve(context: Context): String = when (this) {
    is PenWidgetText.Literal -> value
    is PenWidgetText.Resource -> context.getString(resourceId, *arguments.toTypedArray())
}
