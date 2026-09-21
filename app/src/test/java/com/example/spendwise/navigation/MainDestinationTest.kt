package com.example.spendwise.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MainDestinationTest {
    @Test fun bottomNavigationContainsOnlyPrimaryDestinationsInProductOrder() {
        assertEquals(
            listOf("Home", "Transactions", "Commitments", "Analytics", "More"),
            MainDestination.entries.map { it.title }
        )
        assertFalse(MainDestination.entries.any { it.title == "Settings" || it.title == "Categories" })
    }

    @Test fun categoriesSelectsMoreWhileSettingsSelectsNoBottomDestination() {
        assertEquals(MainDestination.More, MainDestination.entries.single { isMainDestinationSelected(it, "categories") })
        assertFalse(MainDestination.entries.any { isMainDestinationSelected(it, "settings") })
    }
}
