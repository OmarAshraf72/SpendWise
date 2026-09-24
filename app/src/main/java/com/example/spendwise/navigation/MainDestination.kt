package com.example.spendwise.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class SecondaryVisibility { WHEN_UNPINNED, GLOBAL_ACTION }

interface NavigationDestinationDefinition {
    val stableId: String
    val route: String
    val title: String
    val icon: ImageVector
    val canPin: Boolean
    val defaultPinned: Boolean
    val defaultOrder: Int
    val secondaryVisibility: SecondaryVisibility
}

/** One catalog for the bottom bar, navigation settings, and secondary navigation. */
enum class MainDestination(
    override val stableId: String,
    override val route: String,
    override val title: String,
    override val canPin: Boolean,
    override val defaultPinned: Boolean,
    override val defaultOrder: Int,
    override val secondaryVisibility: SecondaryVisibility
) : NavigationDestinationDefinition {
    Home("HOME", "home", "Home", true, true, 0, SecondaryVisibility.WHEN_UNPINNED),
    Transactions("TRANSACTIONS", "transactions", "Transactions", true, true, 1, SecondaryVisibility.WHEN_UNPINNED),
    Commitments("COMMITMENTS", "commitments", "Commitments", true, true, 2, SecondaryVisibility.WHEN_UNPINNED),
    Analytics("ANALYTICS", "analytics", "Analytics", true, true, 3, SecondaryVisibility.WHEN_UNPINNED),
    Assets("ASSETS", "assets", "My Assets", true, true, 4, SecondaryVisibility.WHEN_UNPINNED),
    More("MORE", "more", "More", true, false, 5, SecondaryVisibility.GLOBAL_ACTION),
    Categories("CATEGORIES", "categories", "Categories", true, false, 6, SecondaryVisibility.WHEN_UNPINNED),
    Settings("SETTINGS", "settings", "Settings", false, false, 7, SecondaryVisibility.GLOBAL_ACTION);

    override val icon: ImageVector
        get() = when (this) {
            Home -> Icons.Outlined.Home
            Transactions -> Icons.AutoMirrored.Outlined.ReceiptLong
            Commitments -> Icons.Outlined.AccountBalanceWallet
            Analytics -> Icons.Outlined.Analytics
            Assets -> Icons.Outlined.Inventory2
            More -> Icons.Outlined.MoreHoriz
            Categories -> Icons.Outlined.Category
            Settings -> Icons.Outlined.Settings
        }

    companion object {
        val catalog: List<MainDestination> = entries.sortedBy(MainDestination::defaultOrder)
        val configurable: List<MainDestination> = catalog.filter(MainDestination::canPin)
        fun fromStableId(id: String): MainDestination? = catalog.firstOrNull { it.stableId == id }
    }
}

fun isMainDestinationSelected(destination: MainDestination, currentRoute: String?): Boolean =
    destination.canPin && currentRoute == destination.route
