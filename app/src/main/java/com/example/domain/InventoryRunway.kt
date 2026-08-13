package com.example.domain

import com.example.data.AnalyticsProductDto
import com.example.data.AnalyticsRangeDto
import com.example.data.InsightsResponseDto
import com.example.data.MonthlySpendDto
import com.example.data.ProductTypeCodes
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** Minimum number of valid finished-product observations for any estimate. */
const val MIN_CAPACITY_SAMPLE: Int = 3

/** Minimum elapsed calendar days over which to estimate a burn rate. */
const val MIN_BURN_RATE_DAYS: Int = 7

enum class RunwayBasis {
    PER_GRAM,
    PER_PRODUCT,
}

enum class RunwayConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * Counts of usable finished-product observations, including types below the model threshold.
 */
data class TypeCapacityEvidence(
    val type: String,
    val sampleSize: Int,
    val perGramSampleSize: Int,
)

/**
 * Historical recorded usage at the point products of [type] were marked finished.
 *
 * These values estimate a typical recorded finish point, not physical capacity.
 */
data class TypeCapacityModel(
    val type: String,
    /** All valid finished-product quantities for this type. */
    val sampleSize: Int,
    val medianRecordedUsesAtFinish: Double,
    /** Null unless at least [MIN_CAPACITY_SAMPLE] finite positive ratios exist. */
    val medianRecordedUsesPerGramAtFinish: Double?,
    /** Number of observations actually used by the per-gram median. */
    val perGramSampleSize: Int,
)

data class ProductRunway(
    val productId: String,
    val type: String,
    val usesSoFar: Double,
    /** Estimated typical recorded amount at finish, not literal product capacity. */
    val estimatedTypicalFinishedUses: Double,
    val estimatedRemainingToTypicalUses: Double,
    val usesPerDay: Double,
    /** Inclusive calendar days in the product-specific burn-rate window. */
    val effectiveBurnRateDays: Int,
    val estimatedDaysRemaining: Double,
    val basis: RunwayBasis,
    val confidence: RunwayConfidence,
    /** Evidence used by the selected basis. */
    val sampleSize: Int,
)

data class SpendRunRate(
    val month: String,
    val monthToDateCents: Long,
    val elapsedDays: Int,
    val daysInMonth: Int,
    val projectedMonthEndCents: Long,
    val personalPurchaseCount: Int,
    /** Current-month personal purchases whose dates came from Created At. */
    val estimatedPersonalPurchaseDateCount: Int,
)

/**
 * Returns evidence counts even when a type has fewer than [MIN_CAPACITY_SAMPLE] observations.
 */
fun buildTypeCapacityEvidence(
    products: List<AnalyticsProductDto>,
): Map<String, TypeCapacityEvidence> = collectFinishSamples(products)
    .mapValues { (type, samples) ->
        TypeCapacityEvidence(
            type = type,
            sampleSize = samples.recordedUses.size,
            perGramSampleSize = samples.recordedUsesPerGram.size,
        )
    }

fun buildTypeCapacityModels(
    products: List<AnalyticsProductDto>,
): Map<String, TypeCapacityModel> = collectFinishSamples(products)
    .mapNotNull { (type, samples) ->
        if (samples.recordedUses.size < MIN_CAPACITY_SAMPLE) {
            null
        } else {
            type to TypeCapacityModel(
                type = type,
                sampleSize = samples.recordedUses.size,
                medianRecordedUsesAtFinish = median(samples.recordedUses),
                medianRecordedUsesPerGramAtFinish = samples.recordedUsesPerGram
                    .takeIf { it.size >= MIN_CAPACITY_SAMPLE }
                    ?.let(::median),
                perGramSampleSize = samples.recordedUsesPerGram.size,
            )
        }
    }
    .toMap()

