package com.example.ui

import java.util.Locale

internal object ProductTypes {
    /** Keep this exact order: it is the Purchase screen's dropdown order. */
    val CODES: List<String> = listOf("P", "E", "J", "F", "S", "K")

    private val LABELS: Map<String, String> = mapOf(
        "P" to "Pen",
        "E" to "Edible",
        "J" to "Joint",
        "F" to "Flower",
        "S" to "Shatter",
        "K" to "Keef",
    )

    fun normalize(type: String): String = type.trim().uppercase(Locale.ROOT)

    /** "F — Flower" for known codes; the raw normalized code for anything else. */
    fun displayName(type: String): String {
        val code = normalize(type)
        return LABELS[code]?.let { "$code — $it" } ?: code
    }

    /** Canonical codes plus whatever the catalog actually contains, deduped and sorted. */
    fun options(catalogTypes: List<String>): List<String> =
        (CODES + catalogTypes)
            .map(::normalize)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
}
