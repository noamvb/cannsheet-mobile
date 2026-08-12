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
import com.example.ui.PenQuickLogState
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
                productName = "Long loaded pen cart",
                subtitle = "Active · synced 12 uses",
                seconds = 30,
                canDecrement = true,
                canIncrement = true,
                canSubmit = true,
            ),
        )
        val root = views.apply(context, FrameLayout(context))

        assertEquals("Long loaded pen cart", root.findViewById<TextView>(R.id.widget_pen_name).text)
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
                productName = "Loaded pen",
                subtitle = "Undo within 5 seconds",
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
    }

    @Test
    fun messageStatesUseMessageLayout() {
        val views = PenWidgetRenderer.buildRemoteViews(
            context,
            3,
            PenWidgetUiModel.Message(
                kind = PenWidgetMessageKind.RateOff,
                title = "Pen duration rate is off",
                hint = "Tap to set it in Settings.",
                openTarget = PenWidgetOpenTarget.Settings,
            ),
        )
        val root = views.apply(context, FrameLayout(context))

        assertEquals("Pen duration rate is off", root.findViewById<TextView>(R.id.widget_pen_message_title).text)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_pen_message_root).visibility)
        assertEquals(null, root.findViewById<View>(R.id.widget_pen_counter_panel))
    }
}