fun buildProductRunway(
    product: AnalyticsProductDto,
    models: Map<String, TypeCapacityModel>,
    range: AnalyticsRangeDto,
    analyticsTimeZone: String,
): ProductRunway? {
    if (product.status != STATUS_ACTIVE) return null

    val normalizedType = ProductTypeCodes.normalize(product.type)
    if (!normalizedType.isEligibleRunwayType()) return null
    val model = models[normalizedType] ?: return null

    val usesSoFar = product.allTime.quantity
    if (!usesSoFar.isFinite() || usesSoFar < 0.0) return null

    val rangeUses = product.range.quantity
    if (!rangeUses.isFinite() || rangeUses <= 0.0) return null

    val validatedRange = validateRange(range) ?: return null
    val timeZone = analyticsTimeZone.toValidatedTimeZone() ?: return null
    val firstLogAt = product.allTime.firstLogAtEpochMillis ?: return null
    val firstLogDate = civilDateAt(firstLogAt, timeZone) ?: return null
    if (firstLogDate > validatedRange.to) return null

    val effectiveStart = maxOf(validatedRange.from, firstLogDate)
    val effectiveDaysLong = validatedRange.to.ordinal - effectiveStart.ordinal + 1L
    if (effectiveDaysLong !in MIN_BURN_RATE_DAYS.toLong()..Int.MAX_VALUE.toLong()) {
        return null
    }
    val effectiveBurnRateDays = effectiveDaysLong.toInt()

    val targetGrams = product.grams
    val perGramEstimate = model.medianRecordedUsesPerGramAtFinish
        ?.takeIf { model.perGramSampleSize >= MIN_CAPACITY_SAMPLE }
        ?.takeIf {
            targetGrams != null && targetGrams.isFinite() && targetGrams > 0.0
        }
        ?.let { it * requireNotNull(targetGrams) }

    val basis: RunwayBasis
    val estimatedTypicalFinishedUses: Double
    val evidenceSampleSize: Int
    if (perGramEstimate != null) {
        basis = RunwayBasis.PER_GRAM
        estimatedTypicalFinishedUses = perGramEstimate
        evidenceSampleSize = model.perGramSampleSize
    } else {
        basis = RunwayBasis.PER_PRODUCT
        estimatedTypicalFinishedUses = model.medianRecordedUsesAtFinish
        evidenceSampleSize = model.sampleSize
    }
    if (evidenceSampleSize < MIN_CAPACITY_SAMPLE) return null
    if (!estimatedTypicalFinishedUses.isFinite() || estimatedTypicalFinishedUses <= 0.0) {
        return null
    }

    val estimatedRemaining =
        (estimatedTypicalFinishedUses - usesSoFar).coerceAtLeast(0.0)
    val usesPerDay = rangeUses / effectiveBurnRateDays.toDouble()
    if (!usesPerDay.isFinite() || usesPerDay <= 0.0) return null
    val estimatedDaysRemaining = estimatedRemaining / usesPerDay
    if (!estimatedRemaining.isFinite() || !estimatedDaysRemaining.isFinite()) return null

    val confidence = when {
        basis == RunwayBasis.PER_GRAM && evidenceSampleSize >= HIGH_CONFIDENCE_SAMPLE ->
            RunwayConfidence.HIGH
        evidenceSampleSize >= MEDIUM_CONFIDENCE_SAMPLE -> RunwayConfidence.MEDIUM
        else -> RunwayConfidence.LOW
    }

    return ProductRunway(
        productId = product.productId,
        type = normalizedType,
        usesSoFar = usesSoFar,
        estimatedTypicalFinishedUses = estimatedTypicalFinishedUses,
        estimatedRemainingToTypicalUses = estimatedRemaining,
        usesPerDay = usesPerDay,
        effectiveBurnRateDays = effectiveBurnRateDays,
        estimatedDaysRemaining = estimatedDaysRemaining,
        basis = basis,
        confidence = confidence,
        sampleSize = evidenceSampleSize,
    )
}

/**
 * Projects personal spending only from a complete current-month-through-today snapshot.
 *
 * Requiring the whole response keeps range, products, spending, and time zone from being mixed
 * across snapshots. Ambiguous personal costs or dates suppress the estimate instead of silently
 * understating it.
 */
