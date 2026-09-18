package com.example.spendwise.navigation

enum class MainDestination(val route: String, val title: String, val symbol: String) {
    Home("home", "Home", "H"),
    Transactions("transactions", "Transactions", "T"),
    Analytics("analytics", "Analytics", "A"),
    Categories("categories", "Categories", "C"),
    Settings("settings", "Settings", "S")
}
