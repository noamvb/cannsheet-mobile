package com.example.widget.projection

import com.example.data.ANALYTICS_VERSION
import com.example.data.AnalyticsProductDto
import com.example.data.AnalyticsRangeDto
import com.example.data.DataQualityDto
import com.example.data.DailyActivityDto
import com.example.data.HourActivityDto
import com.example.data.InventoryDto
import com.example.data.MonthlySpendDto
import com.example.data.OverviewDto
import com.example.data.ProductActivityDto
import com.example.data.ProductRangeActivityDto
import com.example.data.QualityWarningsDto
import com.example.data.SpendBucketDto
import com.example.data.SpendingDto
import com.example.data.SourceRevisionDto
import com.example.data.SyncHealthDto
import com.example.data.TypeBreakdownDto
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionUiModelTest {
    @Test
    fun nullSnapshotProducesTheEmptyState() {
        assertEquals(
            ProjectionUiModel.Suppressed(ProjectionSuppressionReason.NO_SNAPSHOT),
            buildProjectionUiModel(null, ProjectionMode.RUNWAY),
        )
    }

    @Test
    fun runwayModeShowsDaysRemainingWithAnAsOfDate() {
        val model = buildProjectionUiModel(snapshot(), ProjectionMode.RUNWAY)
        val ready = model as ProjectionUiModel.Ready

        assertEquals(ProjectionMode.RUNWAY, ready.mode)
        assertTrue(ready.figures.single().renderedText.contains("days"))
        assertTrue(ready.figures.single().renderedText.contains("as of 2026-07-14"))
    }

    @Test
    fun spendModeShowsMonthToDateAndProjectionWithAnAsOfDate() {
        val model = buildProjectionUiModel(snapshot(), ProjectionMode.SPEND)
        val ready = model as ProjectionUiModel.Ready
        val rendered = ready.figures.single().renderedText

        assertEquals(ProjectionMode.SPEND, ready.mode)
        assertTrue(rendered.contains("this month"))
        assertTrue(rendered.contains("personal spending through"))
        assertTrue(rendered.contains("as of 2026-07-14"))
    }

    @Test
    fun everyRenderedFigureCarriesAnAsOfDate() {
        val snapshot = snapshot()

        listOf(ProjectionMode.RUNWAY, ProjectionMode.SPEND).forEach { mode ->
            val ready = buildProjectionUiModel(snapshot, mode) as ProjectionUiModel.Ready
            ready.figures.forEach { figure ->
                assertEquals(snapshot.range.to, figure.asOfDate)
                assertTrue(figure.renderedText.contains("as of ${snapshot.range.to}"))
            }
        }
    }

    @Test
    fun incompleteDataQualityIsSuppressed() {
        val incomplete = snapshot().copy(dataQuality = DataQualityDto(complete = false, warnings = QualityWarningsDto()))

        assertEquals(
            ProjectionUiModel.Suppressed(ProjectionSuppressionReason.INCOMPLETE_SNAPSHOT),
            buildProjectionUiModel(incomplete, ProjectionMode.SPEND),
        )
    }

    @Test
    fun asOfDateComesFromTheSnapshotRangeNotTheDeviceClock() {
        val snapshot = snapshot(rangeTo = "2026-07-14")
        val ready = buildProjectionUiModel(snapshot, ProjectionMode.SPEND) as ProjectionUiModel.Ready

        assertEquals("2026-07-14", ready.figures.single().asOfDate)
        assertTrue(ready.figures.single().renderedText.endsWith("as of 2026-07-14"))
    }

    private fun snapshot(rangeTo: String = "2026-07-14"): com.example.data.InsightsResponseDto {
        val range = AnalyticsRangeDto(
            scope = "CUSTOM",
            from = "2026-07-01",
            to = rangeTo,
            dayCount = 14,
        )
        return com.example.data.InsightsResponseDto(
            success = true,
            analyticsVersion = ANALYTICS_VERSION,
            resource = "insights",
            environment = "PRODUCTION",
            timeZone = "UTC",
            range = range,
            overview = OverviewDto(logCount = 10, activeDayCount = 5, distinctProductCount = 4),
            dailyActivity = emptyList<DailyActivityDto>(),
            byWeekday = emptyList(),
            byHour = emptyList<HourActivityDto>(),
            inventory = InventoryDto(
                activeCount = 1,
                unopenedCount = 0,
                finishedCount = 3,
                unknownStatusCount = 0,
                currentPersonalOriginalCostCents = 1_234,
                currentBorrowedRecordedValueCents = 0,
                unknownCurrentCostCount = 0,
            ),
            byType = emptyList<TypeBreakdownDto>(),
            products = runwayProducts(),
            spending = SpendingDto(
                allTime = spendBucket(),
                range = spendBucket(),
                byMonth = listOf(
                    MonthlySpendDto(
                        month = "2026-07",
                        personalSpendCents = 1_234,
                        personalPurchaseCount = 1,
                        borrowedRecordedValueCents = 0,
                        borrowedPurchaseCount = 0,
                        unknownPersonalCostCount = 0,
                        unknownBorrowedCostCount = 0,
                        estimatedDateCount = 0,
                        unknownDateCount = 0,
                    ),
                ),
            ),
            syncHealth = SyncHealthDto(
                coverage = "AVAILABLE",
                acknowledgedRequestCount30d = 0,
                partialRequestCount30d = 0,
            ),
            dataQuality = DataQualityDto(complete = true, warnings = QualityWarningsDto()),
            sourceRevision = SourceRevisionDto(
                dataVersion = "test",
                purchaseRowCount = 4,
                eventRowCount = 10,
            ),
            generatedAtEpochMillis = 0L,
            serverDurationMs = 0L,
        )
    }

    private fun runwayProducts(): List<AnalyticsProductDto> = listOf(
        product(
            id = "active",
            name = "Current cart",
            status = "ACTIVE",
            purchaseDate = "2026-07-01",
            finalCostCents = 1_234,
            allQuantity = 6.0,
            rangeQuantity = 7.0,
            firstLogAtEpochMillis = utcEpoch(2026, 6, 28),
        ),
        product(
            id = "finished-1",
            name = "Finished 1",
            status = "FINISHED",
            purchaseDate = "2026-06-01",
            finalCostCents = 0,
            allQuantity = 20.0,
            rangeQuantity = 0.0,
            firstLogAtEpochMillis = utcEpoch(2026, 6, 1),
        ),
        product(
            id = "finished-2",
            name = "Finished 2",
            status = "FINISHED",
            purchaseDate = "2026-06-01",
            finalCostCents = 0,
            allQuantity = 22.0,
            rangeQuantity = 0.0,
            firstLogAtEpochMillis = utcEpoch(2026, 6, 1),
        ),
        product(
            id = "finished-3",
            name = "Finished 3",
            status = "FINISHED",
            purchaseDate = "2026-06-01",
            finalCostCents = 0,
            allQuantity = 24.0,
            rangeQuantity = 0.0,
            firstLogAtEpochMillis = utcEpoch(2026, 6, 1),
        ),
    )

    private fun product(
        id: String,
        name: String,
        status: String,
        purchaseDate: String,
        finalCostCents: Long,
        allQuantity: Double,
        rangeQuantity: Double,
        firstLogAtEpochMillis: Long,
    ) = AnalyticsProductDto(
        productId = id,
        name = name,
        type = "P",
        status = status,
        borrowed = false,
        purchaseDate = purchaseDate,
        purchaseDateSource = "RECORDED",
        preTaxCostCents = finalCostCents,
        finalCostCents = finalCostCents,
        grams = null,
        thcRaw = null,
        thcQuality = "UNKNOWN",
        latestFinishedLogAtEpochMillis = if (status == "FINISHED") firstLogAtEpochMillis else null,
        daysSinceLastLog = null,
        allTime = ProductActivityDto(
            logCount = 1,
            quantity = allQuantity,
            activeDayCount = 1,
            firstLogAtEpochMillis = firstLogAtEpochMillis,
            lastLogAtEpochMillis = firstLogAtEpochMillis,
        ),
        range = ProductRangeActivityDto(
            logCount = if (rangeQuantity > 0.0) 1 else 0,
            quantity = rangeQuantity,
            activeDayCount = if (rangeQuantity > 0.0) 1 else 0,
        ),
        costPerLogToDateCents = null,
        costPerRecordedUnitToDateCents = null,
        completedValueComparisonEligible = false,
    )

    private fun spendBucket() = SpendBucketDto(
        personalSpendCents = 0,
        personalPurchaseCount = 0,
        borrowedRecordedValueCents = 0,
        borrowedPurchaseCount = 0,
        unknownPersonalCostCount = 0,
        unknownBorrowedCostCount = 0,
        estimatedDateCount = 0,
        unknownDateCount = 0,
    )

    private fun utcEpoch(year: Int, month: Int, day: Int): Long =
        GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            set(year, month - 1, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
