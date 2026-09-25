package com.example.spendwise.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.spendwise.FeatureFlags
import com.example.spendwise.navigation.MainDestination
import com.example.spendwise.navigation.NavigationViewModel
import com.example.spendwise.screens.AnalyticsScreen
import com.example.spendwise.screens.AddExpenseScreen
import com.example.spendwise.screens.AddIncomeScreen
import com.example.spendwise.screens.AddCommitmentScreen
import com.example.spendwise.screens.CategoriesScreen
import com.example.spendwise.screens.ManageCategoriesScreen
import com.example.spendwise.screens.CategoryDetailScreen
import com.example.spendwise.screens.TransactionDetailScreen
import com.example.spendwise.screens.HomeScreen
import com.example.spendwise.screens.CommitmentsScreen
import com.example.spendwise.screens.ReceiptCaptureScreen
import com.example.spendwise.screens.ReceiptReviewScreen
import com.example.spendwise.screens.SettingsScreen
import com.example.spendwise.screens.MoreScreen
import com.example.spendwise.screens.NavigationCustomizationScreen
import com.example.spendwise.screens.DebtDetailScreen
import com.example.spendwise.screens.TransactionsScreen
import com.example.spendwise.screens.MyAssetsScreen
import com.example.spendwise.screens.AddAssetScreen
import com.example.spendwise.screens.AssetDetailScreen
import com.example.spendwise.screens.AssetMaintenanceScreen
import com.example.spendwise.viewmodel.ReceiptViewModel
import kotlinx.coroutines.launch

