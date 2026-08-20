package com.example.widget

import android.service.quicksettings.Tile
import com.example.data.Product
import com.example.domain.PenQuickLogState
import org.junit.Assert.assertEquals
import org.junit.Test

class PenTileStateTest {
    @Test
    fun noLoadedCartIsUnavailable() {
        val expected = PenTileModel("No cart loaded", Tile.STATE_UNAVAILABLE)

        assertEquals(expected, penTileState(PenQuickLogState.Unavailable, null))
        assertEquals(expected, penTileState(PenQuickLogState.NoCartLoaded, null))
    }

    @Test
    fun missingRateIsUnavailable() {
        assertEquals(
            PenTileModel("No cart loaded", Tile.STATE_UNAVAILABLE),
            penTileState(loaded(rate = null), null),
        )
    }

    @Test
    fun loadedCartShowsNameAndFirstPreset() {
        assertEquals(
            PenTileModel("Loaded cart · 10s", Tile.STATE_INACTIVE),
            penTileState(
                loaded(presetUses = listOf(3.0, 1.0, 2.0)),
                null,
            ),
        )
    }

    @Test
    fun pendingCommitShowsUndoAndActiveState() {
        assertEquals(
            PenTileModel("Undo", Tile.STATE_ACTIVE),
            penTileState(PenQuickLogState.Unavailable, payload()),
        )
    }

    @Test
    fun emptyPresetsFallsBackToTheDefaultStep() {
        assertEquals(
            PenTileModel("Loaded cart · ${STEP_SECONDS}s", Tile.STATE_INACTIVE),
            penTileState(loaded(presetUses = emptyList()), null),
        )
    }

    private fun loaded(
        presetUses: List<Double> = listOf(0.5, 1.0, 2.0),
        rate: Double? = 10.0,
    ) = PenQuickLogState.Loaded(
        product = Product(
            id = "pen-1",
            name = "Loaded cart",
            type = "P",
            status = 0,
            totalUses = 12.0,
        ),
        presetUses = presetUses,
        secondsPerUse = rate,
        syncedUses = 12.0,
        pendingUses = 0.0,
    )

    private fun payload() = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = "commit-1",
        eventId = "event-1",
        submittedAtEpochMillis = 0L,
        commitAtEpochMillis = 5_000L,
        productId = "pen-1",
        productUuid = null,
        seconds = 30,
        secondsPerUse = 10.0,
        uses = 3.0,
        date = "2026-08-12",
        time = "12:00",
    )
}
