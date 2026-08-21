package com.example.domain

import com.example.data.ANALYTICS_VERSION
import com.example.data.AnalyticsProductDto
import com.example.data.AnalyticsRangeDto
import com.example.data.DataQualityDto
import com.example.data.InventoryDto
import com.example.data.InsightsResponseDto
import com.example.data.MonthlySpendDto
import com.example.data.OverviewDto
import com.example.data.ProductActivityDto
import com.example.data.ProductRangeActivityDto
import com.example.data.QualityWarningsDto
import com.example.data.SourceRevisionDto
import com.example.data.SpendBucketDto
import com.example.data.SpendingDto
import com.example.data.SyncHealthDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

class InventoryRunwayTest {
    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreDefaultTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun medianHandlesOddEvenSingleUnsortedAndLargeFiniteValues() {
        assertEquals(2.0, median(listOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(2.5, median(listOf(4.0, 1.0, 3.0, 2.0)), 0.0)
        assertEquals(7.0, median(listOf(7.0)), 0.0)
        assertEquals(
            Double.MAX_VALUE,
            median(listOf(Double.MAX_VALUE, Double.MAX_VALUE)),
            0.0,
        )
        assertEquals(
            0.0,
            median(listOf(-Double.MAX_VALUE, Double.MAX_VALUE)),
            0.0,
        )
    }

    @Test
    fun medianRejectsEmptyAndNonFiniteInput() {
        assertThrows(IllegalArgumentException::class.java) { median(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { median(listOf(1.0, Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) {
            median(listOf(1.0, Double.POSITIVE_INFINITY))
        }
    }

    @Test
    fun emptyAndUnknownProductListsProduceNoModels() {
        assertTrue(buildTypeCapacityModels(emptyList()).isEmpty())
        assertTrue(
            buildTypeCapacityModels(
                listOf(
                    product("unknown-1", type = "UNKNOWN", status = "FINISHED", allQuantity = 10.0),
                    product("unknown-2", type = "unknown", status = "FINISHED", allQuantity = 11.0),
                    product("unknown-3", type = "  UNKNOWN ", status = "FINISHED", allQuantity = 12.0),
                    product("blank-1", type = "", status = "FINISHED", allQuantity = 13.0),
                    product("blank-2", type = " ", status = "FINISHED", allQuantity = 14.0),
                    product("blank-3", type = "\t", status = "FINISHED", allQuantity = 15.0),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun modelRequiresThreeValidFinishedProducts() {
        val two = finishedProducts(2)
        assertTrue(buildTypeCapacityModels(two).isEmpty())

        val model = checkNotNull(buildTypeCapacityModels(finishedProducts(3))["P"])
        assertEquals(3, model.sampleSize)
        assertEquals(11.0, model.medianRecordedUsesAtFinish, 0.0)
    }

    @Test
    fun capacityEvidenceReportsBelowThresholdAndPerGramCounts() {
        val products = listOf(
            product("one", type = " p ", status = "FINISHED", allQuantity = 10.0, grams = 1.0),
            product("two", type = "P", status = "FINISHED", allQuantity = 20.0, grams = null),
            product("invalid", type = "P", status = "FINISHED", allQuantity = Double.NaN, grams = 1.0),
            product("unknown", type = "UNKNOWN", status = "FINISHED", allQuantity = 30.0, grams = 1.0),
        )

        val evidence = buildTypeCapacityEvidence(products)

        assertEquals(
            TypeCapacityEvidence(type = "P", sampleSize = 2, perGramSampleSize = 1),
            evidence["P"],
        )
        assertFalse(evidence.containsKey("UNKNOWN"))
        assertTrue(buildTypeCapacityModels(products).isEmpty())
    }

    @Test
    fun modelIgnoresWrongStatusesAndInvalidRecordedQuantities() {
        val products = finishedProducts(3) + listOf(
            product("active", status = "ACTIVE", allQuantity = 400.0),
            product("unopened", status = "UNOPENED", allQuantity = 400.0),
            product("unknown-status", status = "UNKNOWN", allQuantity = 400.0),
            product("zero", status = "FINISHED", allQuantity = 0.0),
            product("negative", status = "FINISHED", allQuantity = -1.0),
            product("nan", status = "FINISHED", allQuantity = Double.NaN),
            product("infinity", status = "FINISHED", allQuantity = Double.POSITIVE_INFINITY),
        )

        val model = checkNotNull(buildTypeCapacityModels(products)["P"])
        assertEquals(3, model.sampleSize)
        assertEquals(11.0, model.medianRecordedUsesAtFinish, 0.0)
    }

    @Test
    fun modelNormalizesTypesAndMedianResistsAnOutlier() {
        val products = listOf(
            product("one", type = " p ", status = "FINISHED", allQuantity = 10.0),
            product("two", type = "P", status = "FINISHED", allQuantity = 11.0),
            product("outlier", type = "p", status = "FINISHED", allQuantity = 400.0),
        )

        val models = buildTypeCapacityModels(products)
        assertEquals(setOf("P"), models.keys)
        assertEquals(11.0, checkNotNull(models["P"]).medianRecordedUsesAtFinish, 0.0)
        assertTrue((10.0 + 11.0 + 400.0) / 3.0 > 100.0)
    }

    @Test
    fun perGramMedianRequiresThreeFinitePositiveRatios() {
        val products = listOf(
            product("one", status = "FINISHED", allQuantity = 10.0, grams = 1.0),
            product("two", status = "FINISHED", allQuantity = 20.0, grams = 2.0),
            product("three", status = "FINISHED", allQuantity = 30.0, grams = null),
            product("zero", status = "FINISHED", allQuantity = 40.0, grams = 0.0),
            product("negative", status = "FINISHED", allQuantity = 50.0, grams = -1.0),
            product("nan", status = "FINISHED", allQuantity = 60.0, grams = Double.NaN),
            product("infinite", status = "FINISHED", allQuantity = 70.0, grams = Double.POSITIVE_INFINITY),
            product("overflow", status = "FINISHED", allQuantity = Double.MAX_VALUE, grams = Double.MIN_VALUE),
        )

        val model = checkNotNull(buildTypeCapacityModels(products)["P"])
        assertEquals(8, model.sampleSize)
        assertEquals(2, model.perGramSampleSize)
        assertNull(model.medianRecordedUsesPerGramAtFinish)

        val eligible = modelWithFinishedProducts(total = 3, gramSamples = 3)
        assertEquals(3, eligible.perGramSampleSize)
        assertEquals(10.0, eligible.medianRecordedUsesPerGramAtFinish!!, 0.0)
    }

    @Test
    fun runwayUsesPerGramBasisAndItsActualEvidenceCount() {
        val models = buildTypeCapacityModels(finishedProducts(total = 8, gramSamples = 3))
        val runway = checkNotNull(
            buildProductRunway(
                product = activeProduct(grams = 1.0),
                models = models,
                range = range("2026-07-01", "2026-07-07", 7),
                analyticsTimeZone = NEW_YORK,
            ),
        )

        assertEquals(RunwayBasis.PER_GRAM, runway.basis)
        assertEquals(1.0, checkNotNull(runway.targetGrams), 0.0)
        assertEquals(3, runway.sampleSize)
        assertEquals(RunwayConfidence.LOW, runway.confidence)
        assertEquals(10.0, runway.estimatedTypicalFinishedUses, 0.0)
        assertTrue(runway.pace is RunwayPace.Ready)
    }

    @Test
    fun runwayUsesHighConfidenceOnlyForEightPerGramSamples() {
        val models = buildTypeCapacityModels(finishedProducts(total = 8, gramSamples = 8))
        val runway = checkNotNull(
            buildProductRunway(
                activeProduct(grams = 2.0),
                models,
                range("2026-07-01", "2026-07-07", 7),
                NEW_YORK,
            ),
        )

        assertEquals(RunwayBasis.PER_GRAM, runway.basis)
        assertEquals(2.0, checkNotNull(runway.targetGrams), 0.0)
        assertEquals(8, runway.sampleSize)
        assertEquals(RunwayConfidence.HIGH, runway.confidence)
        assertEquals(20.0, runway.estimatedTypicalFinishedUses, 0.0)
    }

    @Test
    fun exactSameTypeAndGramCohortWinsOverMixedSizeOutliers() {
        val products = listOf(
            product("one-gram-10", type = " p ", status = "FINISHED", allQuantity = 10.0, grams = 1.0),
            product("one-gram-12", type = "P", status = "FINISHED", allQuantity = 12.0, grams = 1.0),
            product("one-gram-14", type = "p", status = "FINISHED", allQuantity = 14.0, grams = 1.0),
            product("two-gram-100", type = "P", status = "FINISHED", allQuantity = 100.0, grams = 2.0),
            product("two-gram-120", type = "P", status = "FINISHED", allQuantity = 120.0, grams = 2.0),
            product("two-gram-140", type = "P", status = "FINISHED", allQuantity = 140.0, grams = 2.0),
        )

        val runway = checkNotNull(
            buildProductRunway(
                activeProduct(allQuantity = 4.0, rangeQuantity = 2.0, grams = 1.0),
                buildTypeCapacityModels(products),
                range("2026-07-01", "2026-07-07", 7),
                NEW_YORK,
            ),
        )

        assertEquals(RunwayBasis.MATCHED_GRAMS, runway.basis)
        assertEquals(1.0, checkNotNull(runway.targetGrams), 0.0)
        assertEquals(3, runway.sampleSize)
        assertEquals(12.0, runway.estimatedTypicalFinishedUses, 0.0)
        assertEquals(8.0, runway.estimatedRemainingToTypicalUses, 0.0)
        assertTrue(runway.pace is RunwayPace.Ready)
    }

    @Test
    fun canonicalGramEqualityJoinsEquivalentValuesButNotNearbyValues() {
        val products = listOf(
            product("one", status = "FINISHED", allQuantity = 10.0, grams = 1.0),
            product("one-point-zero", status = "FINISHED", allQuantity = 12.0, grams = 1.00),
            product("one-point-zero-zero", status = "FINISHED", allQuantity = 14.0, grams = 1.000),
            product(
                "nearby",
                status = "FINISHED",
                allQuantity = 999.0,
                grams = 1.0000001,
            ),
        )

        val model = checkNotNull(buildTypeCapacityModels(products)["P"])

        assertEquals(3, model.matchedGrams["1"]?.sampleSize)
        assertEquals(1, model.matchedGrams["1.0000001"]?.sampleSize)
        val runway = checkNotNull(
            buildProductRunway(
                activeProduct(grams = 1.0),
                mapOf("P" to model),
                range("2026-07-01", "2026-07-07", 7),
                NEW_YORK,
            ),
        )
        assertEquals(RunwayBasis.MATCHED_GRAMS, runway.basis)
        assertEquals(12.0, runway.estimatedTypicalFinishedUses, 0.0)
    }

    @Test
    fun undersizedExactCohortFallsBackToBroadPerGramEvidence() {
        val products = listOf(
            product("one-gram-10", status = "FINISHED", allQuantity = 10.0, grams = 1.0),
            product("two-gram-100", status = "FINISHED", allQuantity = 100.0, grams = 2.0),
            product("two-gram-120", status = "FINISHED", allQuantity = 120.0, grams = 2.0),
        )

        val runway = checkNotNull(
            buildProductRunway(
                activeProduct(grams = 1.0),
                buildTypeCapacityModels(products),
                range("2026-07-01", "2026-07-07", 7),
                NEW_YORK,
            ),
        )

        assertEquals(RunwayBasis.PER_GRAM, runway.basis)
        assertEquals(3, runway.sampleSize)
        assertEquals(50.0, runway.estimatedTypicalFinishedUses, 0.0)
    }

    @Test
    fun undersizedGramEvidenceFallsBackToSameTypePerProductEvidence() {
        val products = listOf(
            product("one-gram-10", status = "FINISHED", allQuantity = 10.0, grams = 1.0),
            product("two-gram-20", status = "FINISHED", allQuantity = 20.0, grams = 2.0),
            product("unknown-size-30", status = "FINISHED", allQuantity = 30.0, grams = null),
        )

        val runway = checkNotNull(
            buildProductRunway(
                activeProduct(grams = 1.0),
                buildTypeCapacityModels(products),
                range("2026-07-01", "2026-07-07", 7),
                NEW_YORK,
            ),
        )

        assertEquals(RunwayBasis.PER_PRODUCT, runway.basis)
        assertNull(runway.targetGrams)
        assertEquals(3, runway.sampleSize)
        assertEquals(20.0, runway.estimatedTypicalFinishedUses, 0.0)
    }

    @Test
    fun wrongTypeNearbyGramsAndInvalidInputsDoNotContaminateExactCohort() {
        val products = listOf(
            product("p-one-10", type = "P", status = "FINISHED", allQuantity = 10.0, grams = 1.0),
            product("p-one-11", type = "P", status = "FINISHED", allQuantity = 11.0, grams = 1.0),
            product("p-nearby", type = "P", status = "FINISHED", allQuantity = 999.0, grams = 1.01),
            product("other-type", type = "F", status = "FINISHED", allQuantity = 777.0, grams = 1.0),
            product("invalid-grams", type = "P", status = "FINISHED", allQuantity = 888.0, grams = 0.0),
            product("invalid-uses", type = "P", status = "FINISHED", allQuantity = Double.NaN, grams = 1.0),
        )

        val model = checkNotNull(buildTypeCapacityModels(products)["P"])
        assertEquals(4, model.sampleSize)
        assertEquals(3, model.perGramSampleSize)
        assertEquals(2, model.matchedGrams["1"]?.sampleSize)
        assertNull(buildTypeCapacityModels(products)["F"])

        val runway = checkNotNull(
            buildProductRunway(
                activeProduct(grams = 1.0),
                mapOf("P" to model),
                range("2026-07-01", "2026-07-07", 7),
                NEW_YORK,
            ),
        )
        assertEquals(RunwayBasis.PER_GRAM, runway.basis)
        assertEquals(11.0, runway.estimatedTypicalFinishedUses, 0.0)
    }

    @Test
    fun runwayConfidenceUsesExactEvidenceThresholds() {
        listOf(
            Triple(4, RunwayBasis.PER_PRODUCT, RunwayConfidence.LOW),
            Triple(5, RunwayBasis.PER_PRODUCT, RunwayConfidence.MEDIUM),
        ).forEach { (sampleSize, expectedBasis, expectedConfidence) ->
            val runway = checkNotNull(
                buildProductRunway(
                    activeProduct(),
                    mapOf("P" to capacityModel(sampleSize = sampleSize)),
                    range("2026-07-01", "2026-07-07", 7),
                    NEW_YORK,
                ),
            )
            assertEquals(expectedBasis, runway.basis)
            assertEquals(expectedConfidence, runway.confidence)
        }

        listOf(7 to RunwayConfidence.MEDIUM, 8 to RunwayConfidence.HIGH)
            .forEach { (sampleSize, expectedConfidence) ->
                val runway = checkNotNull(
                    buildProductRunway(
                        activeProduct(grams = 1.0),
                        mapOf(
                            "P" to capacityModel(
                                sampleSize = sampleSize,
                                medianRecordedUsesPerGramAtFinish = 20.0,
                                perGramSampleSize = sampleSize,
                            ),
                        ),
                        range("2026-07-01", "2026-07-07", 7),
                        NEW_YORK,
                    ),
                )
                assertEquals(RunwayBasis.PER_GRAM, runway.basis)
                assertEquals(expectedConfidence, runway.confidence)
            }
    }

    @Test
    fun runwayRejectsModelsThatDoNotMeetTheSelectedBasisMinimum() {
        assertNull(
            buildProductRunway(
                activeProduct(),
                mapOf("P" to capacityModel(sampleSize = 2)),
                range("2026-07-01", "2026-07-07", 7),
                NEW_YORK,
            ),
        )

        val fallsBack = checkNotNull(
            buildProductRunway(
                activeProduct(grams = 1.0),
                mapOf(
                    "P" to capacityModel(
                        sampleSize = 3,
                        medianRecordedUsesPerGramAtFinish = 20.0,
                        perGramSampleSize = 2,
                    ),
                ),
                range("2026-07-01", "2026-07-07", 7),
                NEW_YORK,
            ),
        )
        assertEquals(RunwayBasis.PER_PRODUCT, fallsBack.basis)
    }

    @Test
    fun targetWithoutUsableGramsFallsBackToPerProductEvidence() {
        val models = buildTypeCapacityModels(finishedProducts(total = 8, gramSamples = 8))

        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { grams ->
            val runway = checkNotNull(
                buildProductRunway(
                    activeProduct(grams = grams),
                    models,
                    range("2026-07-01", "2026-07-07", 7),
                    NEW_YORK,
                ),
            )
            assertEquals(RunwayBasis.PER_PRODUCT, runway.basis)
            assertEquals(8, runway.sampleSize)
            assertEquals(RunwayConfidence.MEDIUM, runway.confidence)
        }
    }

    @Test
    fun runwayUsesTheProductSpecificEffectiveDayWindow() {
        val models = mapOf("P" to capacityModel())
        val runway = checkNotNull(
            buildProductRunway(
                product = activeProduct(
                    allQuantity = 10.0,
                    rangeQuantity = 7.0,
                    firstLogAt = utcEpoch(2026, 7, 24, 16),
                ),
                models = models,
                range = range("2026-07-01", "2026-07-30", 30),
                analyticsTimeZone = NEW_YORK,
            ),
        )

        val pace = runway.pace as RunwayPace.Ready
        assertEquals(7, pace.effectiveBurnRateDays)
        assertEquals(1.0, pace.usesPerDay, 0.0)
        assertEquals(20.0, runway.estimatedTypicalFinishedUses, 0.0)
        assertEquals(10.0, runway.estimatedRemainingToTypicalUses, 0.0)
        assertEquals(10.0, pace.estimatedDaysRemaining, 0.0)
    }

    @Test
    fun runwayUsesRangeStartWhenFirstLogPredatesTheRange() {
        val runway = checkNotNull(
            buildProductRunway(
                product = activeProduct(
                    rangeQuantity = 30.0,
                    firstLogAt = utcEpoch(2026, 6, 1),
                ),
                models = mapOf("P" to capacityModel()),
                range = range("2026-07-01", "2026-07-30", 30),
                analyticsTimeZone = NEW_YORK,
            ),
        )

        val pace = runway.pace as RunwayPace.Ready
        assertEquals(30, pace.effectiveBurnRateDays)
        assertEquals(1.0, pace.usesPerDay, 0.0)
    }

    @Test
    fun runwayRequiresSevenEffectiveDaysOnlyForPace() {
        val models = mapOf("P" to capacityModel())
        val selectedRange = range("2026-07-01", "2026-07-30", 30)

        val tooFew = checkNotNull(
            buildProductRunway(
                activeProduct(firstLogAt = utcEpoch(2026, 7, 25, 16)),
                models,
                selectedRange,
                NEW_YORK,
            ),
        )
        assertEquals(RunwayPace.TooFewEffectiveDays(6), tooFew.pace)
        assertEquals(20.0, tooFew.estimatedTypicalFinishedUses, 0.0)

        val exactlySeven = checkNotNull(
            buildProductRunway(
                activeProduct(firstLogAt = utcEpoch(2026, 7, 24, 16)),
                models,
                selectedRange,
                NEW_YORK,
            ),
        )
        assertTrue(exactlySeven.pace is RunwayPace.Ready)
    }

    @Test
    fun brandNewActiveProductShowsItsFullEstimatedCapacityWithoutAPace() {
        val runway = checkNotNull(
            buildProductRunway(
                activeProduct(allQuantity = 0.0, rangeQuantity = 0.0, firstLogAt = null),
                mapOf("P" to capacityModel()),
                range("2026-07-01", "2026-07-07", 7),
                NEW_YORK,
            ),
        )

        assertEquals(0.0, runway.usesSoFar, 0.0)
        assertEquals(20.0, runway.estimatedTypicalFinishedUses, 0.0)
        assertEquals(20.0, runway.estimatedRemainingToTypicalUses, 0.0)
        assertEquals(RunwayPace.NoUseInRange, runway.pace)
    }

    @Test
    fun runwayRejectsInvalidProductRangeAndTimeZoneInputs() {
        val models = mapOf("P" to capacityModel())
        val validRange = range("2026-07-01", "2026-07-07", 7)

        listOf("UNOPENED", "FINISHED", "UNKNOWN").forEach { status ->
            assertNull(buildProductRunway(activeProduct(status = status), models, validRange, NEW_YORK))
        }
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { quantity ->
            val runway = checkNotNull(
                buildProductRunway(
                    activeProduct(rangeQuantity = quantity),
                    models,
                    validRange,
                    NEW_YORK,
                ),
            )
            assertEquals(RunwayPace.InvalidSnapshot, runway.pace)
        }
        val noUse = checkNotNull(
            buildProductRunway(activeProduct(rangeQuantity = 0.0), models, validRange, NEW_YORK),
        )
        assertEquals(RunwayPace.NoUseInRange, noUse.pace)
        assertEquals(20.0, noUse.estimatedTypicalFinishedUses, 0.0)
        val missingFirstLog = checkNotNull(
            buildProductRunway(activeProduct(firstLogAt = null), models, validRange, NEW_YORK),
        )
        assertEquals(RunwayPace.MissingFirstLog, missingFirstLog.pace)
        assertEquals(20.0, missingFirstLog.estimatedTypicalFinishedUses, 0.0)
        val firstLogAfterRange = checkNotNull(
            buildProductRunway(
                activeProduct(firstLogAt = utcEpoch(2026, 7, 8)),
                models,
                validRange,
                NEW_YORK,
            ),
        )
        assertEquals(RunwayPace.InvalidSnapshot, firstLogAfterRange.pace)
        val shortRange = checkNotNull(
            buildProductRunway(
                activeProduct(),
                models,
                range("2026-07-01", "2026-07-06", 6),
                NEW_YORK,
            ),
        )
        assertEquals(RunwayPace.SelectedRangeTooShort, shortRange.pace)
        assertNull(
            buildProductRunway(
                activeProduct(),
                models,
                range("2026-02-30", "2026-03-08", 7),
                NEW_YORK,
            ),
        )
        assertNull(buildProductRunway(activeProduct(), models, validRange, "Not/A_Zone"))
    }

    @Test
    fun runwayClampsPastTypicalUsageAndReturnsOnlyFiniteNumbers() {
        val runway = checkNotNull(
            buildProductRunway(
                activeProduct(allQuantity = 25.0, rangeQuantity = 7.0),
                mapOf("P" to capacityModel()),
                range("2026-07-01", "2026-07-07", 7),
                NEW_YORK,
            ),
        )

        assertEquals(0.0, runway.estimatedRemainingToTypicalUses, 0.0)
        val pace = runway.pace as RunwayPace.Ready
        assertEquals(0.0, pace.estimatedDaysRemaining, 0.0)
        assertTrue(
            listOf(
                runway.usesSoFar,
                runway.estimatedTypicalFinishedUses,
                runway.estimatedRemainingToTypicalUses,
                pace.usesPerDay,
                pace.estimatedDaysRemaining,
            ).all(Double::isFinite),
        )
    }

    @Test
    fun runwayRejectsAnOverflowingPerGramEstimate() {
        val overflowModel = capacityModel(
            medianRecordedUsesPerGramAtFinish = Double.MAX_VALUE,
            perGramSampleSize = 3,
        )

        assertNull(
            buildProductRunway(
                activeProduct(grams = 2.0),
                mapOf("P" to overflowModel),
                range("2026-07-01", "2026-07-07", 7),
                NEW_YORK,
            ),
        )
    }

    @Test
    fun projectsTrueCurrentMonthWithExactPersonalSpend() {
        val result = checkNotNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2026-07-01", "2026-07-15", 15),
                    months = listOf(month("2026-07", personalSpendCents = 15_000)),
                ),
                utcEpoch(2026, 7, 15, 16),
            ),
        )

        assertEquals("2026-07", result.month)
        assertEquals(15_000L, result.monthToDateCents)
        assertEquals(15, result.elapsedDays)
        assertEquals(31, result.daysInMonth)
        assertEquals(31_000L, result.projectedMonthEndCents)
    }

    @Test
    fun projectionRoundsHalfCentsUpWithoutUsingBinaryFloatingPoint() {
        val result = checkNotNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2026-07-01", "2026-07-02", 2),
                    months = listOf(month("2026-07", personalSpendCents = 1)),
                ),
                utcEpoch(2026, 7, 2, 16),
            ),
        )

        assertEquals(16L, result.projectedMonthEndCents)
    }

    @Test
    fun projectionHandlesFirstLastAndLeapYearDays() {
        val first = checkNotNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2026-07-01", "2026-07-01", 1),
                    months = listOf(month("2026-07", personalSpendCents = 100)),
                ),
                utcEpoch(2026, 7, 1, 16),
            ),
        )
        assertEquals(3_100L, first.projectedMonthEndCents)

        val last = checkNotNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2026-07-01", "2026-07-31", 31),
                    months = listOf(month("2026-07", personalSpendCents = 31_001)),
                ),
                utcEpoch(2026, 7, 31, 16),
            ),
        )
        assertEquals(31_001L, last.projectedMonthEndCents)

        val leap = checkNotNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2024-02-01", "2024-02-15", 15),
                    months = listOf(month("2024-02", personalSpendCents = 1_500)),
                ),
                utcEpoch(2024, 2, 15, 17),
            ),
        )
        assertEquals(29, leap.daysInMonth)
        assertEquals(2_900L, leap.projectedMonthEndCents)

        val nonLeapCentury = checkNotNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2100-02-01", "2100-02-15", 15),
                    months = listOf(month("2100-02", personalSpendCents = 1_500)),
                ),
                utcEpoch(2100, 2, 15, 17),
            ),
        )
        assertEquals(28, nonLeapCentury.daysInMonth)
        assertEquals(2_800L, nonLeapCentury.projectedMonthEndCents)
    }

    @Test
    fun projectionRequiresACompleteRangeThroughToday() {
        val now = utcEpoch(2026, 7, 15, 16)
        val monthBucket = listOf(month("2026-07", personalSpendCents = 1_500))

        assertNull(
            projectCurrentMonthSpend(
                insights(range = range("2026-07-02", "2026-07-15", 14), months = monthBucket),
                now,
            ),
        )
        assertNull(
            projectCurrentMonthSpend(
                insights(range = range("2026-07-01", "2026-07-14", 14), months = monthBucket),
                now,
            ),
        )
        assertNull(
            projectCurrentMonthSpend(
                insights(range = range("2026-07-01", "2026-07-16", 16), months = monthBucket),
                now,
            ),
        )
        assertNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2026-06-01", "2026-06-30", 30),
                    months = listOf(month("2026-06", personalSpendCents = 1_500)),
                ),
                now,
            ),
        )

        assertTrue(
            projectCurrentMonthSpend(
                insights(
                    range = range("2026-06-01", "2026-07-15", 45),
                    months = listOf(
                        month("2026-06", personalSpendCents = 1_000),
                        month("2026-07", personalSpendCents = 1_500),
                    ),
                ),
                now,
            ) != null,
        )
    }

    @Test
    fun projectionRejectsMalformedRangesAndMissingOrDuplicateMonths() {
        val now = utcEpoch(2026, 7, 15, 16)
        val july = month("2026-07", personalSpendCents = 1_500)

        listOf(
            range("2026-07-01", "2026-07-15", 14),
            range("2026-07-31", "2026-07-15", -15),
            range("2026-02-30", "2026-07-15", 1),
        ).forEach { invalid ->
            assertNull(projectCurrentMonthSpend(insights(range = invalid, months = listOf(july)), now))
        }
        assertNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2026-07-01", "2026-07-15", 15),
                    months = emptyList(),
                ),
                now,
            ),
        )
        assertNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2026-07-01", "2026-07-15", 15),
                    months = listOf(july, july.copy(personalSpendCents = 2_000)),
                ),
                now,
            ),
        )
    }

    @Test
    fun projectionUsesPersonalSpendAndRejectsUnknownPersonalCost() {
        val validRange = range("2026-07-01", "2026-07-15", 15)
        val now = utcEpoch(2026, 7, 15, 16)
        val borrowedIgnored = checkNotNull(
            projectCurrentMonthSpend(
                insights(
                    range = validRange,
                    months = listOf(
                        month(
                            "2026-07",
                            personalSpendCents = 1_500,
                            borrowedRecordedValueCents = 900_000,
                        ),
                    ),
                ),
                now,
            ),
        )
        assertEquals(3_100L, borrowedIgnored.projectedMonthEndCents)

        assertNull(
            projectCurrentMonthSpend(
                insights(
                    range = validRange,
                    months = listOf(
                        month(
                            "2026-07",
                            personalSpendCents = 1_500,
                            unknownPersonalCostCount = 1,
                        ),
                    ),
                ),
                now,
            ),
        )
    }

    @Test
    fun projectionRejectsPurchaseRowsOmittedBeforeAnalyticsNormalization() {
        val response = insights(
            range = range("2026-07-01", "2026-07-15", 15),
            months = listOf(month("2026-07", personalSpendCents = 1_500)),
        )
        val omittedPurchaseResponse = response.copy(
            dataQuality = response.dataQuality.copy(
                warnings = response.dataQuality.warnings.copy(
                    invalidUnreferencedPurchaseRowCount = 1,
                ),
            ),
        )

        assertNull(
            projectCurrentMonthSpend(
                omittedPurchaseResponse,
                utcEpoch(2026, 7, 15, 16),
            ),
        )
    }

    @Test
    fun projectionSuppressesOnlyUnknownDatesThatCouldAffectPersonalSpend() {
        val validRange = range("2026-07-01", "2026-07-15", 15)
        val month = listOf(month("2026-07", personalSpendCents = 1_500))
        val now = utcEpoch(2026, 7, 15, 16)

        assertNull(
            projectCurrentMonthSpend(
                insights(
                    range = validRange,
                    months = month,
                    products = listOf(
                        product(
                            id = "unknown-personal-date",
                            borrowed = false,
                            purchaseDate = null,
                            purchaseDateSource = "UNKNOWN",
                            finalCostCents = 100,
                        ),
                    ),
                ),
                now,
            ),
        )

        assertTrue(
            projectCurrentMonthSpend(
                insights(
                    range = validRange,
                    months = month,
                    products = listOf(
                        personalPurchase("2026-07-01", 1_500),
                        product(
                            id = "unknown-borrowed-date",
                            borrowed = true,
                            purchaseDate = null,
                            purchaseDateSource = "UNKNOWN",
                            finalCostCents = null,
                        ),
                    ),
                ),
                now,
            ) != null,
        )

        assertNull(
            projectCurrentMonthSpend(
                insights(
                    range = validRange,
                    months = month,
                    products = listOf(
                        product(
                            id = "unknown-owner",
                            borrowed = null,
                            purchaseDate = "2026-07-10",
                            finalCostCents = 100,
                        ),
                    ),
                ),
                now,
            ),
        )

        assertTrue(
            projectCurrentMonthSpend(
                insights(
                    range = validRange,
                    months = month,
                    products = listOf(
                        personalPurchase("2026-07-01", 1_500),
                        product(
                            id = "future-unknown-owner",
                            borrowed = null,
                            purchaseDate = "2026-07-20",
                            finalCostCents = 100,
                        ),
                    ),
                ),
                now,
            ) != null,
        )
    }

    @Test
    fun projectionRejectsMissingCurrentPersonalCostAndCountsEstimatedDates() {
        val validRange = range("2026-07-01", "2026-07-15", 15)
        val month = listOf(month("2026-07", personalSpendCents = 1_500, personalPurchaseCount = 1))
        val now = utcEpoch(2026, 7, 15, 16)

        assertNull(
            projectCurrentMonthSpend(
                insights(
                    range = validRange,
                    months = month,
                    products = listOf(
                        product(
                            id = "unknown-cost",
                            borrowed = false,
                            purchaseDate = "2026-07-10",
                            finalCostCents = null,
                        ),
                    ),
                ),
                now,
            ),
        )

        val estimated = checkNotNull(
            projectCurrentMonthSpend(
                insights(
                    range = validRange,
                    months = month,
                    products = listOf(
                        product(
                            id = "estimated-date",
                            borrowed = false,
                            purchaseDate = "2026-07-10",
                            purchaseDateSource = "CREATED_AT_FALLBACK",
                            finalCostCents = 1_500,
                        ),
                    ),
                ),
                now,
            ),
        )
        assertEquals(1, estimated.estimatedPersonalPurchaseDateCount)
    }

    @Test
    fun projectionUsesBigDecimalPrecisionAndReturnsNullOnOverflow() {
        val exactCents = 9_007_199_254_740_993L
        val exact = checkNotNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2026-07-01", "2026-07-01", 1),
                    months = listOf(month("2026-07", personalSpendCents = exactCents)),
                ),
                utcEpoch(2026, 7, 1, 16),
            ),
        )
        assertEquals(279_223_176_896_970_783L, exact.projectedMonthEndCents)

        assertNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2026-07-01", "2026-07-01", 1),
                    months = listOf(month("2026-07", personalSpendCents = Long.MAX_VALUE)),
                ),
                utcEpoch(2026, 7, 1, 16),
            ),
        )
        assertNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2026-07-01", "2026-07-15", 15),
                    months = listOf(month("2026-07", personalSpendCents = -1)),
                ),
                utcEpoch(2026, 7, 15, 16),
            ),
        )
    }

    @Test
    fun projectionAllowsACompleteZeroSpendMonth() {
        val result = checkNotNull(
            projectCurrentMonthSpend(
                insights(
                    range = range("2026-07-01", "2026-07-15", 15),
                    months = listOf(month("2026-07", personalSpendCents = 0)),
                ),
                utcEpoch(2026, 7, 15, 16),
            ),
        )
        assertEquals(0L, result.monthToDateCents)
        assertEquals(0L, result.projectedMonthEndCents)
    }

    @Test
    fun runwayAndProjectionIgnoreTheJvmDefaultTimeZone() {
        val response = insights(
            range = range("2026-07-01", "2026-07-15", 15),
            months = listOf(month("2026-07", personalSpendCents = 15_000)),
        )
        // This is July 15 in the response's New York zone and Kiritimati, but July 14
        // in Midway. A device-default implementation would therefore disagree.
        val now = utcEpoch(2026, 7, 15, 5)
        val product = activeProduct(
            rangeQuantity = 8.0,
            firstLogAt = utcEpoch(2026, 7, 9, 2),
        )
        val models = mapOf("P" to capacityModel())

        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
        val eastProjection = projectCurrentMonthSpend(response, now)
        val eastRunway = buildProductRunway(product, models, response.range, response.timeZone)

        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Midway"))
        val westProjection = projectCurrentMonthSpend(response, now)
        val westRunway = buildProductRunway(product, models, response.range, response.timeZone)

        assertEquals(eastProjection, westProjection)
        assertTrue(eastProjection != null)
        assertEquals(eastRunway, westRunway)
        assertEquals(
            8,
            (checkNotNull(eastRunway).pace as RunwayPace.Ready).effectiveBurnRateDays,
        )
    }

    @Test
    fun projectionUsesTheResponseTimeZoneAtAMidnightBoundary() {
        val instant = utcEpoch(2026, 7, 15, 2)
        val newYorkResponse = insights(
            timeZone = NEW_YORK,
            range = range("2026-07-01", "2026-07-14", 14),
            months = listOf(month("2026-07", personalSpendCents = 1_400)),
        )
        val utcResponse = insights(
            timeZone = "UTC",
            range = range("2026-07-01", "2026-07-15", 15),
            months = listOf(month("2026-07", personalSpendCents = 1_500)),
        )

        assertEquals(14, checkNotNull(projectCurrentMonthSpend(newYorkResponse, instant)).elapsedDays)
        assertEquals(15, checkNotNull(projectCurrentMonthSpend(utcResponse, instant)).elapsedDays)
        assertNull(
            projectCurrentMonthSpend(
                newYorkResponse.copy(range = range("2026-07-01", "2026-07-15", 15)),
                instant,
            ),
        )
    }

    @Test
    fun invalidResponseTimeZoneNeverFallsBackToGmt() {
        val response = insights(
            timeZone = "Not/A_Zone",
            range = range("2026-07-01", "2026-07-15", 15),
            months = listOf(month("2026-07", personalSpendCents = 15_000)),
        )
        assertNull(projectCurrentMonthSpend(response, utcEpoch(2026, 7, 15, 16)))
    }

    private fun modelWithFinishedProducts(total: Int, gramSamples: Int): TypeCapacityModel =
        checkNotNull(buildTypeCapacityModels(finishedProducts(total, gramSamples))["P"])

    private fun finishedProducts(total: Int, gramSamples: Int = 0): List<AnalyticsProductDto> =
        List(total) { index ->
            val quantity = 10.0 + index
            product(
                id = "finished-$index",
                status = "FINISHED",
                allQuantity = quantity,
                grams = if (index < gramSamples) quantity / 10.0 else null,
            )
        }

    private fun activeProduct(
        status: String = "ACTIVE",
        allQuantity: Double = 5.0,
        rangeQuantity: Double = 7.0,
        grams: Double? = null,
        firstLogAt: Long? = utcEpoch(2026, 7, 1, 16),
    ): AnalyticsProductDto = product(
        id = "active",
        status = status,
        allQuantity = allQuantity,
        rangeQuantity = rangeQuantity,
        grams = grams,
        firstLogAt = firstLogAt,
    )

    private fun capacityModel(
        sampleSize: Int = 5,
        medianRecordedUsesAtFinish: Double = 20.0,
        medianRecordedUsesPerGramAtFinish: Double? = null,
        perGramSampleSize: Int = 0,
    ) = TypeCapacityModel(
        type = "P",
        sampleSize = sampleSize,
        medianRecordedUsesAtFinish = medianRecordedUsesAtFinish,
        medianRecordedUsesPerGramAtFinish = medianRecordedUsesPerGramAtFinish,
        perGramSampleSize = perGramSampleSize,
    )

    private fun product(
        id: String,
        type: String = "P",
        status: String = "ACTIVE",
        borrowed: Boolean? = false,
        purchaseDate: String? = "2026-01-01",
        purchaseDateSource: String = "RECORDED",
        finalCostCents: Long? = 1_000,
        allQuantity: Double = 5.0,
        rangeQuantity: Double = 0.0,
        grams: Double? = null,
        firstLogAt: Long? = utcEpoch(2026, 1, 1, 17),
    ) = AnalyticsProductDto(
        productId = id,
        name = "Product $id",
        type = type,
        status = status,
        borrowed = borrowed,
        purchaseDate = purchaseDate,
        purchaseDateSource = purchaseDateSource,
        finalCostCents = finalCostCents,
        grams = grams,
        thcQuality = "UNKNOWN",
        allTime = ProductActivityDto(
            logCount = if (allQuantity > 0.0 && allQuantity.isFinite()) 1 else 0,
            quantity = allQuantity,
            activeDayCount = if (allQuantity > 0.0 && allQuantity.isFinite()) 1 else 0,
            firstLogAtEpochMillis = firstLogAt,
            lastLogAtEpochMillis = firstLogAt,
        ),
        range = ProductRangeActivityDto(
            logCount = if (rangeQuantity > 0.0 && rangeQuantity.isFinite()) 1 else 0,
            quantity = rangeQuantity,
            activeDayCount = if (rangeQuantity > 0.0 && rangeQuantity.isFinite()) 1 else 0,
        ),
        completedValueComparisonEligible = false,
    )

    private fun range(from: String, to: String, dayCount: Int) =
        AnalyticsRangeDto(scope = "CUSTOM", from = from, to = to, dayCount = dayCount)

    private fun month(
        month: String,
        personalSpendCents: Long,
        personalPurchaseCount: Int = if (personalSpendCents == 0L) 0 else 1,
        borrowedRecordedValueCents: Long = 0,
        unknownPersonalCostCount: Int = 0,
    ) = MonthlySpendDto(
        month = month,
        personalSpendCents = personalSpendCents,
        personalPurchaseCount = personalPurchaseCount,
        borrowedRecordedValueCents = borrowedRecordedValueCents,
        borrowedPurchaseCount = 0,
        unknownPersonalCostCount = unknownPersonalCostCount,
        unknownBorrowedCostCount = 0,
        estimatedDateCount = 0,
        unknownDateCount = 0,
    )

    private fun insights(
        timeZone: String = NEW_YORK,
        range: AnalyticsRangeDto,
        months: List<MonthlySpendDto>,
        products: List<AnalyticsProductDto> = defaultPersonalPurchases(months),
    ) = InsightsResponseDto(
        success = true,
        analyticsVersion = ANALYTICS_VERSION,
        resource = "insights",
        environment = "PRODUCTION",
        timeZone = timeZone,
        range = range,
        overview = OverviewDto(
            logCount = 0,
            activeDayCount = 0,
            distinctProductCount = 0,
        ),
        dailyActivity = emptyList(),
        byWeekday = emptyList(),
        byHour = emptyList(),
        inventory = InventoryDto(
            activeCount = 0,
            unopenedCount = 0,
            finishedCount = 0,
            unknownStatusCount = 0,
            currentPersonalOriginalCostCents = 0,
            currentBorrowedRecordedValueCents = 0,
            unknownCurrentCostCount = 0,
        ),
        byType = emptyList(),
        products = products,
        spending = SpendingDto(
            allTime = spendBucket(),
            range = spendBucket(),
            byMonth = months,
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
            eventRowCount = 0,
        ),
        generatedAtEpochMillis = 0,
        serverDurationMs = 0,
    )

    private fun defaultPersonalPurchases(
        months: List<MonthlySpendDto>,
    ): List<AnalyticsProductDto> = months.flatMap { bucket ->
        when {
            bucket.personalPurchaseCount == 0 -> emptyList()
            bucket.personalPurchaseCount == 1 -> listOf(
                personalPurchase("${bucket.month}-01", bucket.personalSpendCents),
            )
            else -> List(bucket.personalPurchaseCount) { index ->
                val base = bucket.personalSpendCents / bucket.personalPurchaseCount
                val remainder = bucket.personalSpendCents % bucket.personalPurchaseCount
                personalPurchase(
                    date = "${bucket.month}-01",
                    costCents = base + if (index == 0) remainder else 0,
                    idSuffix = "$index",
                )
            }
        }
    }

    private fun personalPurchase(
        date: String,
        costCents: Long,
        idSuffix: String = date,
    ): AnalyticsProductDto = product(
        id = "personal-$idSuffix",
        borrowed = false,
        purchaseDate = date,
        purchaseDateSource = "RECORDED",
        finalCostCents = costCents,
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

    private fun utcEpoch(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
    ): Long = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).run {
        isLenient = false
        clear()
        set(year, month - 1, day, hour, 0, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    companion object {
        private const val NEW_YORK = "America/New_York"
    }
}
