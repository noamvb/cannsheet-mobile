package com.example.ui

import java.util.Locale

/** Returns a presentation-only preview that is never persisted or transmitted. */
internal fun purchaseTaxPreview(
    cost: String,
    postTax: Boolean,
    taxRate: Double?,
): String? {
    val parsedCost = cost.toDoubleOrNull()
    if (parsedCost == null || !parsedCost.isFinite() || parsedCost < 0.0) return null
    if (taxRate == null || !taxRate.isFinite() || taxRate < 0.0 || taxRate >= 1.0) {
        return "Tax rate not synced yet"
    }

    val converted = if (postTax) {
        parsedCost / (1.0 + taxRate)
    } else {
        parsedCost * (1.0 + taxRate)
    }
    if (!converted.isFinite() || converted >= 1_000_000.0) return null

    val money = formatCadCents(Math.round(converted * 100.0))
    val percent = "%.2f".format(Locale.CANADA, taxRate * 100.0).trimEnd('0').trimEnd('.')
    return if (postTax) {
        "$money before $percent% tax"
    } else {
        "$money with $percent% tax"
    }
}
