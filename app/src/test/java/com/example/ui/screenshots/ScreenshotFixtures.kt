package com.example.ui.screenshots

import com.example.data.AnalyticsProductDto
import com.example.data.AnalyticsRangeDto
import com.example.data.DailyActivityDto
import com.example.data.DataQualityDto
import com.example.data.HourActivityDto
import com.example.data.InventoryDto
import com.example.data.OverviewDto
import com.example.data.Product
import com.example.data.ProductActivityDto
import com.example.data.ProductRangeActivityDto
import com.example.data.QualityWarningsDto
import com.example.data.SourceRevisionDto
import com.example.data.SpendBucketDto
import com.example.data.SpendingDto
import com.example.data.SyncHealthDto
import com.example.data.SyncPreferences
import com.example.data.TypeBreakdownDto
import com.example.data.WeekdayActivityDto
import com.example.data.InsightsResponseDto
import com.example.ui.ConsumptionFormState
import com.example.ui.PurchaseFormState
import com.example.ui.RecentProduct
import java.time.Instant

internal val SCREENSHOT_NOW: Instant = Instant.parse("2026-09-28T13:30:00Z")
internal val screenshotProducts = listOf(
    Product(
        id = "fixture-flower-1",
        name = "Blue Dream",
        type = "F",
        status = 0,
        cost = 42.50,
        thc = 0.21,
        grams = 3.5,
        productUuid = "11111111-1111-4111-8111-111111111111",
    ),
    Product(
        id = "fixture-pen-1",
        name = "Citrus Cartridge",
        type = "P",
        status = 0,
        cost = 38.0,
        thc = 0.78,
        grams = 1.0,
        productUuid = "22222222-2222-4222-8222-222222222222",
        totalUses = 120.0,
    ),
)
internal val screenshotRecentProducts = screenshotProducts.map { RecentProduct(it, 0.5) }
internal val screenshotConsumptionForm = ConsumptionFormState(
    selectedProductId = "fixture-flower-1",
    quantityText = "0.5",
)
internal val screenshotPurchaseForm = PurchaseFormState(
    date = "2026-09-28",
    type = "F",
    name = "Blue Dream",
    cost = "42.50",
    thc = "21",
    grams = "3.5",
    postTax = true,
)

internal fun screenshotInsights(empty: Boolean = false): InsightsResponseDto {
    val count = if (empty) 0 else 18
    val spend = if (empty) 0L else 4250L
    val bucket = SpendBucketDto(spend, if (empty) 0 else 3, 0, 0, 0, 0, 0, 0)
    val product = AnalyticsProductDto(
        productUuid = "11111111-1111-4111-8111-111111111111",
        productId = "fixture-flower-1",
        name = "Blue Dream",
        type = "F",
        status = "ACTIVE",
        purchaseDate = "2026-09-15",
        purchaseDateSource = "EXPLICIT",
        preTaxCostCents = 4250,
        finalCostCents = 4800,
        grams = 3.5,
        thcRaw = 0.21,
        thcQuality = "VALID",
        allTime = ProductActivityDto(count, if (empty) 0.0 else 8.5, if (empty) 0 else 6),
        range = ProductRangeActivityDto(count, if (empty) 0.0 else 2.5, if (empty) 0 else 4),
        costPerLogToDateCents = if (empty) null else 531,
        completedValueComparisonEligible = true,
    )
    return InsightsResponseDto(
        success = true,
        analyticsVersion = 2,
        resource = "insights",
        environment = "PRODUCTION",
        timeZone = "America/Toronto",
        range = AnalyticsRangeDto("RANGE", "2026-09-01", "2026-09-28", 28),
        overview = OverviewDto(count, if (empty) 0 else 8, if (empty) 0 else 2),
        dailyActivity = if (empty) emptyList() else listOf(
            DailyActivityDto("2026-09-27", 4, 2),
            DailyActivityDto("2026-09-28", 3, 1),
        ),
        byWeekday = if (empty) emptyList() else listOf(WeekdayActivityDto(1, 4), WeekdayActivityDto(7, 5)),
        byHour = if (empty) emptyList() else listOf(HourActivityDto(9, 5), HourActivityDto(20, 3)),
        inventory = InventoryDto(
            if (empty) 0 else 2,
            if (empty) 0 else 1,
            if (empty) 0 else 4,
            0,
            spend,
            0,
            0,
        ),
        byType = if (empty) emptyList() else listOf(
            TypeBreakdownDto("F", count, 1, 1, 1, 2, 0, spend, 3, 0, 0, 0),
        ),
        products = if (empty) emptyList() else listOf(product),
        spending = SpendingDto(bucket, bucket, emptyList()),
        syncHealth = SyncHealthDto("CURRENT", acknowledgedRequestCount30d = 7, partialRequestCount30d = 0),
        dataQuality = DataQualityDto(true, QualityWarningsDto()),
        sourceRevision = SourceRevisionDto("fixture-r1", 3, count, effectiveEventCount = count),
        generatedAtEpochMillis = SCREENSHOT_NOW.toEpochMilli(),
        serverDurationMs = 23,
    )
}

internal val screenshotSyncPreferences = SyncPreferences(
    enabled = true,
    lastMeaningfulSyncAtEpochMillis = SCREENSHOT_NOW.minusSeconds(3600).toEpochMilli(),
    queueAlertsEnabled = true,
    queueNonEmptySinceEpochMillis = SCREENSHOT_NOW.minusSeconds(1800).toEpochMilli(),
)
