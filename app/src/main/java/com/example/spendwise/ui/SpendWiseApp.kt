package com.example.spendwise.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.spendwise.navigation.MainDestination
import com.example.spendwise.screens.AnalyticsScreen
import com.example.spendwise.screens.AddExpenseScreen
import com.example.spendwise.screens.AddIncomeScreen
import com.example.spendwise.screens.CategoriesScreen
import com.example.spendwise.screens.HomeScreen
import com.example.spendwise.screens.SettingsScreen
import com.example.spendwise.screens.TransactionsScreen

@Composable
fun SpendWiseApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val addExpenseRoute = "add_expense"
    val addIncomeRoute = "add_income"

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (currentRoute != addExpenseRoute && currentRoute != addIncomeRoute) {
                NavigationBar {
                    MainDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
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
                    onAddIncome = { navController.navigate(addIncomeRoute) }
                )
            }
            composable(MainDestination.Transactions.route) {
                TransactionsScreen(onAddExpense = { navController.navigate(addExpenseRoute) })
            }
            composable(MainDestination.Analytics.route) { AnalyticsScreen() }
            composable(MainDestination.Categories.route) { CategoriesScreen() }
            composable(MainDestination.Settings.route) { SettingsScreen() }
            composable(addExpenseRoute) {
                AddExpenseScreen(onSaved = { navController.popBackStack() })
            }
            composable(addIncomeRoute) {
                AddIncomeScreen(onSaved = { navController.popBackStack() })
            }
        }
    }
}
