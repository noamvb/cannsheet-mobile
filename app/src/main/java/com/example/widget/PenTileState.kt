package com.example.widget

import android.service.quicksettings.Tile
import com.example.domain.PenQuickLogState

/**
 * Reserved pseudo widget id for the Quick Settings tile. AppWidgetManager
 * allocates ids from 1 upward and never issues Int.MAX_VALUE, so the tile can
 * reuse the existing commit, claim, and undo machinery without a parallel
 * implementation.
 */
const val PEN_TILE_WIDGET_ID: Int = Int.MAX_VALUE

internal data class PenTileModel(
    val label: String,
    val state: Int,
)

internal fun penTileState(
    penState: PenQuickLogState,
    pending: PenWidgetCommitPayload?,
): PenTileModel {
    if (pending != null) {
        return PenTileModel(label = "Undo", state = Tile.STATE_ACTIVE)
    }

    val loaded = penState as? PenQuickLogState.Loaded
        ?: return PenTileModel(label = "No cart loaded", state = Tile.STATE_UNAVAILABLE)

    if (loaded.secondsPerUse == null) {
        return PenTileModel(label = "No cart loaded", state = Tile.STATE_UNAVAILABLE)
    }

    val seconds = penWidgetPresetSeconds(loaded).firstOrNull() ?: STEP_SECONDS
    return PenTileModel(
        label = "${loaded.product.name} · ${seconds}s",
        state = Tile.STATE_INACTIVE,
    )
}
