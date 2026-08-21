package com.example.ui

import com.example.domain.MIN_BURN_RATE_DAYS
import com.example.domain.ProductRunway
import com.example.domain.RunwayBasis
import com.example.domain.RunwayConfidence
import com.example.domain.RunwayPace
import com.example.domain.SpendRunRate
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

internal fun runwaySummaryText(runway: ProductRunway): String {
    val type = ProductTypes.label(runway.type)
    val productWord = if (runway.sampleSize == 1) "product" else "products"
    val sample = when (runway.basis) {
        RunwayBasis.MATCHED_GRAMS -> {
            val grams = formatRunwayNumber(checkNotNull(runway.targetGrams))
            "${runway.sampleSize} finished $type $productWord at $grams g"
        }
        RunwayBasis.PER_GRAM -> {
            val grams = formatRunwayNumber(checkNotNull(runway.targetGrams))
            "${runway.sampleSize} finished $type $productWord with recorded grams, " +
                "adjusted to $grams g"
        }
        RunwayBasis.PER_PRODUCT -> "${runway.sampleSize} finished $type $productWord"
    }
    val evidence = when (runway.confidence) {
        RunwayConfidence.LOW -> "rough estimate from recorded totals for $sample"
        RunwayConfidence.MEDIUM -> "estimate from recorded totals for $sample"
        RunwayConfidence.HIGH -> "estimate from a good sample of recorded totals for $sample"
    }

    val capacity = if (runway.estimatedRemainingToTypicalUses <= 0.0) {
        val typical = formatEstimatedUses(runway.estimatedTypicalFinishedUses)
        "At or past the usual $typical recorded at finish"
    } else {
        val remaining = formatEstimatedUses(runway.estimatedRemainingToTypicalUses)
        val typical = formatEstimatedUses(runway.estimatedTypicalFinishedUses)
        "$remaining to the typical $typical recorded at finish"
    }

    return when (val pace = runway.pace) {
        is RunwayPace.Ready -> {
            val rate = "a ${pace.effectiveBurnRateDays}-day recorded-use rate"
            if (runway.estimatedRemainingToTypicalUses <= 0.0) {
                "$capacity — $evidence and $rate."
            } else {
                val days = formatEstimatedDays(pace.estimatedDaysRemaining)
                "$days to the usual recorded finish amount; $capacity — $evidence and $rate."
            }
        }
        else -> "$capacity — $evidence. ${runwayPaceUnavailableText(pace)}"
    }
}

private fun runwayPaceUnavailableText(pace: RunwayPace): String = when (pace) {
    is RunwayPace.Ready -> error("A ready pace has no unavailable explanation")
    RunwayPace.SelectedRangeTooShort ->
        "Pick a range of at least $MIN_BURN_RATE_DAYS days to estimate days remaining."
    RunwayPace.NoUseInRange ->
        "An estimate of days remaining needs recorded use in this range."
    RunwayPace.MissingFirstLog ->
        "Days remaining is unavailable because this snapshot has no first recorded use."
    is RunwayPace.TooFewEffectiveDays -> {
        val noun = if (pace.effectiveBurnRateDays == 1) "day" else "days"
        "Days remaining will appear after at least $MIN_BURN_RATE_DAYS effective days " +
            "of recorded use; " +
            "${pace.effectiveBurnRateDays} $noun available."
    }
    RunwayPace.InvalidSnapshot ->
        "Days remaining is unavailable from this snapshot."
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
