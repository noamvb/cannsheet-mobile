package com.example.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Uses are the stored and transmitted unit. Seconds are an input and display unit only.
 * Conversions go through BigDecimal so a rate like 10 never introduces binary drift.
 */
fun usesToSeconds(uses: Double, secondsPerUse: Double): Double =
    BigDecimal.valueOf(uses)
        .multiply(BigDecimal.valueOf(secondsPerUse))
        .setScale(STORAGE_SCALE, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
        .toDouble()

fun secondsToUses(seconds: Double, secondsPerUse: Double): Double =
    BigDecimal.valueOf(seconds)
        .divide(BigDecimal.valueOf(secondsPerUse), STORAGE_SCALE, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
        .toDouble()

/** "15s" when [secondsPerUse] is set, otherwise the plain use count "1.5". */
fun formatQuantityInInputUnit(uses: Double, secondsPerUse: Double?): String {
    val value = if (secondsPerUse == null) {
        BigDecimal.valueOf(uses).setScale(STORAGE_SCALE, RoundingMode.HALF_UP)
    } else {
        BigDecimal.valueOf(usesToSeconds(uses, secondsPerUse))
            .setScale(PRESENTATION_SCALE, RoundingMode.HALF_UP)
    }
    val formatted = value.stripTrailingZeros().toPlainString()
    return if (secondsPerUse == null) formatted else "${formatted}s"
}

private const val STORAGE_SCALE = 6
private const val PRESENTATION_SCALE = 3
