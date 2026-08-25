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
import com.noamv.localllm.contract.v2.AggregateQuery
import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.MetricId
import com.noamv.localllm.contract.v2.QueryPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CannsheetFactsCalculatorTest {

    @Test
    fun testCapabilitiesExposesCannsheetMetrics() {
        val caps = CannsheetFactsCalculator.getCapabilities()
        assertEquals(AppSource.CANNSHEET, caps.sourceApp)
        assertTrue(caps.supportedMetrics.contains(MetricId.CANNSHEET_RECORDED_SPEND.wireName))
        assertTrue(caps.supportedMetrics.contains(MetricId.CANNSHEET_CONSUMPTION_COUNT.wireName))
        assertTrue(caps.supportedMetrics.contains(MetricId.CANNSHEET_INVENTORY_REMAINING.wireName))
    }

    @Test
    fun testCalculateFactsWithNullSnapshotReturnsWarning() {
        val query = AggregateQuery(
            sources = listOf(AppSource.CANNSHEET),
            metrics = listOf(MetricId.CANNSHEET_RECORDED_SPEND),
            period = QueryPeriod.LastDays(30),
        )
        val result = CannsheetFactsCalculator.calculateFacts(
            query = query,
            snapshot = null,
            pendingActionCount = 0,
        )
        assertEquals(AppSource.CANNSHEET, result.sourceApp)
        assertTrue(result.facts.isEmpty())
        assertTrue(result.warnings.any { it.contains("No settled") })
    }

    @Test
    fun testCalculateFactsWithPendingActionsAddsWarning() {
        val query = AggregateQuery(
            sources = listOf(AppSource.CANNSHEET),
            metrics = listOf(MetricId.CANNSHEET_RECORDED_SPEND),
            period = QueryPeriod.LastDays(30),
        )
        val snapshot = createTestSnapshot()
        val result = CannsheetFactsCalculator.calculateFacts(
            query = query,
            snapshot = snapshot,
            pendingActionCount = 3,
        )
        assertTrue(result.warnings.any { it.contains("3 local actions pending") })
    }

    @Test
    fun testCalculateFactsComputesSpendAndCoverageCorrectly() {
        val query = AggregateQuery(
            sources = listOf(AppSource.CANNSHEET),
            metrics = listOf(
                MetricId.CANNSHEET_RECORDED_SPEND,
                MetricId.CANNSHEET_RECORDED_SPEND_COVERAGE,
            ),
            period = QueryPeriod.LastDays(30),
        )
        val snapshot = createTestSnapshot(spendCents = 12050L, purchases = 10, unknownCost = 2)
        val result = CannsheetFactsCalculator.calculateFacts(
            query = query,
            snapshot = snapshot,
            pendingActionCount = 0,
        )
        val spendFact = result.facts.firstOrNull { it.metricId == MetricId.CANNSHEET_RECORDED_SPEND.wireName }
        assertNotNull(spendFact)
        assertEquals("$120.50", spendFact?.displayValue)

        val covFact = result.facts.firstOrNull { it.metricId == MetricId.CANNSHEET_RECORDED_SPEND_COVERAGE.wireName }
        assertNotNull(covFact)
        assertEquals("80%", covFact?.displayValue)
        assertEquals(80, covFact?.coveragePercent)
    }

    @Test
    fun testCalculateFactsComputesAllMetricsGrounded() {
        val query = AggregateQuery(
            sources = listOf(AppSource.CANNSHEET),
            metrics = emptyList(), // compute all
            period = QueryPeriod.LastDays(30),
        )
        val snapshot = createTestSnapshot(logCount = 25, activeDays = 14, purchases = 10)
        val result = CannsheetFactsCalculator.calculateFacts(
            query = query,
            snapshot = snapshot,
            pendingActionCount = 0,
        )
        assertTrue(result.facts.size >= 7)

        val consumptionFact = result.facts.firstOrNull { it.metricId == MetricId.CANNSHEET_CONSUMPTION_COUNT.wireName }
        assertEquals("25", consumptionFact?.displayValue)

        val purchasesFact = result.facts.firstOrNull { it.metricId == MetricId.CANNSHEET_PURCHASE_COUNT.wireName }
        assertEquals("10", purchasesFact?.displayValue)

        val activeDaysFact = result.facts.firstOrNull { it.metricId == MetricId.CANNSHEET_ACTIVE_DAYS.wireName }
        assertEquals("14 of 30", activeDaysFact?.displayValue)

        val inventoryFact = result.facts.firstOrNull { it.metricId == MetricId.CANNSHEET_INVENTORY_REMAINING.wireName }
        assertEquals("3", inventoryFact?.displayValue)
    }

    @Test
    fun testCalculateFactsWithUnsupportedFilterAddsWarning() {
        val query = AggregateQuery(
            sources = listOf(AppSource.CANNSHEET),
            metrics = listOf(MetricId.CANNSHEET_RECORDED_SPEND),
            period = QueryPeriod.LastDays(30),
            filters = listOf(
                com.noamv.localllm.contract.v2.QueryFilter(
                    source = AppSource.CANNSHEET,
                    field = "cannsheet.unknown_field",
                    operator = "EQUALS",
                    value = "foo",
                ),
            ),
        )
        val snapshot = createTestSnapshot()
        val result = CannsheetFactsCalculator.calculateFacts(
            query = query,
            snapshot = snapshot,
            pendingActionCount = 0,
        )
        assertTrue(result.warnings.any { it.contains("UNSUPPORTED filter: cannsheet.unknown_field") })
    }

    @Test
    fun testCalculateFactsWithMismatchedPeriodAddsWarning() {
        val query = AggregateQuery(
            sources = listOf(AppSource.CANNSHEET),
            metrics = listOf(MetricId.CANNSHEET_RECORDED_SPEND),
            period = QueryPeriod.LastDays(90),
        )
        val snapshot = createTestSnapshot()
        val result = CannsheetFactsCalculator.calculateFacts(
            query = query,
            snapshot = snapshot,
            pendingActionCount = 0,
        )
        assertTrue(result.warnings.any { it.contains("Requested period LastDays(90) differs from cached snapshot range") })
    }

    private fun bucket(
        spendCents: Long = 12_050L,
        purchases: Int = 10,
        unknownCost: Int = 2,
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

    private fun createTestSnapshot(
        logCount: Int = 25,
        activeDays: Int = 14,
        purchases: Int = 10,
        spendCents: Long = 12_050L,
        unknownCost: Int = 2,
    ): InsightsResponseDto = InsightsResponseDto(
        success = true,
        analyticsVersion = 1,
        resource = "insights",
        environment = "sandbox",
        timeZone = "America/Toronto",
        range = AnalyticsRangeDto(
            scope = "30d",
            from = "2026-07-25",
            to = "2026-08-23",
            dayCount = 30,
        ),
        overview = OverviewDto(
            logCount = logCount,
            activeDayCount = activeDays,
            distinctProductCount = 5,
            daysSinceLastLog = 1,
        ),
        dailyActivity = emptyList(),
        byWeekday = listOf(
            WeekdayActivityDto(isoDay = 5, logCount = 8),
            WeekdayActivityDto(isoDay = 6, logCount = 12),
        ),
        byHour = listOf(
            HourActivityDto(hour = 20, logCount = 15),
            HourActivityDto(hour = 14, logCount = 10),
        ),
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
            TypeBreakdownDto("P", 20, 2, 1, 0, 3, 0, 5_000, 2, 0, 0, 0),
            TypeBreakdownDto("F", 5, 1, 1, 0, 1, 0, 2_000, 1, 0, 0, 0),
        ),
        products = emptyList(),
        spending = SpendingDto(
            allTime = bucket(spendCents, purchases, unknownCost),
            range = bucket(spendCents, purchases, unknownCost),
            byMonth = emptyList(),
        ),
        syncHealth = SyncHealthDto(
            coverage = "full",
            acknowledgedRequestCount30d = 30,
            partialRequestCount30d = 0,
        ),
        dataQuality = DataQualityDto(
            complete = true,
            warnings = QualityWarningsDto(),
        ),
        sourceRevision = SourceRevisionDto(
            dataVersion = "rev-123",
            purchaseRowCount = purchases,
            eventRowCount = logCount,
        ),
        generatedAtEpochMillis = 10000L,
        serverDurationMs = 100L,
    )
}
