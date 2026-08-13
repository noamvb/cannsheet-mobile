package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow

sealed class Screen(val route: String, val title: String, val icon: @Composable () -> Unit) {
    object Consumption : Screen("consumption", "Log", { Icon(Icons.Filled.List, contentDescription = "Consumption") })
    object Purchase : Screen("purchase", "Purchase", { Icon(Icons.Filled.AddShoppingCart, contentDescription = "Purchase") })
    object Insights : Screen("insights", "Insights", { Icon(Icons.Filled.Insights, contentDescription = "Insights") })
    object Settings : Screen("settings", "Settings", { Icon(Icons.Filled.Settings, contentDescription = "Settings") })
}

val items = listOf(Screen.Consumption, Screen.Purchase, Screen.Insights, Screen.Settings)

internal object AdaptiveNavigationTestTags {
    const val BOTTOM_BAR = "adaptive-navigation-bottom-bar"
    const val RAIL = "adaptive-navigation-rail"
    const val CONTENT = "adaptive-navigation-content"
    const val COUNTDOWN = "adaptive-navigation-countdown"

    fun destination(route: String) = "adaptive-navigation-destination-$route"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CannsheetApp(
    viewModel: CannsheetViewModel = viewModel(),
    startDestination: String = Screen.Consumption.route,
    routeRequests: Flow<String> = emptyFlow(),
) {
    val navController = rememberNavController()
    val pendingCount by viewModel.pendingActionCount.collectAsState()
    val countdown by viewModel.pendingCountdown.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val selectedRoute = navBackStackEntry?.destination?.route

    val navigateTo: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(navController, routeRequests) {
        routeRequests.collect { route ->
            if (items.any { it.route == route }) {
                navigateTo(route)
            }
        }
    }

    AdaptiveNavigationLayout(
        pendingCount = pendingCount,
        selectedRoute = selectedRoute,
        onNavigate = navigateTo,
    ) { windowWidth ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(navController, startDestination = startDestination) {
                composable(Screen.Consumption.route) { ConsumptionScreen(viewModel) }
                composable(Screen.Purchase.route) { PurchaseScreen(viewModel) }
                composable(Screen.Insights.route) {
                    InsightsScreen(viewModel, windowWidth = windowWidth)
                }
                composable(Screen.Settings.route) { SettingsScreen(viewModel) }
            }

            if (countdown > 0) {
                SubmissionCountdownCard(
                    countdown = countdown,
                    onCancel = viewModel::cancelPendingAction,
                    onSubmitNow = viewModel::forceSubmitNow,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
    }
}

/**
 * Stateless navigation chrome. Keeping the app graph and ViewModel outside
 * this seam lets adaptive-layout tests exercise real width constraints without
 * constructing production repositories or starting network work.
 */
@Composable
internal fun AdaptiveNavigationLayout(
    pendingCount: Int,
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (WindowWidth) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val windowWidth = windowWidthFor(maxWidth)
        Scaffold(
            bottomBar = {
                if (windowWidth == WindowWidth.COMPACT) {
                    AppBottomNavigation(
                        pendingCount = pendingCount,
                        selectedRoute = selectedRoute,
                        onNavigate = onNavigate,
                    )
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (windowWidth != WindowWidth.COMPACT) {
                    AppNavigationRail(
                        pendingCount = pendingCount,
                        selectedRoute = selectedRoute,
                        onNavigate = onNavigate,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag(AdaptiveNavigationTestTags.CONTENT),
                ) {
                    content(windowWidth)
                }
            }
        }
    }
}

@Composable
private fun AppBottomNavigation(
    pendingCount: Int,
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
) {
    NavigationBar(modifier = Modifier.testTag(AdaptiveNavigationTestTags.BOTTOM_BAR)) {
        items.forEach { screen ->
            NavigationBarItem(
                modifier = Modifier.testTag(AdaptiveNavigationTestTags.destination(screen.route)),
                icon = { AppNavigationIcon(screen, pendingCount) },
                label = { Text(screen.title) },
                selected = selectedRoute == screen.route,
                onClick = { onNavigate(screen.route) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(
    pendingCount: Int,
    selectedRoute: String?,
    onNavigate: (String) -> Unit,
) {
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .displayCutoutPadding()
            .navigationBarsPadding()
            .testTag(AdaptiveNavigationTestTags.RAIL),
    ) {
        items.forEach { screen ->
            NavigationRailItem(
                modifier = Modifier.testTag(AdaptiveNavigationTestTags.destination(screen.route)),
                icon = { AppNavigationIcon(screen, pendingCount) },
                label = { Text(screen.title) },
                selected = selectedRoute == screen.route,
                onClick = { onNavigate(screen.route) },
            )
        }
    }
}

@Composable
private fun AppNavigationIcon(screen: Screen, pendingCount: Int) {
    if (screen == Screen.Settings && pendingCount > 0) {
        BadgedBox(badge = { Badge { Text(pendingCount.toString()) } }) {
            screen.icon()
        }
    } else {
        screen.icon()
    }
}

@Composable
internal fun SubmissionCountdownCard(
    countdown: Int,
    onCancel: () -> Unit,
    onSubmitNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .testTag(AdaptiveNavigationTestTags.COUNTDOWN),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Submitting in $countdown...", style = MaterialTheme.typography.bodyLarge)
            Row {
                TextButton(onClick = onCancel) {
                    Text("CANCEL")
                }
                TextButton(onClick = onSubmitNow) {
                    Text("SUBMIT NOW")
                }
            }
        }
    }
}
