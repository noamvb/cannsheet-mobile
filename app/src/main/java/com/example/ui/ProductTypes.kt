package com.example.ui

import com.example.data.ProductTypeCodes

internal object ProductTypes {
    const val PEN = ProductTypeCodes.PEN

    /** Keep this exact order: it is the Purchase screen's dropdown order. */
    val CODES: List<String> = listOf("P", "E", "J", "F", "S", "K")

    fun normalize(type: String): String = ProductTypeCodes.normalize(type)

    /** Human label without repeating the canonical code. */
    fun label(type: String): String = ProductTypeCodes.displayLabel(type)

    /** "F — Flower" for known codes; the raw normalized code for anything else. */
    fun displayName(type: String): String {
        val code = normalize(type)
        return ProductTypeCodes.displayLabel(code).takeIf { it != code }?.let { "$code — $it" } ?: code
    }

    /** Canonical codes plus whatever the catalog actually contains, deduped and sorted. */
    fun options(catalogTypes: List<String>): List<String> =
        (CODES + catalogTypes)
            .map(::normalize)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
}
