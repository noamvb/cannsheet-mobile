package com.example.domain

import com.example.data.AnalyticsRangeDto
import com.example.data.DataQualityDto
import com.example.data.HourActivityDto
import com.example.data.InsightsResponseDto
import com.example.data.InventoryDto
import com.example.data.OverviewDto
import com.example.data.QualityWarningsDto
import com.example.data.SourceRevisionDto
import com.example.data.SpendBucketDto
import com.example.data.SpendingDto
import com.example.data.SyncHealthDto
import com.example.data.TypeBreakdownDto
import com.example.data.WeekdayActivityDto
import com.example.ui.InsightsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapper decides what the model is allowed to say, and the gate decides whether it may
 * speak at all. Both are asserted rather than assumed, because a defect in either produces
 * confident prose about data the app itself refuses to present as current.
 */
class CannsheetLlmFactsTest {

    private fun bucket(
        spendCents: Long = 12_345L,
        purchases: Int = 4,
        unknownCost: Int = 0,
    ) = SpendBucketDto(
        personalSpendCents = spendCents,
        personalPurchaseCount = purchases,
        borrowedRecordedValueCents = 0,
        borrowedPurchaseCount = 0,
        unknownPersonalCostCount = unknownCost,
        unknownBorrowedCostCount = 0,
        estimatedDateCount = 0,
        unknownDateCount = 0,
    )

    private fun response(
        logCount: Int = 42,
        activeDays: Int = 18,
        distinctProducts: Int = 6,
        daysSinceLastLog: Int? = 2,
        complete: Boolean = true,
        warnings: QualityWarningsDto = QualityWarningsDto(),
        spendRange: SpendBucketDto = bucket(),
        scope: String = "days",
        dayCount: Int = 30,
    ) = InsightsResponseDto(
        success = true,
        analyticsVersion = 1,
        resource = "insights",
        environment = "PRODUCTION",
        timeZone = "America/Toronto",
        range = AnalyticsRangeDto(scope = scope, from = "2026-07-19", to = "2026-08-18", dayCount = dayCount),
        overview = OverviewDto(
            logCount = logCount,
            activeDayCount = activeDays,
            distinctProductCount = distinctProducts,
            daysSinceLastLog = daysSinceLastLog,
        ),
        dailyActivity = emptyList(),
        byWeekday = listOf(WeekdayActivityDto(1, 3), WeekdayActivityDto(5, 11)),
        byHour = listOf(HourActivityDto(9, 2), HourActivityDto(21, 14)),
        inventory = InventoryDto(
            activeCount = 3,
            unopenedCount = 2,
            finishedCount = 7,
            unknownStatusCount = 0,
            currentPersonalOriginalCostCents = 0,
            currentBorrowedRecordedValueCents = 0,
            unknownCurrentCostCount = 0,
        ),
        byType = listOf(
            TypeBreakdownDto("Flower", 9, 2, 1, 0, 3, 0, 5_000, 2, 0, 0, 0),
            TypeBreakdownDto("Vape", 25, 3, 2, 1, 4, 0, 7_000, 2, 0, 0, 0),
        ),
        products = emptyList(),
        spending = SpendingDto(allTime = bucket(), range = spendRange, byMonth = emptyList()),
        syncHealth = SyncHealthDto(coverage = "full", acknowledgedRequestCount30d = 5, partialRequestCount30d = 0),
        dataQuality = DataQualityDto(complete = complete, warnings = warnings),
        sourceRevision = SourceRevisionDto(dataVersion = "v1", purchaseRowCount = 10, eventRowCount = 42),
        generatedAtEpochMillis = 1_787_000_000_000L,
        serverDurationMs = 120,
    )

    private fun state(
        data: InsightsResponseDto? = response(),
        fromCache: Boolean = false,
        stale: Boolean = false,
        pendingRange: com.example.data.InsightsRange? = null,
        refreshing: Boolean = false,
        initialLoading: Boolean = false,
        error: com.example.ui.AnalyticsUiError? = null,
    ) = InsightsUiState(
        data = data,
        pendingRange = pendingRange,
        isInitialLoading = initialLoading,
        isRefreshing = refreshing,
        isFromCache = fromCache,
        isStale = stale,
        error = error,
    )

    private fun valueOf(facts: List<com.noamv.localllm.contract.Fact>, label: String) =
        facts.firstOrNull { it.label == label }?.value

    // ---- the gate ----

    @Test
    fun `a live settled snapshot may be summarised`() {
        assertTrue(CannsheetLlmFacts.shouldSummarise(state(), pendingActionCount = 0))
    }

