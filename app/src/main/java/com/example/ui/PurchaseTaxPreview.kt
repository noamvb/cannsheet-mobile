package com.example.ui

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
    return if (postTax) {
        "Estimated total before tax · $money"
    } else {
        "Estimated total with tax · $money"
    }
}
