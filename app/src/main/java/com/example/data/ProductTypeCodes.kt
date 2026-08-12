package com.example.data

import java.util.Locale

/** Single source of truth for product type codes usable by data and widget code. */
internal object ProductTypeCodes {
    const val PEN = "P"

    fun normalize(type: String): String = type.trim().uppercase(Locale.ROOT)
}
