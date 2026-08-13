package com.example.widget

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.R
import com.example.data.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PenWidgetRendererTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun composingRemoteViewsInflateWithNameCounterSubtitleAndEnabledState() {
        val views = PenWidgetRenderer.buildRemoteViews(
            context,
            1,
            PenWidgetUiModel.Composing(
                productName = PenWidgetText.Literal("Long loaded pen cart"),
                subtitle = PenWidgetText.Resource(
                    R.string.pen_widget_status_synced,
                    listOf("Active", "12"),
                ),
                seconds = 30,
                recentlyQueued = false,
                canDecrement = true,
                canIncrement = true,
                canSubmit = true,
            ),
        )
        val root = views.apply(context, FrameLayout(context))

        assertEquals("Long loaded pen cart", root.findViewById<TextView>(R.id.widget_pen_name).text)
        assertEquals(
            "Long loaded pen cart. Double tap to open Cannsheet Mobile.",
            root.findViewById<TextView>(R.id.widget_pen_name).contentDescription,
        )
        assertEquals("30s", root.findViewById<TextView>(R.id.widget_pen_counter).text)
        assertEquals("Active · synced 12 uses", root.findViewById<TextView>(R.id.widget_pen_subtitle).text)
        assertTrue(root.findViewById<Button>(R.id.widget_pen_plus).isEnabled)
        assertEquals(View.GONE, root.findViewById<Chronometer>(R.id.widget_pen_countdown).visibility)
    }

    @Test
    fun awaitingRemoteViewsShowCountdownAndUndo() {
        val views = PenWidgetRenderer.buildRemoteViews(
            context,
            2,
            PenWidgetUiModel.AwaitingCommit(
                productName = PenWidgetText.Literal("Loaded pen"),
                subtitle = PenWidgetText.Resource(
                    R.string.pen_widget_undo_window,
                    listOf(5),
                ),
                frozenSeconds = 30,
                commitId = "commit-1",
                remainingMillis = 3_000L,
            ),
        )
        val root = views.apply(context, FrameLayout(context))

        assertEquals(View.VISIBLE, root.findViewById<Chronometer>(R.id.widget_pen_countdown).visibility)
        assertTrue(root.findViewById<Chronometer>(R.id.widget_pen_countdown).isCountDown)
        assertEquals("UNDO", root.findViewById<Button>(R.id.widget_pen_submit).text)
        assertFalse(root.findViewById<Button>(R.id.widget_pen_plus).isEnabled)
        assertTrue(
            root.findViewById<View>(R.id.widget_pen_counter_panel)
                .contentDescription.toString().contains("30 seconds"),
        )
    }

    @Test
    fun messageStatesUseMessageLayout() {
        val views = PenWidgetRenderer.buildRemoteViews(
            context,
            3,
            PenWidgetUiModel.Message(
                kind = PenWidgetMessageKind.RateOff,
                title = PenWidgetText.Resource(R.string.pen_widget_rate_off),
                hint = PenWidgetText.Resource(R.string.pen_widget_rate_off_hint),
                openTarget = PenWidgetOpenTarget.Settings,
            ),
        )
        val root = views.apply(context, FrameLayout(context))

        assertEquals("Pen duration rate is off", root.findViewById<TextView>(R.id.widget_pen_message_title).text)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_pen_message_root).visibility)
        assertEquals(null, root.findViewById<View>(R.id.widget_pen_counter_panel))
    }

    @Test
    fun zeroRemainingRendersStoppedSavingState() {
        val views = PenWidgetRenderer.buildRemoteViews(
            context,
            4,
            PenWidgetUiModel.AwaitingCommit(
                productName = PenWidgetText.Literal("Loaded pen"),
                subtitle = PenWidgetText.Resource(R.string.pen_widget_undo_window, listOf(5)),
                frozenSeconds = 30,
                commitId = "commit-1",
                remainingMillis = 0L,
            ),
        )
        val root = views.apply(context, FrameLayout(context))

        assertEquals(View.GONE, root.findViewById<Chronometer>(R.id.widget_pen_countdown).visibility)
        assertEquals("Saving…", root.findViewById<TextView>(R.id.widget_pen_counter).text)
        assertFalse(root.findViewById<Button>(R.id.widget_pen_submit).isEnabled)
    }

    @Test
    fun compactAndFullLayoutsKeepSubmitVisibleAtSupportedHeights() {
        listOf(110 to true, 160 to false, 250 to false).forEach { (heightDp, compact) ->
            val views = PenWidgetRenderer.buildRemoteViews(
                context,
                5 + heightDp,
                PenWidgetUiModel.Composing(
                    productName = PenWidgetText.Literal("Loaded cart"),
                    subtitle = PenWidgetText.Resource(
                        R.string.pen_widget_status_synced,
                        listOf("Active", "12"),
                    ),
                    seconds = 30,
                    recentlyQueued = false,
                    canDecrement = true,
                    canIncrement = true,
                    canSubmit = true,
                ),
                compact = compact,
            )
            val root = views.apply(context, FrameLayout(context))
            val width = dp(140)
            val height = dp(heightDp)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
            val submit = root.findViewById<Button>(R.id.widget_pen_submit)
            val minus = root.findViewById<Button>(R.id.widget_pen_minus)
            val plus = root.findViewById<Button>(R.id.widget_pen_plus)

            assertTrue("submit must have height at ${heightDp}dp", submit.height >= dp(40))
            assertTrue("submit must fit at ${heightDp}dp", submit.bottom <= root.height)
            assertTrue("decrement must fit at ${heightDp}dp", minus.bottom <= root.height)
            assertTrue("increment must fit at ${heightDp}dp", plus.bottom <= root.height)
            assertTrue("decrement must remain usable at ${heightDp}dp", minus.width >= dp(40))
            assertTrue("increment must remain usable at ${heightDp}dp", plus.width >= dp(40))
        }
    }

    @Test
    fun compactQueuedConfirmationDoesNotDependOnSubtitle() {
        val views = PenWidgetRenderer.buildRemoteViews(
            context,
            6,
            PenWidgetUiModel.Composing(
                productName = PenWidgetText.Literal("Loaded cart"),
                subtitle = PenWidgetText.Resource(R.string.pen_widget_queued),
                seconds = 0,
                recentlyQueued = true,
                canDecrement = false,
                canIncrement = true,
                canSubmit = false,
            ),
            compact = true,
        )
        val root = views.apply(context, FrameLayout(context))

        assertEquals("✓", root.findViewById<TextView>(R.id.widget_pen_counter).text)
        assertEquals(View.GONE, root.findViewById<TextView>(R.id.widget_pen_subtitle).visibility)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
