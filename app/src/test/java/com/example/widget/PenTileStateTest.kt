package com.example.widget

import android.service.quicksettings.Tile
import com.example.R
import com.example.data.Product
import com.example.domain.PenQuickLogState
import org.junit.Assert.assertEquals
import org.junit.Test

class PenTileStateTest {
    @Test
    fun noLoadedCartIsUnavailable() {
        val expected = PenTileModel(PenWidgetText.Resource(R.string.pen_tile_no_cart), Tile.STATE_UNAVAILABLE)

        assertEquals(expected, penTileState(PenQuickLogState.Unavailable, null))
        assertEquals(expected, penTileState(PenQuickLogState.NoCartLoaded, null))
    }

    @Test
    fun missingRateIsUnavailable() {
        assertEquals(
            PenTileModel(PenWidgetText.Resource(R.string.pen_tile_no_cart), Tile.STATE_UNAVAILABLE),
            penTileState(loaded(rate = null), null),
        )
    }

    @Test
    fun loadedCartShowsNameAndFirstPreset() {
        assertEquals(
            PenTileModel(
                PenWidgetText.Resource(R.string.pen_tile_loaded, listOf("Loaded cart", 10)),
                Tile.STATE_INACTIVE,
            ),
            penTileState(
                loaded(presetUses = listOf(3.0, 1.0, 2.0)),
                null,
            ),
        )
    }

    @Test
    fun pendingCommitShowsUndoAndActiveState() {
        assertEquals(
            PenTileModel(PenWidgetText.Resource(R.string.pen_tile_undo), Tile.STATE_ACTIVE),
            penTileState(PenQuickLogState.Unavailable, payload()),
        )
    }

    @Test
    fun emptyPresetsFallsBackToTheDefaultStep() {
        assertEquals(
            PenTileModel(
                PenWidgetText.Resource(R.string.pen_tile_loaded, listOf("Loaded cart", STEP_SECONDS)),
                Tile.STATE_INACTIVE,
            ),
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
        inputKind = DeferredPenInputKind.DURATION_SECONDS,
        seconds = 30,
        secondsPerUse = 10.0,
        restoreDraftSeconds = 30,
        uses = 3.0,
        date = "2026-08-12",
        time = "12:00",
    )
}
