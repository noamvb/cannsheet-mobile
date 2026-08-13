package com.example.ui

import com.example.data.ANALYTICS_VERSION
import com.example.data.AnalyticsProductDto
import com.example.data.AnalyticsRangeDto
import com.example.data.DataQualityDto
import com.example.data.InsightsRange
import com.example.data.InsightsResponseDto
import com.example.data.InventoryDto
import com.example.data.MonthlySpendDto
import com.example.data.OverviewDto
import com.example.data.ProductActivityDto
import com.example.data.ProductRangeActivityDto
import com.example.data.QualityWarningsDto
import com.example.data.SourceRevisionDto
import com.example.data.SpendBucketDto
import com.example.data.SpendingDto
import com.example.data.SyncHealthDto
import com.example.domain.RunwayBasis
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RunwayPresentationTest {
    @Test
    fun onlyARealNonemptyToEmptyTransitionInvalidatesTheSnapshot() {
        assertFalse(shouldInvalidateRunwayForQueueTransition(null, 0))
        assertFalse(shouldInvalidateRunwayForQueueTransition(0, 1))
        assertFalse(shouldInvalidateRunwayForQueueTransition(1, 2))
        assertFalse(shouldInvalidateRunwayForQueueTransition(2, 1))
        assertTrue(shouldInvalidateRunwayForQueueTransition(1, 0))
    }

    @Test
    fun unavailableOrUntrustedSnapshotsSuppressEveryEstimate() {
        val fresh = InsightsUiState(data = insights())
        val cases = listOf(
            Triple(InsightsUiState(), 0, RunwaySuppressionReason.NO_SNAPSHOT),
            Triple(fresh.copy(isStale = true), 0, RunwaySuppressionReason.STALE_SNAPSHOT),
            Triple(fresh.copy(isFromCache = true), 0, RunwaySuppressionReason.CACHED_SNAPSHOT),
            Triple(fresh.copy(pendingRange = InsightsRange.All), 0, RunwaySuppressionReason.RANGE_CHANGING),
            Triple(fresh, 2, RunwaySuppressionReason.PENDING_ACTIONS),
        )

        cases.forEach { (insights, pendingActionCount, expectedReason) ->
            val result = deriveRunwayPresentationState(
                insights = insights,
                pendingActionCount = pendingActionCount,
                nowEpochMillis = NOW,
            )

            assertSame(insights, result.insights)
            assertEquals(pendingActionCount, result.pendingActionCount)
            assertEquals(RunwayEstimateState.Suppressed(expectedReason), result.estimates)
        }
    }

    @Test
    fun unknownQueueCountSuppressesUntilRoomEmitsItsFirstCount() {
        val insights = InsightsUiState(data = insights())

        val result = deriveRunwayPresentationState(
            insights = insights,
            pendingActionCount = null,
            nowEpochMillis = NOW,
        )

        assertSame(insights, result.insights)
        assertTrue(result.pendingActionCount == null)
        assertEquals(
            RunwayEstimateState.Suppressed(RunwaySuppressionReason.QUEUE_COUNT_UNKNOWN),
            result.estimates,
        )
    }

    @Test
    fun suppressionPrecedencePrefersPendingActionsOverCacheAndStaleness() {
        val insights = InsightsUiState(
            data = insights(),
            isFromCache = true,
            isStale = true,
            pendingRange = InsightsRange.All,
        )
        val result = deriveRunwayPresentationState(
            insights = insights,
            pendingActionCount = 3,
            nowEpochMillis = NOW,
        )
        assertEquals(
            RunwayEstimateState.Suppressed(RunwaySuppressionReason.PENDING_ACTIONS),
            result.estimates,
        )
    }

    @Test
    fun everyInputSuppressedBeforeIsStillSuppressed() {
        val fresh = InsightsUiState(data = insights())
        val suppressingStates = listOf(
            InsightsUiState() to 0,
            fresh to null,
            fresh to 1,
            fresh.copy(pendingRange = InsightsRange.All) to 0,
            fresh.copy(isFromCache = true) to 0,
            fresh.copy(isStale = true) to 0,
        )
        suppressingStates.forEach { (insights, pendingCount) ->
            val result = deriveRunwayPresentationState(insights, pendingCount, NOW)
            assertTrue(result.estimates is RunwayEstimateState.Suppressed)
        }
    }

    @Test
    fun readyStateSurvivesAFreshCompleteSnapshot() {
        val fresh = InsightsUiState(data = insights())
        val result = deriveRunwayPresentationState(fresh, 0, NOW)
        assertTrue(result.estimates is RunwayEstimateState.Ready)
    }

    @Test
    fun suppressionCopyNamesTheQueueCountAndNeverUsesSingularForPlural() {
        val single = runwaySuppressionText(RunwaySuppressionReason.PENDING_ACTIONS, 1)
        val plural = runwaySuppressionText(RunwaySuppressionReason.PENDING_ACTIONS, 2)
        assertTrue(checkNotNull(single).contains("1 entry is waiting"))
        assertTrue(checkNotNull(plural).contains("2 entries are waiting"))
    }

    @Test
    fun noSnapshotAndUnknownQueueCountRenderNoCopy() {
        assertEquals(null, runwaySuppressionText(RunwaySuppressionReason.NO_SNAPSHOT, 0))
        assertEquals(null, runwaySuppressionText(RunwaySuppressionReason.QUEUE_COUNT_UNKNOWN, 0))
    }

    @Test
    fun freshSnapshotBuildsExactIdRunwayEvidenceAndCurrentMonthSpend() {
        val insights = InsightsUiState(data = insights())

        val result = deriveRunwayPresentationState(
            insights = insights,
            pendingActionCount = 0,
            nowEpochMillis = NOW,
        )

        val ready = result.estimates as RunwayEstimateState.Ready
        assertSame(insights, result.insights)
        assertEquals(0, result.pendingActionCount)
        assertEquals(setOf(ACTIVE_PRODUCT_ID), ready.runwayByProductId.keys)
        assertFalse(ready.runwayByProductId.containsKey(ACTIVE_PRODUCT_UUID))
        assertFalse(ready.runwayByProductId.containsKey(ACTIVE_PRODUCT_NAME))

        val runway = checkNotNull(ready.runwayByProductId[ACTIVE_PRODUCT_ID])
        assertEquals(RunwayBasis.PER_PRODUCT, runway.basis)
        assertEquals(3, runway.sampleSize)
        assertEquals(12.0, runway.estimatedTypicalFinishedUses, 0.0)
        assertEquals(8.0, runway.estimatedRemainingToTypicalUses, 0.0)
        assertEquals(15, runway.effectiveBurnRateDays)
        assertEquals(2.0 / 15.0, runway.usesPerDay, 0.0)
        assertEquals(60.0, runway.estimatedDaysRemaining, 0.0)

        val evidence = checkNotNull(ready.evidenceByType["P"])
        assertEquals("P", evidence.type)
        assertEquals(3, evidence.sampleSize)
        assertEquals(0, evidence.perGramSampleSize)
        assertTrue(ready.diagnostics.isEmpty())

        val spend = checkNotNull(ready.spendRunRate)
        assertEquals("2026-07", spend.month)
        assertEquals(2_000L, spend.monthToDateCents)
        assertEquals(15, spend.elapsedDays)
        assertEquals(31, spend.daysInMonth)
        assertEquals(4_133L, spend.projectedMonthEndCents)
        assertEquals(1, spend.personalPurchaseCount)
        assertEquals(0, spend.estimatedPersonalPurchaseDateCount)
    }

    @Test
    fun unavailableProductsAreAggregatedByTypeAndCause() {
        val data = insights()
        val products = data.products + listOf(
            product(id = "unused-one", status = "ACTIVE", allQuantity = 0.0),
            product(id = "unused-two", status = "ACTIVE", allQuantity = 0.0),
            product(
                id = "too-new-for-rate",
                status = "ACTIVE",
                allQuantity = 1.0,
                rangeQuantity = 1.0,
                firstLogAt = utcEpoch(2026, 7, 15, 16),
            ),
        )

        val result = deriveRunwayPresentationState(
            insights = InsightsUiState(data = data.copy(products = products)),
            pendingActionCount = 0,
            nowEpochMillis = NOW,
        )

        val ready = result.estimates as RunwayEstimateState.Ready
        assertTrue(
            ready.diagnostics.contains(
                RunwayDiagnostic.NoUseInRange(type = "P", productCount = 2),
            ),
        )
        assertTrue(
            ready.diagnostics.contains(
                RunwayDiagnostic.UnavailableForType(type = "P", productCount = 1),
            ),
        )
        assertTrue(
            runwayDiagnosticText(
                RunwayDiagnostic.NoUseInRange(type = "P", productCount = 2),
            ).contains("2 active products"),
        )
    }

    @Test
    fun queueDrainKeepsTheOldSnapshotSuppressedUntilARefreshCompletes() {
        val insights = InsightsUiState(data = insights())

        val beforeQueue = deriveRunwayPresentationState(insights, 0, NOW)
        val whileQueued = deriveRunwayPresentationState(insights, 1, NOW)
        val afterQueueBeforeRefresh = deriveRunwayPresentationState(
            insights.copy(isStale = true),
            0,
            NOW,
        )
        val afterFreshResponse = deriveRunwayPresentationState(insights, 0, NOW)

        assertTrue(beforeQueue.estimates is RunwayEstimateState.Ready)
        assertEquals(
            RunwayEstimateState.Suppressed(RunwaySuppressionReason.PENDING_ACTIONS),
            whileQueued.estimates,
        )
        assertEquals(
            RunwayEstimateState.Suppressed(RunwaySuppressionReason.STALE_SNAPSHOT),
            afterQueueBeforeRefresh.estimates,
        )
        assertTrue(afterFreshResponse.estimates is RunwayEstimateState.Ready)
    }

    private fun insights(): InsightsResponseDto {
        val products = listOf(
            product(
                id = ACTIVE_PRODUCT_ID,
                uuid = ACTIVE_PRODUCT_UUID,
                name = ACTIVE_PRODUCT_NAME,
                status = "ACTIVE",
                borrowed = false,
                purchaseDate = "2026-07-01",
                finalCostCents = 2_000,
                allQuantity = 4.0,
                rangeQuantity = 2.0,
                firstLogAt = utcEpoch(2026, 7, 1, 16),
            ),
            product(id = "finished-10", status = "FINISHED", allQuantity = 10.0),
            product(id = "finished-12", status = "FINISHED", allQuantity = 12.0),
            product(id = "finished-14", status = "FINISHED", allQuantity = 14.0),
        )
        return InsightsResponseDto(
            success = true,
            analyticsVersion = ANALYTICS_VERSION,
            resource = "insights",
            environment = "PRODUCTION",
            timeZone = NEW_YORK,
            range = AnalyticsRangeDto(
                scope = "CUSTOM",
                from = "2026-07-01",
                to = "2026-07-15",
                dayCount = 15,
            ),
            overview = OverviewDto(
                logCount = 1,
                activeDayCount = 1,
                distinctProductCount = 1,
            ),
            dailyActivity = emptyList(),
            byWeekday = emptyList(),
            byHour = emptyList(),
            inventory = InventoryDto(
                activeCount = 1,
                unopenedCount = 0,
                finishedCount = 3,
                unknownStatusCount = 0,
                currentPersonalOriginalCostCents = 2_000,
                currentBorrowedRecordedValueCents = 0,
                unknownCurrentCostCount = 0,
            ),
            byType = emptyList(),
            products = products,
            spending = SpendingDto(
                allTime = spendBucket(personalSpendCents = 2_000, personalPurchaseCount = 1),
                range = spendBucket(personalSpendCents = 2_000, personalPurchaseCount = 1),
                byMonth = listOf(
                    MonthlySpendDto(
                        month = "2026-07",
                        personalSpendCents = 2_000,
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
            dataQuality = DataQualityDto(
                complete = true,
                warnings = QualityWarningsDto(),
            ),
            sourceRevision = SourceRevisionDto(
                dataVersion = "test",
                purchaseRowCount = products.size,
                eventRowCount = 1,
            ),
            generatedAtEpochMillis = NOW,
            serverDurationMs = 1,
        )
    }

    private fun product(
        id: String,
        uuid: String? = null,
        name: String = "Product $id",
        status: String,
        borrowed: Boolean = true,
        purchaseDate: String = "2026-01-01",
        finalCostCents: Long = 1_000,
        allQuantity: Double,
        rangeQuantity: Double = 0.0,
        firstLogAt: Long = utcEpoch(2026, 1, 1, 17),
    ) = AnalyticsProductDto(
        productUuid = uuid,
        productId = id,
        name = name,
        type = "P",
        status = status,
        borrowed = borrowed,
        purchaseDate = purchaseDate,
        purchaseDateSource = "RECORDED",
        finalCostCents = finalCostCents,
        thcQuality = "UNKNOWN",
        allTime = ProductActivityDto(
            logCount = if (allQuantity > 0.0) 1 else 0,
            quantity = allQuantity,
            activeDayCount = if (allQuantity > 0.0) 1 else 0,
            firstLogAtEpochMillis = firstLogAt,
            lastLogAtEpochMillis = firstLogAt,
        ),
        range = ProductRangeActivityDto(
            logCount = if (rangeQuantity > 0.0) 1 else 0,
            quantity = rangeQuantity,
            activeDayCount = if (rangeQuantity > 0.0) 1 else 0,
        ),
        completedValueComparisonEligible = false,
    )

    private fun spendBucket(
        personalSpendCents: Long = 0,
        personalPurchaseCount: Int = 0,
    ) = SpendBucketDto(
        personalSpendCents = personalSpendCents,
        personalPurchaseCount = personalPurchaseCount,
        borrowedRecordedValueCents = 0,
        borrowedPurchaseCount = 0,
        unknownPersonalCostCount = 0,
        unknownBorrowedCostCount = 0,
        estimatedDateCount = 0,
        unknownDateCount = 0,
    )

    companion object {
        private const val NEW_YORK = "America/New_York"
        private const val ACTIVE_PRODUCT_ID = "inventory-id"
        private const val ACTIVE_PRODUCT_UUID = "legacy-uuid"
        private const val ACTIVE_PRODUCT_NAME = "Name that is not an ID"
        private val NOW = utcEpoch(2026, 7, 15, 16)

        private fun utcEpoch(
            year: Int,
            month: Int,
            day: Int,
            hour: Int,
        ): Long = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).run {
            isLenient = false
            clear()
            set(year, month - 1, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
    }
}
