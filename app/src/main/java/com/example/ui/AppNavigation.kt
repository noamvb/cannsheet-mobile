package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

sealed class Screen(val route: String, val title: String, val icon: @Composable () -> Unit) {
    object Consumption : Screen("consumption", "Log", { Icon(Icons.Filled.List, contentDescription = "Consumption") })
    object Purchase : Screen("purchase", "Purchase", { Icon(Icons.Filled.AddShoppingCart, contentDescription = "Purchase") })
    object Insights : Screen("insights", "Insights", { Icon(Icons.Filled.Insights, contentDescription = "Insights") })
    object Settings : Screen("settings", "Settings", { Icon(Icons.Filled.Settings, contentDescription = "Settings") })
}

val items = listOf(Screen.Consumption, Screen.Purchase, Screen.Insights, Screen.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CannsheetApp(viewModel: CannsheetViewModel = viewModel()) {
    val navController = rememberNavController()
    val pendingCount by viewModel.pendingActionCount.collectAsState()
    val countdown by viewModel.pendingCountdown.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            if (screen == Screen.Settings && pendingCount > 0) {
                                BadgedBox(badge = { Badge { Text(pendingCount.toString()) } }) {
                                    screen.icon()
                                }
                            } else {
                                screen.icon()
                            }
                        },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(navController, startDestination = Screen.Consumption.route) {
                composable(Screen.Consumption.route) { ConsumptionScreen(viewModel) }
                composable(Screen.Purchase.route) { PurchaseScreen(viewModel) }
                composable(Screen.Insights.route) { InsightsScreen(viewModel) }
                composable(Screen.Settings.route) { SettingsScreen(viewModel) }
            }

            if (countdown > 0) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Submitting in $countdown...", style = MaterialTheme.typography.bodyLarge)
                        Row {
                            TextButton(onClick = { viewModel.cancelPendingAction() }) {
                                Text("CANCEL")
                            }
                            TextButton(onClick = { viewModel.forceSubmitNow() }) {
                                Text("SUBMIT NOW")
                            }
                        }
                    }
                }
            }
        }
    }
}
