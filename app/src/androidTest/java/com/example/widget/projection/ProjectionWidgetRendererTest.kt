package com.example.widget.projection

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Inflates the RemoteViews the renderer produces and reads back the actual TextViews, so these
 * tests fail if a future edit decouples a projection's value from its as-of date the way
 * ADR-039 forbids. See `ProjectionWidgetRenderer.setFigure`, the single place both are written.
 */
@RunWith(AndroidJUnit4::class)
class ProjectionWidgetRendererTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun readyFigureRendersBothTheValueAndTheAsOfDate() {
        val figure = ProjectionFigure(
            key = "runway-1",
            valueText = "Current cart: 12 days left",
            asOfDate = "2026-07-14",
        )
        val views = ProjectionWidgetRenderer.buildRemoteViews(
            context,
            1,
            ProjectionMode.RUNWAY,
            ProjectionUiModel.Ready(mode = ProjectionMode.RUNWAY, figures = listOf(figure)),
        )
        val root = views.apply(context, FrameLayout(context))

        val primary = root.findViewById<TextView>(R.id.widget_projection_primary)
        val asOf = root.findViewById<TextView>(R.id.widget_projection_as_of)

        assertEquals(View.VISIBLE, primary.visibility)
        assertEquals("Current cart: 12 days left", primary.text.toString())
        assertEquals(View.VISIBLE, asOf.visibility)
        assertEquals(context.getString(R.string.projection_as_of, "2026-07-14"), asOf.text.toString())
    }

    /**
     * The invariant test: across every model shape the renderer accepts, the as-of view's
     * visibility must never diverge from the value view's. This is what would have caught the
     * dead `renderedText` coupling — nothing here reads that property, it reads the real views.
     */
    @Test
    fun asOfDateIsVisibleWheneverTheValueIsVisible() {
        val models = listOf(
            ProjectionUiModel.Ready(
                mode = ProjectionMode.RUNWAY,
                figures = listOf(
                    ProjectionFigure(
                        key = "runway-1",
                        valueText = "Current cart: 12 days left",
                        asOfDate = "2026-07-14",
                    ),
                ),
            ),
            ProjectionUiModel.Ready(mode = ProjectionMode.RUNWAY, figures = emptyList()),
            ProjectionUiModel.Suppressed(ProjectionSuppressionReason.NO_SNAPSHOT),
            ProjectionUiModel.Suppressed(ProjectionSuppressionReason.INCOMPLETE_SNAPSHOT),
            ProjectionUiModel.Suppressed(ProjectionSuppressionReason.STRUCTURALLY_UNUSABLE_SNAPSHOT),
            ProjectionUiModel.Suppressed(ProjectionSuppressionReason.NO_USABLE_FIGURE),
        )

        models.forEach { model ->
            val views = ProjectionWidgetRenderer.buildRemoteViews(context, 1, ProjectionMode.RUNWAY, model)
            val root = views.apply(context, FrameLayout(context))

            val primary = root.findViewById<TextView>(R.id.widget_projection_primary)
            val asOf = root.findViewById<TextView>(R.id.widget_projection_as_of)
            val primaryVisible = primary.visibility == View.VISIBLE
            val asOfVisible = asOf.visibility == View.VISIBLE

            assertTrue(
                "as-of visibility ($asOfVisible) must match the value's visibility " +
                    "($primaryVisible) for $model",
                asOfVisible == primaryVisible,
            )
            if (primaryVisible) {
                assertTrue(
                    "as-of text must be non-blank whenever the value is visible for $model",
                    asOf.text.isNotBlank(),
                )
            }
        }
    }

    @Test
    fun suppressedModelShowsPlaceholdersInBothViewsInsteadOfAFigure() {
        val views = ProjectionWidgetRenderer.buildRemoteViews(
            context,
            1,
            ProjectionMode.SPEND,
            ProjectionUiModel.Suppressed(ProjectionSuppressionReason.NO_SNAPSHOT),
        )
        val root = views.apply(context, FrameLayout(context))

        val primary = root.findViewById<TextView>(R.id.widget_projection_primary)
        val asOf = root.findViewById<TextView>(R.id.widget_projection_as_of)

        assertEquals(View.VISIBLE, primary.visibility)
        assertEquals(context.getString(R.string.projection_widget_unavailable), primary.text.toString())
        assertEquals(View.VISIBLE, asOf.visibility)
        assertEquals(context.getString(R.string.projection_widget_no_as_of), asOf.text.toString())
    }
}
