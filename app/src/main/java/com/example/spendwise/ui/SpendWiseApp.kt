package com.example.spendwise.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.FeatureFlags
import com.example.spendwise.navigation.MainDestination
import com.example.spendwise.navigation.isMainDestinationSelected
import com.example.spendwise.screens.AnalyticsScreen
import com.example.spendwise.screens.AddExpenseScreen
import com.example.spendwise.screens.AddIncomeScreen
import com.example.spendwise.screens.AddCommitmentScreen
import com.example.spendwise.screens.CategoriesScreen
import com.example.spendwise.screens.HomeScreen
import com.example.spendwise.screens.CommitmentsScreen
import com.example.spendwise.screens.ReceiptCaptureScreen
import com.example.spendwise.screens.ReceiptReviewScreen
import com.example.spendwise.screens.SettingsScreen
import com.example.spendwise.screens.MoreScreen
import com.example.spendwise.screens.TransactionsScreen
import com.example.spendwise.viewmodel.ReceiptViewModel
import kotlinx.coroutines.launch

@Composable
fun SpendWiseApp() {
    val navController = rememberNavController()
    val receiptViewModel: ReceiptViewModel? = if (FeatureFlags.ENABLE_RECEIPT_SCAN) viewModel() else null
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val addExpenseRoute = "add_expense"
    val addIncomeRoute = "add_income"
    val addCommitmentRoute = "add_commitment"
    val editCommitmentRoute = "edit_commitment/{commitmentId}"
    val receiptCaptureRoute = "receipt_capture"
    val receiptReviewRoute = "receipt_review"
    val categoriesRoute = "categories"
    val settingsRoute = "settings"
    val fullScreenRoutes = setOf(addExpenseRoute, addIncomeRoute, addCommitmentRoute, editCommitmentRoute, receiptCaptureRoute, receiptReviewRoute)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (currentRoute !in fullScreenRoutes) {
                NavigationBar {
                    MainDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = isMainDestinationSelected(destination, currentRoute),
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Text(destination.symbol) },
                            label = { Text(destination.title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = MainDestination.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(MainDestination.Home.route) {
                HomeScreen(
                    onAddExpense = { navController.navigate(addExpenseRoute) },
                    onAddIncome = { navController.navigate(addIncomeRoute) },
                    onCommitments = { navController.navigate(MainDestination.Commitments.route) },
                    onSettings = { navController.navigate(settingsRoute) { launchSingleTop = true } }
                )
            }
            composable(MainDestination.Transactions.route) {
                TransactionsScreen(onAddExpense = { navController.navigate(addExpenseRoute) })
            }
            composable(MainDestination.Analytics.route) { AnalyticsScreen() }
            composable(MainDestination.Commitments.route) {
                CommitmentsScreen(
                    onAddCommitment = { navController.navigate(addCommitmentRoute) },
                    onEditCommitment = { navController.navigate("edit_commitment/$it") }
                )
            }
            composable(MainDestination.More.route) {
                MoreScreen(onCategories = { navController.navigate(categoriesRoute) })
            }
            composable(categoriesRoute) { CategoriesScreen() }
            composable(settingsRoute) { SettingsScreen(onBack = { navController.popBackStack() }) }
            composable(addExpenseRoute) {
                AddExpenseScreen(onSaved = { navController.popBackStack() })
            }
            composable(addIncomeRoute) {
                AddIncomeScreen(onSaved = { navController.popBackStack() })
            }
            composable(addCommitmentRoute) {
                AddCommitmentScreen(onSaved = { navController.popBackStack() })
            }
            composable(
                editCommitmentRoute,
                arguments = listOf(navArgument("commitmentId") { type = NavType.LongType })
            ) { entry ->
                AddCommitmentScreen(
                    commitmentId = entry.arguments?.getLong("commitmentId"),
                    onSaved = { navController.popBackStack() }
                )
            }
            if (FeatureFlags.ENABLE_RECEIPT_SCAN) {
                composable(receiptCaptureRoute) {
                    ReceiptCaptureScreen(
                        viewModel = checkNotNull(receiptViewModel),
                        onImageReady = { navController.navigate(receiptReviewRoute) }
                    )
                }
                composable(receiptReviewRoute) {
                    ReceiptReviewScreen(
                        viewModel = checkNotNull(receiptViewModel),
                        onSaved = {
                            navController.navigate(MainDestination.Home.route) {
                                popUpTo(MainDestination.Home.route)
                                launchSingleTop = true
                            }
                            scope.launch { snackbarHostState.showSnackbar("Receipt saved successfully") }
                        }
                    )
                }
            }
        }
    }
}
