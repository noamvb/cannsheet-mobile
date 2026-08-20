package com.example.widget.projection

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.example.R
import com.example.widget.ACTION_OPEN_INSIGHTS
import com.example.widget.pendingIntent

/** Builds a fixed RemoteViews surface for either the runway or spend projection mode. */
object ProjectionWidgetRenderer {
    fun buildRemoteViews(
        context: Context,
        appWidgetId: Int,
        mode: ProjectionMode,
        model: ProjectionUiModel,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_projection).apply {
        setTextViewText(
            R.id.widget_projection_title,
            context.getString(
                when (mode) {
                    ProjectionMode.RUNWAY -> R.string.projection_widget_title_runway
                    ProjectionMode.SPEND -> R.string.projection_widget_title_spend
                },
            ),
        )
        setOnClickPendingIntent(
            R.id.widget_projection_root,
            pendingIntent(context, appWidgetId, ACTION_OPEN_INSIGHTS),
        )
        setOnClickPendingIntent(
            R.id.widget_projection_action,
            pendingIntent(context, appWidgetId, ACTION_OPEN_INSIGHTS),
        )
        setContentDescription(
            R.id.widget_projection_root,
            context.getString(R.string.projection_widget_open_insights),
        )
        setTextViewText(
            R.id.widget_projection_action,
            context.getString(R.string.projection_widget_open_insights),
        )

        when (model) {
            is ProjectionUiModel.Ready -> renderReady(context, model)
            is ProjectionUiModel.Suppressed -> renderSuppressed(context, model)
        }
    }

    private fun RemoteViews.renderReady(
        context: Context,
        model: ProjectionUiModel.Ready,
    ) {
        val figure = model.figures.firstOrNull()
        if (figure == null) {
            setViewVisibility(R.id.widget_projection_primary, View.GONE)
            setViewVisibility(R.id.widget_projection_supporting, View.GONE)
            setViewVisibility(R.id.widget_projection_as_of, View.GONE)
            setTextViewText(
                R.id.widget_projection_primary,
                context.getString(R.string.projection_widget_unavailable),
            )
            return
        }

        setViewVisibility(R.id.widget_projection_primary, View.VISIBLE)
        setViewVisibility(R.id.widget_projection_supporting, View.VISIBLE)
        setViewVisibility(R.id.widget_projection_as_of, View.VISIBLE)
        setTextViewText(R.id.widget_projection_primary, figure.valueText)
        setTextViewText(
            R.id.widget_projection_supporting,
            if (model.figures.size > 1) {
                context.getString(R.string.projection_widget_more, model.figures.size - 1)
            } else {
                context.getString(R.string.projection_widget_cached_note)
            },
        )
        setTextViewText(
            R.id.widget_projection_as_of,
            context.getString(R.string.projection_as_of, figure.asOfDate),
        )
    }

    private fun RemoteViews.renderSuppressed(
        context: Context,
        model: ProjectionUiModel.Suppressed,
    ) {
        setViewVisibility(R.id.widget_projection_primary, View.VISIBLE)
        setViewVisibility(R.id.widget_projection_supporting, View.VISIBLE)
        setViewVisibility(R.id.widget_projection_as_of, View.VISIBLE)
        setTextViewText(
            R.id.widget_projection_primary,
            context.getString(R.string.projection_widget_unavailable),
        )
        setTextViewText(
            R.id.widget_projection_supporting,
            context.getString(
                when (model.reason) {
                    ProjectionSuppressionReason.NO_SNAPSHOT -> R.string.projection_widget_no_snapshot
                    ProjectionSuppressionReason.INCOMPLETE_SNAPSHOT ->
                        R.string.projection_widget_incomplete
                    ProjectionSuppressionReason.STRUCTURALLY_UNUSABLE_SNAPSHOT,
                    ProjectionSuppressionReason.NO_USABLE_FIGURE,
                    -> R.string.projection_widget_no_usable_figure
                },
            ),
        )
        setTextViewText(
            R.id.widget_projection_as_of,
            context.getString(R.string.projection_widget_no_as_of),
        )
    }
}