@Composable
fun SpendWiseApp(notificationAssetRequest: Pair<Long, Int>? = null) {
    val navController = rememberNavController()
    LaunchedEffect(notificationAssetRequest) {
        notificationAssetRequest?.let { (assetId, _) ->
            navController.navigate("asset/$assetId") { launchSingleTop = true }
        }
    }
    val navigationViewModel: NavigationViewModel = viewModel()
    val navigationConfiguration by navigationViewModel.configuration.collectAsStateWithLifecycle()
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
    val categoriesRoute = MainDestination.Categories.route
    val settingsRoute = MainDestination.Settings.route
    val customizeRoute = "customize_navigation"
    val debtDetailRoute = "debt_detail/{debtProfileId}"
    val assetsRoute = MainDestination.Assets.route
    val addAssetRoute = "add_asset"
    val addAssetFromTransactionRoute = "add_asset/from_transaction/{transactionId}"
    val categoryDetailRoute = "category/{categoryId}?period={period}"
    val manageCategoriesRoute = "manage_categories"
    val transactionDetailRoute = "transaction/{transactionId}"
    val assetDetailRoute = "asset/{assetId}"
    val assetMaintenanceRoute = "asset/{assetId}/maintenance?complete={complete}&ruleId={ruleId}"
    val editAssetRoute = "edit_asset/{assetId}"
    val fullScreenRoutes = setOf(addExpenseRoute, addIncomeRoute, addCommitmentRoute, editCommitmentRoute, debtDetailRoute, addAssetRoute, addAssetFromTransactionRoute, editAssetRoute, assetDetailRoute, assetMaintenanceRoute, receiptCaptureRoute, receiptReviewRoute, settingsRoute, customizeRoute, manageCategoriesRoute, transactionDetailRoute)
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (currentRoute in MainDestination.configurable.map(MainDestination::route)) {
                Row(Modifier.fillMaxWidth().statusBarsPadding(), horizontalArrangement = Arrangement.End) {
                    if (currentRoute != MainDestination.More.route) IconButton(onClick = {
                        navController.navigate(MainDestination.More.route) { launchSingleTop = true }
                    }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                    IconButton(onClick = { navController.navigate(settingsRoute) { launchSingleTop = true } }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            }
        },
        bottomBar = {
            if (currentRoute !in fullScreenRoutes && !imeVisible) {
                FloatingNavigationBar(
                    destinations = navigationConfiguration.pinnedDestinations,
                    currentRoute = currentRoute,
                    onDestination = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                            launchSingleTop = true
                            restoreState = false
                        }
                    },
                    onReorderPinned = navigationViewModel::savePinnedOrder
                )
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
                    onAssets = { navController.navigate(assetsRoute) }
                )
            }
            composable(MainDestination.Transactions.route) {
                TransactionsScreen(onAddExpense = { navController.navigate(addExpenseRoute) },
                    onAddToItems = { navController.navigate("add_asset/from_transaction/$it") })
            }
            composable(MainDestination.Analytics.route) { AnalyticsScreen() }
            composable(MainDestination.Commitments.route) {
                CommitmentsScreen(
                    onAddCommitment = { navController.navigate(addCommitmentRoute) },
                    onEditCommitment = { navController.navigate("edit_commitment/$it") },
                    onDebtDetail = { navController.navigate("debt_detail/$it") }
                )
            }
            composable(MainDestination.More.route) {
                MoreScreen(
                    secondaryDestinations = navigationConfiguration.secondaryDestinations,
                    onOpen = { navController.navigate(it.route) { launchSingleTop = true } },
                    onCustomize = { navController.navigate(customizeRoute) },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(assetsRoute) { MyAssetsScreen(onAdd = { navController.navigate(addAssetRoute) }, onOpen = { navController.navigate("asset/$it") }) }
            composable(addAssetRoute) { AddAssetScreen(onSaved = { id -> navController.navigate("asset/$id") { popUpTo(addAssetRoute) { inclusive = true } } }, onCancel = { navController.popBackStack() }) }
            composable(addAssetFromTransactionRoute, arguments = listOf(navArgument("transactionId") { type = NavType.LongType })) { entry ->
                AddAssetScreen(transactionId = entry.arguments?.getLong("transactionId"),
                    onSaved = { id -> navController.navigate("asset/$id") { popUpTo(addAssetFromTransactionRoute) { inclusive = true } } },
                    onCancel = { navController.popBackStack() })
            }
            composable(editAssetRoute, arguments = listOf(navArgument("assetId") { type = NavType.LongType })) { entry ->
                AddAssetScreen(assetId = entry.arguments?.getLong("assetId"), onSaved = { navController.popBackStack() }, onCancel = { navController.popBackStack() })
            }
            composable(assetDetailRoute, arguments = listOf(navArgument("assetId") { type = NavType.LongType })) {
                AssetDetailScreen(onBack = { navController.popBackStack() }, onEdit = { navController.navigate("edit_asset/$it") }, onCommitment = { navController.navigate(MainDestination.Commitments.route) },
                    onMaintenance = { id, complete, ruleId -> navController.navigate("asset/$id/maintenance?complete=$complete&ruleId=${ruleId ?: 0}") },
                    onTransaction = { navController.navigate("transaction/$it") })
            }
            composable(assetMaintenanceRoute, arguments = listOf(
                navArgument("assetId") { type = NavType.LongType }, navArgument("complete") { type = NavType.BoolType; defaultValue = false },
                navArgument("ruleId") { type = NavType.LongType; defaultValue = 0L }
            )) { entry ->
                AssetMaintenanceScreen(onBack = { navController.popBackStack() }, openCompletion = entry.arguments?.getBoolean("complete") == true,
                    initialRuleId = entry.arguments?.getLong("ruleId")?.takeIf { it > 0 })
            }
            composable(categoriesRoute) { CategoriesScreen(onOpen = { id, period -> navController.navigate("category/$id?period=${period.name}") },
                onManage = { navController.navigate(manageCategoriesRoute) }) }
            composable(categoryDetailRoute, arguments = listOf(
                navArgument("categoryId") { type = NavType.LongType },
                navArgument("period") { type = NavType.StringType; nullable = true }
            )) {
                CategoryDetailScreen(onBack = { navController.popBackStack() }, onItem = { navController.navigate("asset/$it") },
                    onTransaction = { navController.navigate("transaction/$it") })
            }
            composable(manageCategoriesRoute) { ManageCategoriesScreen(onBack = { navController.popBackStack() }) }
            composable(transactionDetailRoute, arguments = listOf(navArgument("transactionId") { type = NavType.LongType })) {
                TransactionDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(settingsRoute) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigation = { navController.navigate(customizeRoute) },
                    onMore = { navController.navigate(MainDestination.More.route) }
                )
            }
            composable(customizeRoute) {
                NavigationCustomizationScreen(
                    configuration = navigationConfiguration,
                    onBack = { navController.popBackStack() },
                    onSaveOrder = { navigationViewModel.saveOrder(it) },
                    onSetPinned = { id, pinned -> navigationViewModel.setPinned(id, pinned) },
                    onReset = { navigationViewModel.reset() }
                )
            }
            composable(
                debtDetailRoute,
                arguments = listOf(navArgument("debtProfileId") { type = NavType.LongType })
            ) { DebtDetailScreen(onBack = { navController.popBackStack() }) }
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
