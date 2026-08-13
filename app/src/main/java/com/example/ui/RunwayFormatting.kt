package com.example.ui

import com.example.domain.ProductRunway
import com.example.domain.RunwayBasis
import com.example.domain.RunwayConfidence
import com.example.domain.SpendRunRate
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

internal fun runwaySummaryText(runway: ProductRunway): String {
    val type = ProductTypes.label(runway.type)
    val productWord = if (runway.sampleSize == 1) "product" else "products"
    val gramEvidence = if (runway.basis == RunwayBasis.PER_GRAM) " with recorded grams" else ""
    val sample = "${runway.sampleSize} finished $type $productWord$gramEvidence"
    val evidence = when (runway.confidence) {
        RunwayConfidence.LOW -> "rough estimate from recorded totals for $sample"
        RunwayConfidence.MEDIUM -> "estimate from recorded totals for $sample"
        RunwayConfidence.HIGH -> "estimate from a good sample of recorded totals for $sample"
    }
    val rate = "a ${runway.effectiveBurnRateDays}-day recorded-use rate"

    return if (runway.estimatedRemainingToTypicalUses <= 0.0) {
        val typical = formatEstimatedUses(runway.estimatedTypicalFinishedUses)
        "At or past the usual $typical recorded at finish — $evidence and $rate."
    } else {
        val days = formatEstimatedDays(runway.estimatedDaysRemaining)
        val remaining = formatEstimatedUses(runway.estimatedRemainingToTypicalUses)
        val typical = formatEstimatedUses(runway.estimatedTypicalFinishedUses)
        "$days to the usual recorded finish amount; $remaining to the typical " +
            "$typical recorded at finish — $evidence and $rate."
    }
}

internal fun spendRunRateText(runRate: SpendRunRate): String {
    val purchaseWord = if (runRate.personalPurchaseCount == 1) "purchase" else "purchases"
    val estimatedDates = if (runRate.estimatedPersonalPurchaseDateCount > 0) {
        val dateWord = if (runRate.estimatedPersonalPurchaseDateCount == 1) "date was" else "dates were"
        " ${runRate.estimatedPersonalPurchaseDateCount} purchase $dateWord estimated from creation time."
    } else {
        ""
    }
    return "On track for ~${formatCadCents(runRate.projectedMonthEndCents)} this month — " +
        "estimate from ${formatCadCents(runRate.monthToDateCents)} personal spending through " +
        "day ${runRate.elapsedDays} of ${runRate.daysInMonth}. Based on " +
        "${runRate.personalPurchaseCount} recorded personal $purchaseWord.$estimatedDates"
}

internal fun formatRunwayNumber(value: Double): String {
    require(value.isFinite() && value >= 0.0) {
        "Runway values must be finite and nonnegative"
    }
    return BigDecimal.valueOf(if (value == -0.0) 0.0 else value)
        .setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}

private fun formatEstimatedDays(value: Double): String {
    require(value.isFinite() && value >= 0.0) {
        "Estimated days must be finite and nonnegative"
    }
    if (value < 0.01) return "<0.01 day"
    val formatted = formatRunwayNumber(value)
    return "~$formatted ${if (formatted == "1") "day" else "days"}"
}

private fun formatEstimatedUses(value: Double): String {
    require(value.isFinite() && value > 0.0) {
        "Estimated uses must be finite and positive"
    }
    val formatted = formatUsageAmount(value)
    if (formatted == "0") return "<0.000001 use"
    return "~$formatted ${if (formatted == "1") "use" else "uses"}"
}

internal fun formatCadCents(cents: Long): String =
    NumberFormat.getCurrencyInstance(Locale.CANADA).format(BigDecimal.valueOf(cents, 2))
