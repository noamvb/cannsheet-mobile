package com.example.wear

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearPenProtocolTest {
    @Test
    fun inactiveTileMapsToLog() {
        assertEquals(WearPenAction.LOG, wearPenAction(Tile.STATE_INACTIVE))
    }

    @Test
    fun activeTileMapsToUndo() {
        assertEquals(WearPenAction.UNDO, wearPenAction(Tile.STATE_ACTIVE))
    }

    @Test
    fun unavailableTileMapsToUnavailable() {
        assertEquals(WearPenAction.UNAVAILABLE, wearPenAction(Tile.STATE_UNAVAILABLE))
    }

    @Test
    fun unknownTileStateMapsToUnavailable() {
        assertEquals(WearPenAction.UNAVAILABLE, wearPenAction(99))
    }

    @Test
    fun encoderEmitsExactFields() {
        val fields = wearPenStateFields(
            "Loaded cart · 10 s",
            WearPenAction.UNDO,
            1_727_000_000_123L,
        )
        assertEquals(
            mapOf(
                "v" to 1,
                "label" to "Loaded cart · 10 s",
                "action" to "undo",
                "stampMillis" to 1_727_000_000_123L,
            ),
            fields,
        )
        assertTrue(fields["v"] is Int)
        assertTrue(fields["stampMillis"] is Long)
    }

    @Test
    fun wireLiteralsAreFrozen() {
        assertEquals("/cannsheet/pen/tap", WEAR_PEN_TAP_PATH)
        assertEquals("/cannsheet/pen/refresh", WEAR_PEN_REFRESH_PATH)
        assertEquals("/cannsheet/pen/state", WEAR_PEN_STATE_PATH)
        assertEquals(listOf("log", "undo", "unavailable"), WearPenAction.entries.map { it.wire })
    }

    @Test
    fun manifestPathPrefixCoversMessagePaths() {
        val prefix = "/cannsheet/pen"
        assertTrue(WEAR_PEN_TAP_PATH.startsWith(prefix))
        assertTrue(WEAR_PEN_REFRESH_PATH.startsWith(prefix))
        assertTrue(WEAR_PEN_STATE_PATH.startsWith(prefix))
    }
}
