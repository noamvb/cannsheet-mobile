package com.example.ui

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Uses are the stored and transmitted unit. Seconds are an input and display unit only.
 * Conversions go through BigDecimal so a rate like 10 never introduces binary drift.
 */
internal fun usesToSeconds(uses: Double, secondsPerUse: Double): Double =
    BigDecimal.valueOf(uses)
        .multiply(BigDecimal.valueOf(secondsPerUse))
        .setScale(6, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
        .toDouble()

internal fun secondsToUses(seconds: Double, secondsPerUse: Double): Double =
    BigDecimal.valueOf(seconds)
        .divide(BigDecimal.valueOf(secondsPerUse), 6, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
        .toDouble()

/** "15s" when [secondsPerUse] is set, otherwise the plain use count "1.5". */
internal fun formatQuantityInInputUnit(uses: Double, secondsPerUse: Double?): String {
    val value = if (secondsPerUse == null) {
        BigDecimal.valueOf(uses)
    } else {
        BigDecimal.valueOf(usesToSeconds(uses, secondsPerUse))
    }
    val formatted = value
        .setScale(6, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
    return if (secondsPerUse == null) formatted else "${formatted}s"
}
