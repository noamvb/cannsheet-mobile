package com.example.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class PenWidgetSchedulerTest {
    @Test
    fun requestHasFiveSecondDelayAndExpectedInputs() {
        val request = PenWidgetScheduler.commitRequest(42, "commit-42")

        assertEquals(UNDO_WINDOW_MILLIS, request.workSpec.initialDelay)
        assertEquals(42, request.workSpec.input.getInt(INPUT_APP_WIDGET_ID, -1))
        assertEquals("commit-42", request.workSpec.input.getString(INPUT_COMMIT_ID))
        assertEquals("cannsheet-pen-widget-commit-42", PenWidgetScheduler.uniqueWorkName(42))
    }
}
