package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sizedContainersChooseBottomBarOrRailWithoutDuplicatingDestinations() {
        var width by mutableStateOf(400.dp)
        var selectedRoute by mutableStateOf(Screen.Consumption.route)

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MaterialTheme {
                    AdaptiveNavigationLayout(
                        pendingCount = 3,
                        selectedRoute = selectedRoute,
                        onNavigate = { selectedRoute = it },
                        modifier = Modifier.requiredSize(width, 1_000.dp),
                    ) { windowWidth ->
                        Box(
                            Modifier
                                .requiredSize(1.dp)
                                .testTag("adaptive-content-${windowWidth.name}"),
                        )
                    }
                }
            }
        }

        assertCompactNavigation()
        composeRule.onNodeWithTag(
            AdaptiveNavigationTestTags.destination(Screen.Consumption.route),
        ).assertIsSelected()
        items.forEach { screen ->
            composeRule.onNodeWithTag(AdaptiveNavigationTestTags.destination(screen.route)).assertExists()
        }

        composeRule.onNodeWithTag(
            AdaptiveNavigationTestTags.destination(Screen.Settings.route),
        ).performClick()
        composeRule.runOnIdle { assertEquals(Screen.Settings.route, selectedRoute) }

        composeRule.runOnIdle { width = 700.dp }
        assertWideNavigation()
        composeRule.onNodeWithTag(
            AdaptiveNavigationTestTags.destination(Screen.Settings.route),
        ).assertIsSelected()

        composeRule.runOnIdle { width = 900.dp }
        assertWideNavigation()
        composeRule.onNodeWithTag("adaptive-content-EXPANDED").assertExists()
        items.forEach { screen ->
            composeRule.onNodeWithTag(AdaptiveNavigationTestTags.destination(screen.route)).assertExists()
        }
    }

    @Test
    fun submissionCountdownIsBoundedOnAWideContainer() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(900.dp, 1_000.dp)) {
                        SubmissionCountdownCard(
                            countdown = 5,
                            onCancel = {},
                            onSubmitNow = {},
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }

        val width = composeRule.onNodeWithTag(AdaptiveNavigationTestTags.COUNTDOWN)
            .fetchSemanticsNode()
            .boundsInRoot
            .width
        assertTrue("Countdown width was $width instead of at most 480px", width <= 480f)
    }

    private fun assertCompactNavigation() {
        composeRule.onNodeWithTag(AdaptiveNavigationTestTags.BOTTOM_BAR).assertExists()
        composeRule.onAllNodesWithTag(AdaptiveNavigationTestTags.RAIL).assertCountEquals(0)
    }

    private fun assertWideNavigation() {
        composeRule.onAllNodesWithTag(AdaptiveNavigationTestTags.BOTTOM_BAR).assertCountEquals(0)
        composeRule.onNodeWithTag(AdaptiveNavigationTestTags.RAIL).assertExists()
    }
}