    @Test
    fun `a cached or stale snapshot is never summarised`() {
        assertFalse(CannsheetLlmFacts.shouldSummarise(state(fromCache = true), 0))
        assertFalse(CannsheetLlmFacts.shouldSummarise(state(stale = true), 0))
    }

    @Test
    fun `queued local actions suppress the summary`() {
        assertFalse(CannsheetLlmFacts.shouldSummarise(state(), pendingActionCount = 1))
    }

    @Test
    fun `an unknown queue depth suppresses the summary just as a non-zero one does`() {
        // The screen masks this with `?: 0`; the summary must not inherit that assumption.
        assertFalse(CannsheetLlmFacts.shouldSummarise(state(), pendingActionCount = null))
    }

    @Test
    fun `a range change in flight suppresses the summary`() {
        assertFalse(
            CannsheetLlmFacts.shouldSummarise(
                state(pendingRange = com.example.data.InsightsRange.Default),
                0,
            ),
        )
    }

    @Test
    fun `loading, refreshing, error and absent snapshots suppress the summary`() {
        assertFalse(CannsheetLlmFacts.shouldSummarise(state(refreshing = true), 0))
        assertFalse(CannsheetLlmFacts.shouldSummarise(state(initialLoading = true), 0))
        assertFalse(CannsheetLlmFacts.shouldSummarise(state(data = null), 0))
    }

    @Test
    fun `too few logs suppresses the summary`() {
        assertFalse(CannsheetLlmFacts.shouldSummarise(state(response(logCount = 2)), 0))
        assertTrue(CannsheetLlmFacts.shouldSummarise(state(response(logCount = 3)), 0))
    }

    // ---- the facts ----

    @Test
    fun `recorded aggregates are reported`() {
        val f = CannsheetLlmFacts.from(response())
        assertEquals("42", valueOf(f, "Consumption entries recorded"))
        assertEquals("18 of 30", valueOf(f, "Days with any activity"))
        assertEquals("6", valueOf(f, "Distinct products used"))
        assertEquals("2", valueOf(f, "Days since the last entry"))
        assertEquals("Vape", valueOf(f, "Most used product type"))
        assertEquals("Friday", valueOf(f, "Busiest day of the week"))
        assertEquals("evening", valueOf(f, "Most common time of day"))
        assertEquals("3", valueOf(f, "Products currently open"))
    }

    @Test
    fun `no projection or runway figure is ever sent`() {
        val labels = CannsheetLlmFacts.from(response()).map { it.label.lowercase() }
        listOf("runway", "project", "forecast", "estimate", "per day", "will last", "remaining").forEach { banned ->
            assertFalse("leaked a projection: $banned", labels.any { it.contains(banned) })
        }
    }

    @Test
    fun `recorded spend is reported with its unknown-cost caveat`() {
        val f = CannsheetLlmFacts.from(response(spendRange = bucket(spendCents = 12_345, purchases = 4, unknownCost = 2)))
        val fact = f.first { it.label == "Recorded spend in this range" }
        assertTrue(fact.note!!.contains("across 4 purchases"))
        assertTrue(fact.note!!.contains("2 more have no recorded cost"))
    }

    @Test
    fun `spend is omitted entirely when nothing was purchased`() {
        val f = CannsheetLlmFacts.from(response(spendRange = bucket(spendCents = 0, purchases = 0)))
        assertNull(valueOf(f, "Recorded spend in this range"))
    }

    @Test
    fun `incomplete data is surfaced so the model can qualify`() {
        val f = CannsheetLlmFacts.from(
            response(
                complete = false,
                warnings = QualityWarningsDto(unknownPurchaseDateCount = 3, unknownStatusCount = 1),
            ),
        )
        val fact = f.first { it.label == "Data completeness" }
        assertEquals("incomplete", fact.value)
        assertTrue(fact.note!!.contains("3 purchases have no date"))
        assertTrue(fact.note!!.contains("1 products have unknown status"))
    }

    @Test
    fun `complete data adds no completeness fact`() {
        assertNull(valueOf(CannsheetLlmFacts.from(response(complete = true)), "Data completeness"))
    }

    @Test
    fun `the period comes from the response, not the device clock`() {
        val p = CannsheetLlmFacts.period(response(dayCount = 90))
        assertEquals("the last 90 days", p.label)
        assertEquals("2026-07-19", p.start)
        assertEquals("2026-08-18", p.end)
        assertEquals("all recorded activity", CannsheetLlmFacts.period(response(scope = "all")).label)
    }

    @Test
    fun `every fact carries a non-blank label and value`() {
        CannsheetLlmFacts.from(response()).forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue("blank value for ${it.label}", it.value.isNotBlank())
        }
    }
}
