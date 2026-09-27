package com.example.wear

import android.service.quicksettings.Tile

const val WEAR_PEN_TAP_PATH = "/cannsheet/pen/tap"
const val WEAR_PEN_REFRESH_PATH = "/cannsheet/pen/refresh"
const val WEAR_PEN_STATE_PATH = "/cannsheet/pen/state"
const val WEAR_PEN_PROTOCOL_VERSION = 1

enum class WearPenAction(val wire: String) {
    LOG("log"),
    UNDO("undo"),
    UNAVAILABLE("unavailable"),
}

internal fun wearPenAction(tileState: Int): WearPenAction = when (tileState) {
    Tile.STATE_INACTIVE -> WearPenAction.LOG
    Tile.STATE_ACTIVE -> WearPenAction.UNDO
    Tile.STATE_UNAVAILABLE -> WearPenAction.UNAVAILABLE
    else -> WearPenAction.UNAVAILABLE
}

internal fun wearPenStateFields(
    label: String,
    action: WearPenAction,
    stampMillis: Long,
): Map<String, Any> = mapOf(
    "v" to WEAR_PEN_PROTOCOL_VERSION,
    "label" to label,
    "action" to action.wire,
    "stampMillis" to stampMillis,
)
