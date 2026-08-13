package com.example.ui

import com.example.domain.ProductRunway
import com.example.domain.RunwayBasis
import com.example.domain.RunwayConfidence
import com.example.domain.SpendRunRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunwayFormattingTest {
    @Test
    fun runwayNumbersRoundTinyAndLargeValuesWithoutIntegerOverflow() {
        assertEquals("0.04", formatRunwayNumber(0.04))
        assertEquals("0.05", formatRunwayNumber(0.05))
        assertEquals("0", formatRunwayNumber(-0.0))
        assertEquals("1234567890123.45", formatRunwayNumber(1_234_567_890_123.45))
    }

    @Test
    fun runwayNumbersRejectNegativeAndNonFiniteInputs() {
        listOf(-0.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                formatRunwayNumber(value)
            }
        }
    }

    @Test
    fun perGramHighConfidenceSummaryNamesEveryEvidenceBoundary() {
        val text = runwaySummaryText(
            runway(
                basis = RunwayBasis.PER_GRAM,
                confidence = RunwayConfidence.HIGH,
                sampleSize = 8,
                effectiveBurnRateDays = 14,
            ),
        )

        assertTrue(text.contains("~4.45 days"))
        assertTrue(text.contains("~2.25 uses"))
        assertTrue(text.contains("typical ~12.75 uses recorded at finish"))
        assertTrue(text.contains("good sample"))
        assertTrue(text.contains("8 finished Pen products with recorded grams"))
        assertTrue(text.contains("14-day recorded-use rate"))
    }

    @Test
    fun lowConfidencePerProductSummaryUsesSingularEvidenceAndNoGramClaim() {
        val text = runwaySummaryText(
            runway(
                basis = RunwayBasis.PER_PRODUCT,
                confidence = RunwayConfidence.LOW,
                sampleSize = 1,
            ),
        )

        assertTrue(text.contains("rough estimate"))
        assertTrue(text.contains("1 finished Pen product"))
        assertFalse(text.contains("products"))
        assertFalse(text.contains("recorded grams"))
    }

    @Test
    fun subHundredthDayEstimateNeverRoundsToZero() {
        val text = runwaySummaryText(
            runway(
                estimatedDays = 0.004,
                basis = RunwayBasis.PER_PRODUCT,
                confidence = RunwayConfidence.LOW,
                sampleSize = 3,
            ),
        )

        assertTrue(text.startsWith("<0.01 day"))
        assertFalse(text.contains("0 days"))
    }

    @Test
    fun positiveSubPrecisionUsesNeverRenderAsZero() {
        val text = runwaySummaryText(
            runway(
                estimatedRemaining = 0.0000001,
                estimatedDays = 0.004,
                basis = RunwayBasis.PER_PRODUCT,
                confidence = RunwayConfidence.LOW,
                sampleSize = 3,
            ),
        )

        assertTrue(text.contains("<0.000001 use"))
        assertFalse(text.contains("~0 uses"))
    }

    @Test
    fun positiveRemainderWithUnderflowedZeroDaysDoesNotCrashOrClaimZeroDays() {
        val text = runwaySummaryText(
            runway(
                estimatedRemaining = 0.0000001,
                estimatedDays = 0.0,
                basis = RunwayBasis.PER_PRODUCT,
                confidence = RunwayConfidence.LOW,
                sampleSize = 3,
            ),
        )

        assertTrue(text.startsWith("<0.01 day"))
        assertFalse(text.contains("0 days"))
    }

    @Test
    fun mediumConfidenceAndPastTypicalTotalRemainQualifiedAsEstimates() {
        val medium = runwaySummaryText(
            runway(
                basis = RunwayBasis.PER_PRODUCT,
                confidence = RunwayConfidence.MEDIUM,
                sampleSize = 5,
            ),
        )
        val past = runwaySummaryText(
            runway(
                estimatedRemaining = 0.0,
                estimatedDays = 0.0,
                basis = RunwayBasis.PER_PRODUCT,
                confidence = RunwayConfidence.LOW,
                sampleSize = 3,
            ),
        )

        assertTrue(medium.contains("— estimate from recorded totals"))
        assertTrue(past.startsWith("At or past the usual ~12.75 uses recorded at finish"))
        assertTrue(past.contains("rough estimate"))
        assertTrue(past.contains("3 finished Pen products"))
        assertFalse(past.contains("days left"))
    }

    @Test
    fun currencyUsesExactCentsAcrossTinyAndLargeAmounts() {
        assertEquals("\$0.01", formatCadCents(1))
        assertEquals("\$1,234.56", formatCadCents(123_456))
        assertEquals("\$92,233,720,368,547,758.07", formatCadCents(Long.MAX_VALUE))
    }

    @Test
    fun spendSummaryUsesSingularPurchaseAndEstimatedDateGrammar() {
        val text = spendRunRateText(
            spendRunRate(
                personalPurchaseCount = 1,
                estimatedPersonalPurchaseDateCount = 1,
            ),
        )

        assertTrue(text.contains("~\$1,234.56 this month"))
        assertTrue(text.contains("\$12.34 personal spending through day 1 of 31"))
        assertTrue(text.contains("1 recorded personal purchase."))
        assertTrue(text.contains("1 purchase date was estimated from creation time."))
        assertFalse(text.contains("1 recorded personal purchases"))
    }

    @Test
    fun spendSummaryUsesPluralGrammarAndOmitsAbsentDateQualification() {
        val multiple = spendRunRateText(
            spendRunRate(
                personalPurchaseCount = 2,
                estimatedPersonalPurchaseDateCount = 2,
            ),
        )
        val none = spendRunRateText(
            spendRunRate(
                personalPurchaseCount = 0,
                estimatedPersonalPurchaseDateCount = 0,
            ),
        )

        assertTrue(multiple.contains("2 recorded personal purchases."))
        assertTrue(multiple.contains("2 purchase dates were estimated from creation time."))
        assertTrue(none.contains("0 recorded personal purchases."))
        assertFalse(none.contains("purchase date"))
    }

    private fun runway(
        estimatedRemaining: Double = 2.25,
        estimatedDays: Double = 4.45,
        basis: RunwayBasis,
        confidence: RunwayConfidence,
        sampleSize: Int,
        effectiveBurnRateDays: Int = 9,
    ) = ProductRunway(
        productId = "product-id",
        type = "P",
        usesSoFar = 10.5,
        estimatedTypicalFinishedUses = 12.75,
        estimatedRemainingToTypicalUses = estimatedRemaining,
        usesPerDay = 0.5,
        effectiveBurnRateDays = effectiveBurnRateDays,
        estimatedDaysRemaining = estimatedDays,
        basis = basis,
        confidence = confidence,
        sampleSize = sampleSize,
    )

    private fun spendRunRate(
        personalPurchaseCount: Int,
        estimatedPersonalPurchaseDateCount: Int,
    ) = SpendRunRate(
        month = "2026-07",
        monthToDateCents = 1_234,
        elapsedDays = 1,
        daysInMonth = 31,
        projectedMonthEndCents = 123_456,
        personalPurchaseCount = personalPurchaseCount,
        estimatedPersonalPurchaseDateCount = estimatedPersonalPurchaseDateCount,
    )
}
