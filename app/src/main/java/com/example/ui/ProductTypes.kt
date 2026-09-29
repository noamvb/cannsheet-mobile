package com.example.ui

import androidx.compose.ui.graphics.Color
import com.example.data.ProductTypeCodes

internal object ProductTypes {
    const val PEN = ProductTypeCodes.PEN

    /** Keep this exact order: it is the Purchase screen's dropdown order. */
    val CODES: List<String> = listOf("P", "E", "J", "F", "S", "K")

    data class CategoryColor(val light: Color, val dark: Color)

    private val categoryPalette: Map<String, CategoryColor> = mapOf(
        "P" to CategoryColor(Color(0xFF8B2522), Color(0xFFE57373)),
        "E" to CategoryColor(Color(0xFF2E6930), Color(0xFF81C784)),
        "J" to CategoryColor(Color(0xFF1565C0), Color(0xFF64B5F6)),
        "F" to CategoryColor(Color(0xFFB25E00), Color(0xFFFFB74D)),
        "S" to CategoryColor(Color(0xFF6A1B9A), Color(0xFFBA68C8)),
        "K" to CategoryColor(Color(0xFF00695C), Color(0xFF4DB6AC)),
    )

    fun categoryColor(type: String, isDark: Boolean): Color {
        val code = normalize(type)
        val palette = categoryPalette[code]
        return if (palette != null) {
            if (isDark) palette.dark else palette.light
        } else {
            if (isDark) Color(0xFF83D0C2) else Color(0xFF145F58)
        }
    }

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

