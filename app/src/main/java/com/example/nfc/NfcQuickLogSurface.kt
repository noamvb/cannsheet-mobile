package com.example.nfc

/**
 * Reserved pseudo-surface ID for NFC quick-log commits. AppWidgetManager never allocates
 * [Int.MAX_VALUE] (the existing Quick Settings tile ID) or this adjacent value as a real
 * AppWidget ID. Keeping the value in the NFC package makes the shared outbox's ownership
 * explicit without making NFC part of the widget API.
 */
const val PEN_NFC_SURFACE_ID: Int = Int.MAX_VALUE - 1
