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
        byWeekday: List<WeekdayActivityDto> = listOf(WeekdayActivityDto(1, 3), WeekdayActivityDto(5, 11)),
        byHour: List<HourActivityDto> = listOf(HourActivityDto(9, 2), HourActivityDto(21, 14)),
        byType: List<TypeBreakdownDto> = listOf(
            TypeBreakdownDto("Flower", 9, 2, 1, 0, 3, 0, 5_000, 2, 0, 0, 0),
            TypeBreakdownDto("Vape", 25, 3, 2, 1, 4, 0, 7_000, 2, 0, 0, 0),
        ),
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
        byWeekday = byWeekday,
        byHour = byHour,
        inventory = InventoryDto(
            activeCount = 3,
            unopenedCount = 2,
            finishedCount = 7,
            unknownStatusCount = 0,
            currentPersonalOriginalCostCents = 0,
            currentBorrowedRecordedValueCents = 0,
            unknownCurrentCostCount = 0,
        ),
        byType = byType,
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
        assertEquals("Vape", valueOf(f, "Most frequently logged product type"))
        assertEquals("Friday", valueOf(f, "Most frequently logged weekday"))
        assertEquals("evening", valueOf(f, "Most frequently logged time of day"))
        assertEquals("3", valueOf(f, "Products currently open"))
    }

    @Test
    fun `no projection or runway language is ever sent in a serialized fact field`() {
        val serializedFields = CannsheetLlmFacts.from(response()).flatMap { fact ->
            listOfNotNull(fact.label, fact.value, fact.note).map(String::lowercase)
        }
        listOf("runway", "project", "forecast", "estimate", "per day", "will last", "remaining").forEach { banned ->
            assertFalse(
                "leaked a projection through a serialized fact field: $banned",
                serializedFields.any { it.contains(banned) },
            )
        }
    }

    @Test
    fun `all-known recorded spend uses every purchase as its cost coverage denominator`() {
        val f = CannsheetLlmFacts.from(response(spendRange = bucket(spendCents = 12_345, purchases = 4)))
        val fact = f.first { it.label == "Recorded spend in this range" }
        assertEquals("\$123.45", fact.value)
        assertEquals("across 4 of 4 purchases with recorded costs", fact.note)
    }

    @Test
    fun `partial recorded spend uses only known-cost purchases and reports the missing coverage`() {
        val f = CannsheetLlmFacts.from(response(spendRange = bucket(spendCents = 12_345, purchases = 4, unknownCost = 2)))
        val fact = f.first { it.label == "Recorded spend in this range" }
        assertEquals("\$123.45", fact.value)
        assertEquals(
            "across 2 of 4 purchases with recorded costs; 2 purchases have no recorded cost",
            fact.note,
        )
    }

    @Test
    fun `all-unknown recorded spend is omitted instead of being narrated as zero`() {
        val f = CannsheetLlmFacts.from(response(spendRange = bucket(spendCents = 0, purchases = 4, unknownCost = 4)))
        assertNull(valueOf(f, "Recorded spend in this range"))
    }

    @Test
    fun `inconsistent unknown-cost coverage fails closed instead of exceeding its denominator`() {
        val f = CannsheetLlmFacts.from(
            response(spendRange = bucket(spendCents = 12_345, purchases = 2, unknownCost = 9)),
        )
        assertNull(valueOf(f, "Recorded spend in this range"))
    }

    @Test
    fun `spend is omitted entirely when nothing was purchased`() {
        val f = CannsheetLlmFacts.from(response(spendRange = bucket(spendCents = 0, purchases = 0)))
        assertNull(valueOf(f, "Recorded spend in this range"))
    }

    @Test
    fun `time-of-day combines every hour in each band before choosing the highest band`() {
        val f = CannsheetLlmFacts.from(
            response(
                byHour = listOf(
                    HourActivityDto(6, 6),
                    HourActivityDto(11, 6),
                    HourActivityDto(21, 10),
                ),
            ),
        )
        val fact = f.first { it.label == "Most frequently logged time of day" }
        assertEquals("morning", fact.value)
        assertEquals("12 entries", fact.note)
    }

    @Test
    fun `invalid hour buckets cannot be mislabelled as evening activity`() {
        val f = CannsheetLlmFacts.from(
            response(
                byHour = listOf(
                    HourActivityDto(9, 4),
                    HourActivityDto(24, 100),
                    HourActivityDto(-1, 100),
                ),
            ),
        )
        val fact = f.first { it.label == "Most frequently logged time of day" }
        assertEquals("morning", fact.value)
        assertEquals("4 entries", fact.note)
    }

    @Test
    fun `canonical and legacy aliases are aggregated under one product display label`() {
        val f = CannsheetLlmFacts.from(
            response(
                byType = listOf(
                    TypeBreakdownDto("F", 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    TypeBreakdownDto("Flower", 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    TypeBreakdownDto("P", 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                ),
            ),
        )
        val fact = f.first { it.label == "Most frequently logged product type" }
        assertEquals("Flower", fact.value)
        assertEquals("12 entries", fact.note)
    }

    @Test
    fun `ties are explicit and product codes use their production display labels`() {
        val f = CannsheetLlmFacts.from(
            response(
                byType = listOf(
                    TypeBreakdownDto("F", 12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    TypeBreakdownDto("P", 12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                ),
                byWeekday = listOf(WeekdayActivityDto(1, 12), WeekdayActivityDto(5, 12)),
                byHour = listOf(HourActivityDto(6, 12), HourActivityDto(12, 12)),
            ),
        )

        assertEquals("Flower and Pen", valueOf(f, "Most frequently logged product type"))
        assertEquals("Monday and Friday", valueOf(f, "Most frequently logged weekday"))
        assertEquals("morning and afternoon", valueOf(f, "Most frequently logged time of day"))
        listOf(
            "Most frequently logged product type",
            "Most frequently logged weekday",
            "Most frequently logged time of day",
        ).forEach { label ->
            assertEquals("tied at 12 entries each", f.first { it.label == label }.note)
        }
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