fun projectCurrentMonthSpend(
    insights: InsightsResponseDto,
    nowEpochMillis: Long,
): SpendRunRate? {
    if (insights.dataQuality.warnings.invalidUnreferencedPurchaseRowCount != 0) {
        return null
    }
    val timeZone = insights.timeZone.toValidatedTimeZone() ?: return null
    val today = civilDateAt(nowEpochMillis, timeZone) ?: return null
    val validatedRange = validateRange(insights.range) ?: return null
    if (validatedRange.to != today) return null

    val monthStart = CivilDate(today.year, today.month, 1)
    if (validatedRange.from > monthStart) return null

    val month = today.monthKey()
    val monthBuckets = insights.spending.byMonth.filter { it.month == month }
    if (monthBuckets.size != 1) return null
    val bucket = monthBuckets.single()
    if (!bucket.hasValidNonNegativeValues() || bucket.unknownPersonalCostCount > 0) {
        return null
    }

    var knownPersonalPurchaseCount = 0
    var knownPersonalSpendCents = 0L
    var estimatedPersonalPurchaseDateCount = 0

    for (product in insights.products) {
        val purchaseDate = product.trustedPurchaseDate()
        if (purchaseDate == null) {
            if (product.borrowed != true) return null
            continue
        }
        if (purchaseDate < monthStart || purchaseDate > today) continue

        when (product.borrowed) {
            null -> return null
            true -> Unit
            false -> {
                val finalCostCents = product.finalCostCents
                    ?.takeIf { it >= 0L }
                    ?: return null
                knownPersonalPurchaseCount++
                knownPersonalSpendCents = try {
                    Math.addExact(knownPersonalSpendCents, finalCostCents)
                } catch (_: ArithmeticException) {
                    return null
                }
                if (product.purchaseDateSource == PURCHASE_DATE_CREATED_AT_FALLBACK) {
                    estimatedPersonalPurchaseDateCount++
                }
            }
        }
    }

    if (
        knownPersonalPurchaseCount != bucket.personalPurchaseCount ||
        knownPersonalSpendCents != bucket.personalSpendCents
    ) {
        return null
    }

    val elapsedDays = today.day
    val daysInMonth = daysInMonth(today.year, today.month)
    val projectedMonthEndCents = if (elapsedDays == daysInMonth) {
        bucket.personalSpendCents
    } else {
        try {
            BigDecimal.valueOf(bucket.personalSpendCents)
                .multiply(BigDecimal.valueOf(daysInMonth.toLong()))
                .divide(
                    BigDecimal.valueOf(elapsedDays.toLong()),
                    0,
                    RoundingMode.HALF_UP,
                )
                .longValueExact()
        } catch (_: ArithmeticException) {
            return null
        }
    }

    return SpendRunRate(
        month = month,
        monthToDateCents = bucket.personalSpendCents,
        elapsedDays = elapsedDays,
        daysInMonth = daysInMonth,
        projectedMonthEndCents = projectedMonthEndCents,
        personalPurchaseCount = bucket.personalPurchaseCount,
        estimatedPersonalPurchaseDateCount = estimatedPersonalPurchaseDateCount,
    )
}

internal fun median(values: List<Double>): Double {
    require(values.isNotEmpty()) { "median requires at least one value" }
    require(values.all(Double::isFinite)) { "median requires finite values" }
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        val lower = sorted[middle - 1]
        val upper = sorted[middle]
        if (lower <= 0.0 && upper >= 0.0) {
            lower / 2.0 + upper / 2.0
        } else {
            lower + (upper - lower) / 2.0
        }
    }
}

private data class FinishSamples(
    val recordedUses: MutableList<Double> = mutableListOf(),
    val recordedUsesPerGram: MutableList<Double> = mutableListOf(),
)

private fun collectFinishSamples(
    products: List<AnalyticsProductDto>,
): Map<String, FinishSamples> {
    val samplesByType = sortedMapOf<String, FinishSamples>()
    products.forEach { product ->
        if (product.status != STATUS_FINISHED) return@forEach
        val type = ProductTypeCodes.normalize(product.type)
        if (!type.isEligibleRunwayType()) return@forEach
        val recordedUses = product.allTime.quantity
        if (!recordedUses.isFinite() || recordedUses <= 0.0) return@forEach

        val samples = samplesByType.getOrPut(type, ::FinishSamples)
        samples.recordedUses += recordedUses
        val grams = product.grams
        if (grams != null && grams.isFinite() && grams > 0.0) {
            val ratio = recordedUses / grams
            if (ratio.isFinite() && ratio > 0.0) {
                samples.recordedUsesPerGram += ratio
            }
        }
    }
    return samplesByType
}

private fun String.isEligibleRunwayType(): Boolean = isNotBlank() && this != TYPE_UNKNOWN

private data class ValidatedRange(
    val from: CivilDate,
    val to: CivilDate,
)

