package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import com.example.domain.ProductRunway
import com.example.domain.RunwayBasis
import com.example.domain.RunwayConfidence
import com.example.domain.RunwayPace
import com.example.domain.SpendRunRate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InsightsRunwayTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun runwayRowsUseExactProductIdsSortNearestFirstAndExpandPastFive() {
        val products = listOf(
            analyticsProduct("p1", "Eight days"),
            analyticsProduct("p2", "Three days"),
            analyticsProduct("p3", "Twelve days"),
            analyticsProduct("p4", "One day"),
            analyticsProduct("p5", "Four days"),
            analyticsProduct("p6", "Two days"),
            // Deliberately shares a name with p4. Name fallback would render an extra row.
            analyticsProduct("unmatched", "One day"),
        )
        val estimates = ready(
            runways = listOf(
                runway("p1", days = 8.0),
                runway("p2", days = 3.0),
                runway("p3", days = 12.0),
                runway("p4", days = 1.0),
                runway("p5", days = 4.0),
                runway("p6", days = 2.0),
            ),
        )

        composeRule.setContent {
            MaterialTheme {
                RunwaySection(estimates = estimates, products = products)
            }
        }

        composeRule.onNodeWithTag(InsightsRunwayTestTags.SECTION).assertIsDisplayed()
        val expectedOrder = listOf("p4", "p6", "p2", "p5", "p1")
        expectedOrder.forEach { id ->
            composeRule.onNodeWithTag(InsightsRunwayTestTags.row(id)).fetchSemanticsNode()
        }
        val renderedTopPositions = expectedOrder.map { id ->
            composeRule.onNodeWithTag(InsightsRunwayTestTags.row(id))
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        }
        assertEquals(renderedTopPositions.sorted(), renderedTopPositions)
        composeRule.onAllNodesWithTag(InsightsRunwayTestTags.row("p3")).assertCountEquals(0)
        composeRule.onAllNodesWithTag(InsightsRunwayTestTags.row("unmatched")).assertCountEquals(0)

        composeRule.onNodeWithTag(InsightsRunwayTestTags.SHOW_ALL).performClick()
        composeRule.onNodeWithTag(InsightsRunwayTestTags.row("p3")).fetchSemanticsNode()
        composeRule.onAllNodesWithTag(InsightsRunwayTestTags.row("unmatched")).assertCountEquals(0)
    }

    @Test
    fun runwayRowNamesItsRecordedEvidence() {
        val product = analyticsProduct("pen-1", "Blue cart")
        val estimate = runway(
            productId = product.productId,
            days = 4.5,
            basis = RunwayBasis.PER_GRAM,
            confidence = RunwayConfidence.HIGH,
            sampleSize = 7,
        )

        composeRule.setContent {
            MaterialTheme {
                RunwaySection(
                    estimates = ready(listOf(estimate)),
                    products = listOf(product),
                )
            }
        }

        composeRule.onNodeWithTag(InsightsRunwayTestTags.row(product.productId)).assertIsDisplayed()
        composeRule.onNode(hasText("7 finished Pen products", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("recorded grams", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("14-day recorded-use rate", substring = true)).assertIsDisplayed()
    }

    @Test
    fun matchedGramCapacityOnlyRowNamesExactEvidenceAndExplainsMissingPace() {
        val product = analyticsProduct("matched-pen", "Matched-size cart")
        val estimate = runway(
            productId = product.productId,
            days = 10.0,
            basis = RunwayBasis.MATCHED_GRAMS,
            confidence = RunwayConfidence.LOW,
            sampleSize = 3,
            paceOverride = RunwayPace.TooFewEffectiveDays(effectiveBurnRateDays = 4),
        ).copy(
            usesSoFar = 8.0,
            estimatedTypicalFinishedUses = 20.0,
            estimatedRemainingToTypicalUses = 12.0,
        )

        composeRule.setContent {
            MaterialTheme {
                RunwaySection(
                    estimates = ready(listOf(estimate)),
                    products = listOf(product),
                )
            }
        }

        composeRule.onNodeWithTag(InsightsRunwayTestTags.row(product.productId))
            .assertIsDisplayed()
            .assert(hasText("3 finished Pen products at 1 g", substring = true))
            .assert(hasText("~12 uses to the typical ~20 uses recorded at finish", substring = true))
            .assert(hasText("4 days available", substring = true))
        composeRule.onAllNodes(hasText("recorded-use rate", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("to the usual recorded finish amount", substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun runwaySectionExplainsInsufficientEvidenceAndNoUse() {
        val estimates = RunwayEstimateState.Ready(
            runwayByProductId = emptyMap(),
            evidenceByType = emptyMap(),
            spendRunRate = null,
            diagnostics = listOf(
                RunwayDiagnostic.InsufficientFinishEvidence(type = "P", availableSampleSize = 1),
                RunwayDiagnostic.NoUseInRange(
                    type = "F",
                    productCount = 1,
                ),
            ),
        )

        composeRule.setContent {
            MaterialTheme {
                RunwaySection(
                    estimates = estimates,
                    products = listOf(
                        analyticsProduct("pen-no-model", "Quiet pen"),
                        analyticsProduct("flower-1", "Quiet flower", type = "F"),
                    ),
                )
            }
        }

        composeRule.onNode(
            hasText(
                "Not enough recorded finished Pen products yet — 3 needed, 1 available.",
                substring = true,
            ),
        ).assertIsDisplayed()
        composeRule.onNode(
            hasText(
                "No Flower runway estimate for 1 active product with no recorded use",
                substring = true,
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun diagnosticsAreBoundedUntilDetailsAreExpanded() {
        val diagnostics = listOf("P", "F", "PR", "C", "E", "O").map { type ->
            RunwayDiagnostic.InsufficientFinishEvidence(
                type = type,
                availableSampleSize = 0,
            )
        }

        composeRule.setContent {
            MaterialTheme {
                RunwaySection(
                    estimates = RunwayEstimateState.Ready(
                        runwayByProductId = emptyMap(),
                        evidenceByType = emptyMap(),
                        spendRunRate = null,
                        diagnostics = diagnostics,
                    ),
                    products = emptyList(),
                )
            }
        }

        composeRule.onAllNodesWithTag(
            InsightsRunwayTestTags.diagnostic("O", 5),
        ).assertCountEquals(0)
        composeRule.onNodeWithTag(InsightsRunwayTestTags.SHOW_ALL).performClick()
        composeRule.onNodeWithTag(InsightsRunwayTestTags.diagnostic("O", 5)).assertIsDisplayed()
    }

    @Test
    fun productSheetShowsTheRunwayForItsExactProductId() {
        val product = analyticsProduct("sheet-product", "Sheet cart")
        val estimate = runway(product.productId, days = 4.0)

        composeRule.setContent {
            MaterialTheme {
                InsightsContent(
                    state = InsightsUiState(data = insights(listOf(product))),
                    pendingCount = 0,
                    isSyncing = false,
                    onSync = {},
                    onRefresh = {},
                    runwayState = ready(listOf(estimate)),
                )
            }
        }

        val productRow = hasText(product.name) and hasClickAction()
        composeRule.onNodeWithTag(InsightsRunwayTestTags.CONTENT)
            .performScrollToNode(productRow)
        composeRule.onNode(productRow).performClick()

        composeRule.onNodeWithTag(InsightsRunwayTestTags.sheet(product.productId))
            .assertIsDisplayed()
            .assert(
                hasAnyDescendant(
                    hasText("from recorded totals for 5 finished Pen products", substring = true),
                ),
            )
    }

    @Test
    fun expandedInsightsUseInlineDetailWithoutOpeningTheCompactSheet() {
        val product = analyticsProduct("expanded-product", "Expanded cart")

        composeRule.setContent {
            MaterialTheme {
                InsightsContent(
                    state = InsightsUiState(data = insights(listOf(product))),
                    pendingCount = 0,
                    isSyncing = false,
                    onSync = {},
                    onRefresh = {},
                    windowWidth = WindowWidth.EXPANDED,
                )
            }
        }

        composeRule.onNodeWithTag(AdaptiveInsightsTestTags.INSIGHTS_LIST_PANE).assertIsDisplayed()
        composeRule.onNodeWithTag(AdaptiveInsightsTestTags.INSIGHTS_DETAIL_PANE).assertIsDisplayed()
        composeRule.onNodeWithTag(AdaptiveInsightsTestTags.DETAIL_PLACEHOLDER).assertIsDisplayed()

        val productRow = hasText(product.name) and hasClickAction()
        composeRule.onNodeWithTag(InsightsRunwayTestTags.CONTENT)
            .performScrollToNode(productRow)
        composeRule.onNode(productRow).performClick()

        composeRule.onNodeWithTag(AdaptiveInsightsTestTags.INSIGHTS_DETAIL_PANE)
            .assert(
                hasAnyDescendant(hasText("${product.status} · ${product.type} · ${product.productId}")),
            )
        composeRule.onAllNodesWithTag(InsightsRunwayTestTags.sheet(product.productId))
            .assertCountEquals(0)
    }

    @Test
    fun openProductDetailSurvivesSavedStateRestoration() {
        val product = analyticsProduct("restored-product", "Restored cart")
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            MaterialTheme {
                InsightsContent(
                    state = InsightsUiState(data = insights(listOf(product))),
                    pendingCount = 0,
                    isSyncing = false,
                    onSync = {},
                    onRefresh = {},
                )
            }
        }

        val productRow = hasText(product.name) and hasClickAction()
        composeRule.onNodeWithTag(InsightsRunwayTestTags.CONTENT)
            .performScrollToNode(productRow)
        composeRule.onNode(productRow).performClick()
        composeRule.onNodeWithTag(InsightsRunwayTestTags.sheet(product.productId)).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(InsightsRunwayTestTags.sheet(product.productId)).assertIsDisplayed()
    }

    @Test
    fun admittedSpendProjectionNamesEstimatedPurchaseDate() {
        val product = analyticsProduct("p1", "Blue cart")
        val data = insights(products = listOf(product))
        val admitted = RunwayEstimateState.Ready(
            runwayByProductId = mapOf(product.productId to runway(product.productId, days = 4.0)),
            evidenceByType = emptyMap(),
            spendRunRate = SpendRunRate(
                month = "2026-08",
                monthToDateCents = 12_345,
                elapsedDays = 13,
                daysInMonth = 31,
                projectedMonthEndCents = 29_430,
                personalPurchaseCount = 2,
                estimatedPersonalPurchaseDateCount = 1,
            ),
            diagnostics = emptyList(),
        )
        composeRule.setContent {
            MaterialTheme {
                InsightsContent(
                    state = InsightsUiState(data = data),
                    pendingCount = 0,
                    isSyncing = false,
                    onSync = {},
                    onRefresh = {},
                    runwayState = admitted,
                )
            }
        }

        composeRule.onNodeWithTag(InsightsRunwayTestTags.CONTENT)
            .performScrollToNode(hasTestTag(InsightsRunwayTestTags.SPEND_PROJECTION))
        composeRule.onNodeWithTag(InsightsRunwayTestTags.SPEND_PROJECTION).assertIsDisplayed()
        composeRule.onNode(
            hasText("1 purchase date was estimated from creation time", substring = true),
        ).assertIsDisplayed()
    }

    @Test
    fun suppressedEstimatesExplainThemselvesWithoutRenderingRows() {
        composeRule.setContent {
            MaterialTheme {
                RunwaySection(
                    estimates = RunwayEstimateState.Suppressed(RunwaySuppressionReason.STALE_SNAPSHOT),
                    products = listOf(analyticsProduct("p1", "Blue cart")),
                )
            }
        }

        composeRule.onNodeWithTag(InsightsRunwayTestTags.SECTION).assertIsDisplayed()
        composeRule.onNodeWithTag(InsightsRunwayTestTags.SUPPRESSION_NOTICE).assertIsDisplayed()
        composeRule.onNode(
            hasText("Runway estimates pause until Insights refreshes from the sheet.", substring = true),
        ).assertIsDisplayed()
        composeRule.onAllNodesWithTag(InsightsRunwayTestTags.row("p1")).assertCountEquals(0)
        composeRule.onAllNodesWithTag(InsightsRunwayTestTags.SPEND_PROJECTION).assertCountEquals(0)
    }

    private fun ready(runways: List<ProductRunway>) = RunwayEstimateState.Ready(
        runwayByProductId = runways.associateBy(ProductRunway::productId),
        evidenceByType = emptyMap(),
        spendRunRate = null,
        diagnostics = emptyList(),
    )

    private fun runway(
        productId: String,
        days: Double,
        basis: RunwayBasis = RunwayBasis.PER_PRODUCT,
        confidence: RunwayConfidence = RunwayConfidence.MEDIUM,
        sampleSize: Int = 5,
        paceOverride: RunwayPace? = null,
    ) = ProductRunway(
        productId = productId,
        type = "P",
        usesSoFar = 8.0,
        estimatedTypicalFinishedUses = 20.0,
        estimatedRemainingToTypicalUses = 12.0,
        pace = paceOverride ?: RunwayPace.Ready(
            usesPerDay = 12.0 / days,
            effectiveBurnRateDays = 14,
            estimatedDaysRemaining = days,
        ),
        basis = basis,
        targetGrams = if (basis == RunwayBasis.PER_PRODUCT) null else 1.0,
        confidence = confidence,
        sampleSize = sampleSize,
    )

    private fun analyticsProduct(
        productId: String,
        name: String,
        type: String = "P",
    ) = AnalyticsProductDto(
        productUuid = "shared-uuid",
        productId = productId,
        name = name,
        type = type,
        status = "ACTIVE",
        borrowed = false,
        purchaseDate = "2026-08-01",
        purchaseDateSource = "RECORDED",
        finalCostCents = 1_000,
        grams = 1.0,
        thcQuality = "UNKNOWN",
        allTime = ProductActivityDto(
            logCount = 8,
            quantity = 8.0,
            activeDayCount = 8,
            firstLogAtEpochMillis = 1_754_044_800_000,
            lastLogAtEpochMillis = 1_755_168_000_000,
        ),
        range = ProductRangeActivityDto(logCount = 4, quantity = 4.0, activeDayCount = 4),
        completedValueComparisonEligible = false,
    )

    private fun insights(products: List<AnalyticsProductDto>) = InsightsResponseDto(
        success = true,
        analyticsVersion = ANALYTICS_VERSION,
        resource = "insights",
        environment = "PRODUCTION",
        timeZone = "America/New_York",
        range = AnalyticsRangeDto(
            scope = "CUSTOM",
            from = "2026-08-01",
            to = "2026-08-13",
            dayCount = 13,
        ),
        overview = OverviewDto(
            logCount = 4,
            activeDayCount = 4,
            distinctProductCount = 1,
        ),
        dailyActivity = emptyList(),
        byWeekday = emptyList(),
        byHour = emptyList(),
        inventory = InventoryDto(
            activeCount = products.size,
            unopenedCount = 0,
            finishedCount = 0,
            unknownStatusCount = 0,
            currentPersonalOriginalCostCents = 1_000,
            currentBorrowedRecordedValueCents = 0,
            unknownCurrentCostCount = 0,
        ),
        byType = emptyList(),
        products = products,
        spending = SpendingDto(
            allTime = spendBucket(personalSpendCents = 12_345, personalPurchaseCount = 2),
            range = spendBucket(personalSpendCents = 12_345, personalPurchaseCount = 2),
            byMonth = listOf(
                MonthlySpendDto(
                    month = "2026-08",
                    personalSpendCents = 12_345,
                    personalPurchaseCount = 2,
                    borrowedRecordedValueCents = 0,
                    borrowedPurchaseCount = 0,
                    unknownPersonalCostCount = 0,
                    unknownBorrowedCostCount = 0,
                    estimatedDateCount = 1,
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
            purchaseRowCount = products.size,
            eventRowCount = 4,
        ),
        generatedAtEpochMillis = 1_755_129_600_000,
        serverDurationMs = 1,
    )

    private fun spendBucket(
        personalSpendCents: Long,
        personalPurchaseCount: Int,
    ) = SpendBucketDto(
        personalSpendCents = personalSpendCents,
        personalPurchaseCount = personalPurchaseCount,
        borrowedRecordedValueCents = 0,
        borrowedPurchaseCount = 0,
        unknownPersonalCostCount = 0,
        unknownBorrowedCostCount = 0,
        estimatedDateCount = 1,
        unknownDateCount = 0,
    )
}
