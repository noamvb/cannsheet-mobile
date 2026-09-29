package com.example.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.InsightsRange
import com.example.domain.ProductRunway
import com.example.ui.screenshots.ScreenshotTestApplication
import com.example.ui.screenshots.screenshotInsights
import com.example.ui.theme.CannsheetTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.LEGACY)
@Config(
    application = ScreenshotTestApplication::class,
    sdk = [35],
    qualifiers = "w411dp-h891dp-xxhdpi",
)
class InsightsRedesignTest {
    @get:Rule val composeRule = createComposeRule()

    private fun showInsights() {
        composeRule.setContent {
            CannsheetTheme {
                Surface {
                    InsightsContent(
                        state = InsightsUiState(
                            data = screenshotInsights(),
                            displayedRange = InsightsRange.LastDays(30),
                        ),
                        pendingCount = 0,
                        isSyncing = false,
                        onSync = {},
                        onRefresh = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun rangeControlExposesAllChoicesAndSelectsDefaultRange() {
        showInsights()
        listOf("30d", "90d", "180d", "All", "Custom").forEach { label ->
            composeRule.onNodeWithTag("insights-range-$label").assertExists()
        }
        composeRule.onNodeWithTag("insights-range-30d").assertIsSelected()
    }

    @Test
    fun headlineFigureUsesTabularNumerals() {
        showInsights()
        val node = composeRule.onNodeWithTag("insights-headline-number").fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(text.contains("18"))
        composeRule.onNodeWithTag("insights-headline-number")
            .assert(SemanticsMatcher.expectValue(INSIGHTS_FONT_FEATURE_SETTINGS, "tnum"))
    }

    @Test
    fun chartDescriptionListsEveryFixtureValue() {
        showInsights()
        composeRule.onNodeWithTag("insights-chart-activity")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("activity: 09-27, 4; 09-28, 3"),
                ),
            )
    }

    @Test
    fun runwayRowShowsTaggedProductAndRightAlignedFigure() {
        val data = screenshotInsights()
        val product = data.products.single()
        composeRule.setContent {
            CannsheetTheme {
                Surface {
                    RunwaySection(
                        estimates = RunwayEstimateState.Ready(
                            runwayByProductId = mapOf(
                                product.productId to ProductRunway(
                                    productId = product.productId,
                                    type = product.type,
                                    usesSoFar = 5.0,
                                    basis = com.example.domain.RunwayBasis.PER_PRODUCT,
                                    sampleSize = 3,
                                    targetGrams = null,
                                    estimatedRemainingToTypicalUses = 5.0,
                                    estimatedTypicalFinishedUses = 10.0,
                                    confidence = com.example.domain.RunwayConfidence.MEDIUM,
                                    pace = com.example.domain.RunwayPace.NoUseInRange,
                                ),
                            ),
                            evidenceByType = emptyMap(),
                            spendRunRate = null,
                            diagnostics = emptyList(),
                        ),
                        products = data.products,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(InsightsRunwayTestTags.row(product.productId)).assertExists()
        val figure = composeRule.onNodeWithTag("${InsightsRunwayTestTags.row(product.productId)}-figure")
        figure.assertTextContains("5 uses")
        val row = figure
            .fetchSemanticsNode().boundsInRoot
        val list = composeRule.onNodeWithTag(InsightsRunwayTestTags.SECTION)
            .fetchSemanticsNode().boundsInRoot
        with(composeRule.density) {
            assertTrue("Right edge delta was ${list.right - row.right}px", list.right - row.right <= 24.dp.toPx())
        }
    }

}
