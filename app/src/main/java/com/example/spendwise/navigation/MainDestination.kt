package com.example.spendwise.navigation

enum class MainDestination(val route: String, val title: String, val symbol: String) {
    Home("home", "Home", "H"),
    Transactions("transactions", "Transactions", "T"),
    Commitments("commitments", "Commitments", "M"),
    Analytics("analytics", "Analytics", "A"),
    More("more", "More", "•••")
}

fun isMainDestinationSelected(destination: MainDestination, currentRoute: String?): Boolean =
    currentRoute == destination.route ||
        (destination == MainDestination.More && (currentRoute == "categories" || currentRoute == "assets"))
