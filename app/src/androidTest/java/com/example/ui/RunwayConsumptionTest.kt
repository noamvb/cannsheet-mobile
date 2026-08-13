package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.example.data.Product
import com.example.domain.PenQuickLogState
import com.example.domain.ProductRunway
import com.example.domain.RunwayBasis
import com.example.domain.RunwayConfidence
import org.junit.Rule
import org.junit.Test

class RunwayConsumptionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectedProductShowsOnlyAnExactProductIdRunway() {
        val selected = Product(
            id = "selected-id",
            name = "Same name",
            type = "P",
            status = 0,
            productUuid = "same-uuid",
            totalUses = 8.0,
        )
        val wrongIdentity = Product(
            id = "other-id",
            name = selected.name,
            type = selected.type,
            status = selected.status,
            productUuid = selected.productUuid,
            totalUses = selected.totalUses,
        )
        var runwayByProductId by mutableStateOf(
            mapOf(wrongIdentity.id to runway(wrongIdentity.id)),
        )

        composeRule.setContent {
            MaterialTheme {
                ConsumptionContent(
                    allProducts = listOf(selected, wrongIdentity),
                    recentProducts = emptyList(),
                    quantityPresets = listOf(1.0),
                    includeUnopened = false,
                    formState = ConsumptionFormState(selectedProductId = selected.id),
                    onSelectProduct = {},
                    onQuantityChange = {},
                    onIncludeUnopenedChange = {},
                    onLog = { _, _, _, _, _ -> },
                    onLogBorrowed = { _, _, _, _, _ -> },
                    onFinishWithoutConsumption = {},
                    runwayByProductId = runwayByProductId,
                )
            }
        }

        composeRule.onAllNodesWithTag(ConsumptionRunwayTestTags.SELECTED_PRODUCT_RUNWAY)
            .assertCountEquals(0)

        composeRule.runOnIdle {
            runwayByProductId = mapOf(selected.id to runway(selected.id))
        }

        composeRule.onNodeWithTag(ConsumptionRunwayTestTags.SELECTED_PRODUCT_RUNWAY)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNode(
            hasText("5 finished Pen products", substring = true),
        ).assertIsDisplayed()
    }

    @Test
    fun loadedPenShowsItsExactIdRunwayAndNamesEvidence() {
        val loaded = Product(
            id = "loaded-pen",
            name = "Loaded cart",
            type = "P",
            status = 0,
            productUuid = "shared-uuid",
        )
        val estimate = runway(loaded.id)

        composeRule.setContent {
            MaterialTheme {
                PenQuickLogCard(
                    state = PenQuickLogState.Loaded(
                        product = loaded,
                        presetUses = listOf(1.0),
                        secondsPerUse = 10.0,
                        syncedUses = 8.0,
                        pendingUses = 0.0,
                    ),
                    onQuickLogPen = {},
                    onChooseCart = {},
                    runwayByProductId = mapOf(loaded.id to estimate),
                )
            }
        }

        composeRule.onNodeWithTag(PenQuickLogTestTags.RUNWAY)
            .assertIsDisplayed()
            .assert(
                hasText("5 finished Pen products", substring = true),
            )
    }

    @Test
    fun loadedPenDoesNotFallBackToNameOrUuid() {
        val loaded = Product(
            id = "loaded-pen",
            name = "Same cart",
            type = "P",
            status = 0,
            productUuid = "same-uuid",
        )
        val otherId = "different-id"

        composeRule.setContent {
            MaterialTheme {
                PenQuickLogCard(
                    state = PenQuickLogState.Loaded(
                        product = loaded,
                        presetUses = listOf(1.0),
                        secondsPerUse = 10.0,
                        syncedUses = 8.0,
                        pendingUses = 0.0,
                    ),
                    onQuickLogPen = {},
                    onChooseCart = {},
                    runwayByProductId = mapOf(otherId to runway(otherId)),
                )
            }
        }

        composeRule.onAllNodesWithTag(PenQuickLogTestTags.RUNWAY).assertCountEquals(0)
    }

    @Test
    fun consumptionContentForwardsRunwayToItsEmbeddedPenCard() {
        val loaded = Product(
            id = "embedded-pen",
            name = "Embedded cart",
            type = "P",
            status = 0,
        )

        composeRule.setContent {
            MaterialTheme {
                ConsumptionContent(
                    allProducts = listOf(loaded),
                    recentProducts = emptyList(),
                    quantityPresets = listOf(1.0),
                    includeUnopened = false,
                    formState = ConsumptionFormState(),
                    onSelectProduct = {},
                    onQuantityChange = {},
                    onIncludeUnopenedChange = {},
                    onLog = { _, _, _, _, _ -> },
                    onLogBorrowed = { _, _, _, _, _ -> },
                    onFinishWithoutConsumption = {},
                    penQuickLog = PenQuickLogState.Loaded(
                        product = loaded,
                        presetUses = listOf(1.0),
                        secondsPerUse = 10.0,
                        syncedUses = 8.0,
                        pendingUses = 0.0,
                    ),
                    runwayByProductId = mapOf(loaded.id to runway(loaded.id)),
                )
            }
        }

        composeRule.onNodeWithTag(PenQuickLogTestTags.RUNWAY)
            .assertIsDisplayed()
            .assert(hasText("5 finished Pen products", substring = true))
    }

    private fun runway(productId: String) = ProductRunway(
        productId = productId,
        type = "P",
        usesSoFar = 8.0,
        estimatedTypicalFinishedUses = 20.0,
        estimatedRemainingToTypicalUses = 12.0,
        usesPerDay = 1.0,
        effectiveBurnRateDays = 14,
        estimatedDaysRemaining = 12.0,
        basis = RunwayBasis.PER_PRODUCT,
        confidence = RunwayConfidence.MEDIUM,
        sampleSize = 5,
    )
}