private fun validateRange(range: AnalyticsRangeDto): ValidatedRange? {
    val from = CivilDate.parse(range.from) ?: return null
    val to = CivilDate.parse(range.to) ?: return null
    if (from > to) return null
    val dayCount = to.ordinal - from.ordinal + 1L
    if (dayCount != range.dayCount.toLong()) return null
    return ValidatedRange(from = from, to = to)
}

private data class CivilDate(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<CivilDate> {
    val ordinal: Long = daysBeforeYear(year) + daysBeforeMonth(year, month) + day - 1L

    override fun compareTo(other: CivilDate): Int = ordinal.compareTo(other.ordinal)

    fun monthKey(): String =
        year.toString().padStart(4, '0') + "-" + month.toString().padStart(2, '0')

    companion object {
        fun parse(value: String?): CivilDate? {
            if (
                value == null || value.length != 10 || value[4] != '-' || value[7] != '-' ||
                value.indices.any { index -> index != 4 && index != 7 && !value[index].isDigit() }
            ) {
                return null
            }
            val year = value.substring(0, 4).toIntOrNull() ?: return null
            val month = value.substring(5, 7).toIntOrNull() ?: return null
            val day = value.substring(8, 10).toIntOrNull() ?: return null
            if (year !in MIN_YEAR..MAX_YEAR || month !in 1..12) return null
            if (day !in 1..daysInMonth(year, month)) return null
            return CivilDate(year = year, month = month, day = day)
        }
    }
}

private fun String.toValidatedTimeZone(): TimeZone? {
    if (this !in AVAILABLE_TIME_ZONE_IDS) return null
    return TimeZone.getTimeZone(this)
}

private fun civilDateAt(epochMillis: Long, timeZone: TimeZone): CivilDate? {
    val calendar = GregorianCalendar(timeZone, Locale.ROOT).apply {
        isLenient = false
        gregorianChange = Date(Long.MIN_VALUE)
        timeInMillis = epochMillis
    }
    if (calendar.get(Calendar.ERA) != GregorianCalendar.AD) return null
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH) + 1
    val day = calendar.get(Calendar.DAY_OF_MONTH)
    if (year !in MIN_YEAR..MAX_YEAR) return null
    return CivilDate(year = year, month = month, day = day)
}

private fun AnalyticsProductDto.trustedPurchaseDate(): CivilDate? {
    if (
        purchaseDateSource != PURCHASE_DATE_RECORDED &&
        purchaseDateSource != PURCHASE_DATE_CREATED_AT_FALLBACK
    ) {
        return null
    }
    return CivilDate.parse(purchaseDate)
}

private fun MonthlySpendDto.hasValidNonNegativeValues(): Boolean =
    personalSpendCents >= 0L &&
        personalPurchaseCount >= 0 &&
        borrowedRecordedValueCents >= 0L &&
        borrowedPurchaseCount >= 0 &&
        unknownPersonalCostCount >= 0 &&
        unknownBorrowedCostCount >= 0 &&
        estimatedDateCount >= 0 &&
        unknownDateCount >= 0

private fun daysBeforeYear(year: Int): Long {
    val completedYears = year - 1L
    return 365L * completedYears +
        completedYears / 4L -
        completedYears / 100L +
        completedYears / 400L
}

private fun daysBeforeMonth(year: Int, month: Int): Long {
    var days = 0L
    for (candidate in 1 until month) {
        days += daysInMonth(year, candidate)
    }
    return days
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (isLeapYear(year)) 29 else 28
    else -> error("month must be in 1..12")
}

private fun isLeapYear(year: Int): Boolean =
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

private const val STATUS_ACTIVE = "ACTIVE"
private const val STATUS_FINISHED = "FINISHED"
private const val TYPE_UNKNOWN = "UNKNOWN"
private const val PURCHASE_DATE_RECORDED = "RECORDED"
private const val PURCHASE_DATE_CREATED_AT_FALLBACK = "CREATED_AT_FALLBACK"
private const val MEDIUM_CONFIDENCE_SAMPLE = 5
private const val HIGH_CONFIDENCE_SAMPLE = 8
private const val MIN_YEAR = 1
private const val MAX_YEAR = 9999
private val AVAILABLE_TIME_ZONE_IDS: Set<String> = TimeZone.getAvailableIDs().toSet()
