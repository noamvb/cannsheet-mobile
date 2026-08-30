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
            stepSeconds = STEP_SECONDS,
        )
        val root = views.apply(context, FrameLayout(context))

        assertEquals("Long loaded pen cart", root.findViewById<TextView>(R.id.widget_pen_name).text)
        assertEquals(
            "Long loaded pen cart. Double tap to choose a different cart.",
            root.findViewById<TextView>(R.id.widget_pen_name).contentDescription,
        )
        assertEquals("30s", root.findViewById<TextView>(R.id.widget_pen_counter).text)
        assertEquals("Active · synced 12 uses", root.findViewById<TextView>(R.id.widget_pen_subtitle).text)
        assertTrue(root.findViewById<Button>(R.id.widget_pen_plus).isEnabled)
        assertEquals(
            "Increase duration by 10 seconds",
            root.findViewById<Button>(R.id.widget_pen_plus).contentDescription,
        )
        assertEquals(View.GONE, root.findViewById<Chronometer>(R.id.widget_pen_countdown).visibility)
    }

    @Test
    fun everyBucketKeepsVisibleLabelsAndDescriptionsAlignedWithTheEffectiveStep() {
        listOf(
            Triple(110, 110, true),
            Triple(140, 160, false),
            Triple(280, 320, false),
        ).forEach { (widthDp, heightDp, compact) ->
            val views = PenWidgetRenderer.buildRemoteViews(
                context,
                8 + widthDp,
                PenWidgetUiModel.Composing(
                    productName = PenWidgetText.Literal("Loaded pen"),
                    subtitle = PenWidgetText.Resource(
                        R.string.pen_widget_status_synced,
                        listOf("Active", "12"),
                    ),
                    seconds = 30,
                    recentlyQueued = false,
                    canDecrement = true,
                    canIncrement = true,
                    canSubmit = true,
                    presetSeconds = listOf(10, 20, 30),
                ),
                spec = PenWidgetSizing.resolve(
                    widthDp = widthDp,
                    heightDp = heightDp,
                    compactBreakpointHeightDp = COMPACT_BREAKPOINT_HEIGHT_DP,
                ),
                stepSeconds = 5,
            )
            val root = views.apply(context, FrameLayout(context))
            val plus = root.findViewById<Button>(R.id.widget_pen_plus)
            val minus = root.findViewById<Button>(R.id.widget_pen_minus)

            assertEquals(if (compact) "+" else "+5s", drawnText(plus))
            assertEquals(if (compact) "−" else "−5s", drawnText(minus))
            assertEquals("Increase duration by 5 seconds", plus.contentDescription)
            assertEquals("Decrease duration by 5 seconds", minus.contentDescription)

            // The preset row carries the same lowercase second suffix and the same allCaps risk.
            val preset = root.findViewById<Button>(R.id.widget_pen_preset_1)
            if (preset.visibility == View.VISIBLE) {
                assertEquals("10s", drawnText(preset))
            }
        }
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
            stepSeconds = STEP_SECONDS,
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
            stepSeconds = STEP_SECONDS,
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
            stepSeconds = STEP_SECONDS,
        )
        val root = views.apply(context, FrameLayout(context))

        assertEquals(View.GONE, root.findViewById<Chronometer>(R.id.widget_pen_countdown).visibility)
        assertEquals("Saving…", root.findViewById<TextView>(R.id.widget_pen_counter).text)
        assertFalse(root.findViewById<Button>(R.id.widget_pen_submit).isEnabled)
    }

    @Test
    fun compactAndFullLayoutsKeepSubmitVisibleAtSupportedHeights() {
        listOf(110, 160, 250).forEach { heightDp ->
            val root = layoutAt(appWidgetId = 5 + heightDp, widthDp = 140, heightDp = heightDp)
            val submit = root.findViewById<Button>(R.id.widget_pen_submit)
            val minus = root.findViewById<Button>(R.id.widget_pen_minus)
            val plus = root.findViewById<Button>(R.id.widget_pen_plus)

            // The rows now share any shortfall instead of clipping the bottom
            // control, so the smallest supported height has a 2dp tolerance.
            val floor = if (heightDp < 160) dp(38) else dp(40)

            assertTrue("submit must have height at ${heightDp}dp", submit.height >= floor)
            assertTrue(
                "counter row must fit at ${heightDp}dp",
                root.findViewById<View>(R.id.widget_pen_counter_row).bottom <= root.height,
            )
            assertTrue(
                "step row must fit at ${heightDp}dp",
                root.findViewById<View>(R.id.widget_pen_step_row).bottom <= root.height,
            )
            assertTrue("step buttons must have height at ${heightDp}dp", plus.height >= floor)
            assertTrue("submit must remain usable at ${heightDp}dp", submit.width >= dp(40))
            assertTrue("decrement must remain usable at ${heightDp}dp", minus.width >= dp(40))
            assertTrue("increment must remain usable at ${heightDp}dp", plus.width >= dp(40))
        }
    }

    @Test
    fun resizedWidgetSpendsExtraHeightOnControlsRatherThanEmptyBackground() {
        listOf(140 to 160, 200 to 240, 300 to 320, 360 to 360).forEach { (widthDp, heightDp) ->
            val root = layoutAt(appWidgetId = 400 + heightDp, widthDp = widthDp, heightDp = heightDp)
            val panel = root.findViewById<View>(R.id.widget_pen_counter_panel)
            // widget_pen_step_row is a direct child of the root, so its bottom is
            // root-relative. The root padding is 10dp; anything much larger than
            // that below the step row is the dead spacer this layout used to end
            // with.
            val stepRow = root.findViewById<View>(R.id.widget_pen_step_row)
            val slack = root.height - stepRow.bottom

            assertTrue(
                "dead space of ${slack}px below the controls at ${widthDp}x${heightDp}dp",
                slack <= dp(12),
            )
            assertTrue(
                "counter panel collapsed at ${widthDp}x${heightDp}dp",
                panel.height >= dp(40),
            )
        }
    }

    @Test
    fun controlsAndTextGrowWhenTheWidgetGrows() {
        val small = layoutAt(appWidgetId = 501, widthDp = 140, heightDp = 160)
        val large = layoutAt(appWidgetId = 502, widthDp = 300, heightDp = 320)

        val smallPanel = small.findViewById<View>(R.id.widget_pen_counter_panel)
        val largePanel = large.findViewById<View>(R.id.widget_pen_counter_panel)
        val smallCounter = small.findViewById<TextView>(R.id.widget_pen_counter)
        val largeCounter = large.findViewById<TextView>(R.id.widget_pen_counter)
        val smallPlus = small.findViewById<Button>(R.id.widget_pen_plus)
        val largePlus = large.findViewById<Button>(R.id.widget_pen_plus)
        val largeSubmit = large.findViewById<Button>(R.id.widget_pen_submit)

        assertTrue("counter panel must grow", largePanel.height > smallPanel.height)
        assertTrue("counter panel must widen", largePanel.width > smallPanel.width)
        assertTrue("step buttons must grow", largePlus.height > smallPlus.height)
        assertTrue("counter text must grow", largeCounter.textSize > smallCounter.textSize)
        assertTrue(
            "submit must stay paired with the counter panel",
            largeSubmit.height == largePanel.height,
        )
        assertTrue("submit must widen with the widget", largeSubmit.width > dp(40))
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
            spec = PenWidgetSizing.resolve(
                widthDp = 140,
                heightDp = 110,
                compactBreakpointHeightDp = COMPACT_BREAKPOINT_HEIGHT_DP,
            ),
            stepSeconds = STEP_SECONDS,
        )
        val root = views.apply(context, FrameLayout(context))

        assertEquals("✓", root.findViewById<TextView>(R.id.widget_pen_counter).text)
        assertEquals(View.GONE, root.findViewById<TextView>(R.id.widget_pen_subtitle).visibility)
    }

    @Test
    fun presetRowHidesUntilTheRegularLayoutHasEnoughHeight() {
        val views = PenWidgetRenderer.buildRemoteViews(
            context,
            7,
            PenWidgetUiModel.Composing(
                productName = PenWidgetText.Literal("Loaded cart"),
                subtitle = PenWidgetText.Resource(
                    R.string.pen_widget_status_synced,
                    listOf("Active", "12"),
                ),
                seconds = 0,
                recentlyQueued = false,
                canDecrement = false,
                canIncrement = true,
                canSubmit = false,
                presetSeconds = listOf(5, 10, 20),
            ),
            spec = PenWidgetSizing.resolve(
                widthDp = 140,
                heightDp = 160,
                compactBreakpointHeightDp = COMPACT_BREAKPOINT_HEIGHT_DP,
            ),
            stepSeconds = STEP_SECONDS,
        )
        val root = views.apply(context, FrameLayout(context))

        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_pen_preset_row).visibility)
    }

    private fun layoutAt(appWidgetId: Int, widthDp: Int, heightDp: Int): View {
        val views = PenWidgetRenderer.buildRemoteViews(
            context,
            appWidgetId,
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
            spec = PenWidgetSizing.resolve(
                widthDp = widthDp,
                heightDp = heightDp,
                compactBreakpointHeightDp = COMPACT_BREAKPOINT_HEIGHT_DP,
            ),
            stepSeconds = STEP_SECONDS,
        )
        val root = views.apply(context, FrameLayout(context))
        val width = dp(widthDp)
        val height = dp(heightDp)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        return root
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /**
     * The glyphs a [TextView] actually draws, which is not always what [TextView.getText] holds.
     * `Button` styles default to `textAllCaps`, applied as a [android.text.method.TransformationMethod]
     * at draw time, so a lowercase "+10s" reached the screen as "+10S" and read as "+105" while an
     * assertion on `getText()` stayed green. Asserting the stored string rather than the rendered
     * one is the same mistake that let the step defect ship.
     */
    private fun drawnText(view: TextView): String =
        view.transformationMethod?.getTransformation(view.text, view)?.toString()
            ?: view.text.toString()

    private companion object {
        /** Mirrors `R.dimen.widget_compact_breakpoint_height`. */
        const val COMPACT_BREAKPOINT_HEIGHT_DP = 150
    }
}
