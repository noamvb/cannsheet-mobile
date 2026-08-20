package com.example.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenWidgetActionsTest {
    @Test
    fun openActionsAreNotBroadcastActions() {
        assertFalse(ACTION_OPEN_LOG in HANDLED_ACTIONS)
        assertFalse(ACTION_OPEN_CART_PICKER in HANDLED_ACTIONS)
        assertFalse(ACTION_OPEN_SETTINGS in HANDLED_ACTIONS)
        assertTrue(ACTION_DECREMENT in HANDLED_ACTIONS)
        assertTrue(ACTION_INCREMENT in HANDLED_ACTIONS)
        assertTrue(ACTION_RESET in HANDLED_ACTIONS)
        assertTrue(ACTION_SUBMIT in HANDLED_ACTIONS)
        assertTrue(ACTION_UNDO in HANDLED_ACTIONS)
    }
}
