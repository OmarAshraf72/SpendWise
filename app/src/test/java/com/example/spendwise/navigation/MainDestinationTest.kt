package com.example.spendwise.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainDestinationTest {
    @Test fun catalogDrivesConfigurableSurfaces() {
        assertEquals(
            listOf("HOME", "TRANSACTIONS", "COMMITMENTS", "ANALYTICS", "ASSETS", "MORE", "CATEGORIES"),
            NavigationConfiguration.DEFAULT.orderedIds
        )
        assertEquals(5, NavigationConfiguration.DEFAULT.pinnedDestinations.size)
        assertEquals(MainDestination.catalog.filter { it.canPin }, NavigationConfiguration.DEFAULT.orderedDestinations)
        assertEquals(listOf(MainDestination.Categories), NavigationConfiguration.DEFAULT.secondaryDestinations)
        assertFalse(MainDestination.Settings.canPin)
    }

    @Test fun visualOrderDoesNotChangeRouteIdentity() {
        val reordered = NavigationConfiguration.DEFAULT.move("ASSETS", 1)
        assertEquals(listOf("HOME", "ASSETS", "TRANSACTIONS", "COMMITMENTS", "ANALYTICS", "MORE", "CATEGORIES"), reordered.orderedIds)
        assertEquals("assets", reordered.orderedDestinations[1].route)
        assertEquals("transactions", reordered.orderedDestinations[2].route)
        assertTrue(isMainDestinationSelected(MainDestination.Assets, "assets"))
        assertFalse(MainDestination.configurable.any { isMainDestinationSelected(it, "settings") })
    }

    @Test fun pinningAndUnpinningKeepAssetsReachable() {
        val default = NavigationConfiguration.DEFAULT
        assertTrue(MainDestination.Assets in default.pinnedDestinations)
        val hidden = default.withPinned("ASSETS", false)
        assertTrue(MainDestination.Assets in hidden.unpinnedDestinations)
        assertTrue(MainDestination.Assets in hidden.secondaryDestinations)
        assertEquals(4, hidden.pinnedDestinations.size)
        assertTrue(MainDestination.Assets in hidden.withPinned("ASSETS", true).pinnedDestinations)
        val pinnedCategories = hidden.withPinned("CATEGORIES", true)
        assertTrue(MainDestination.Categories in pinnedCategories.pinnedDestinations)
        assertFalse(MainDestination.Categories in pinnedCategories.secondaryDestinations)
        assertEquals("categories", MainDestination.Categories.route)
    }

    @Test fun visibleCountAndUnknownIdsAreValidated() {
        val three = NavigationConfiguration.DEFAULT.withPinned("ASSETS", false).withPinned("ANALYTICS", false)
        assertEquals(3, three.pinnedDestinations.size)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { three.withPinned("HOME", false) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { three.withPinned("SETTINGS", true) }
        assertEquals(5, NavigationConfiguration.DEFAULT.pinnedDestinations.size)
        assertEquals(5, three.withPinned("MORE", true).withPinned("CATEGORIES", true).pinnedDestinations.size)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { NavigationConfiguration.DEFAULT.withPinned("MORE", true) }
    }

    @Test fun malformedPreferencesRecoverSafeDefault() {
        val restored = NavigationConfiguration.restored(listOf("ASSETS", "ASSETS", "UNKNOWN"), setOf("SETTINGS"))
        assertEquals(7, restored.orderedDestinations.size)
        assertEquals("ASSETS", restored.orderedIds.first())
        assertEquals(5, restored.pinnedDestinations.size)
    }

    @Test fun directBarReorderPreservesUnpinnedPositions() {
        val original = NavigationConfiguration.DEFAULT.withPinned("TRANSACTIONS", false)
            .withPinned("MORE", true)
        val reordered = original.reorderPinned(listOf("MORE", "HOME", "ASSETS", "COMMITMENTS", "ANALYTICS"))
        assertEquals(listOf("MORE", "TRANSACTIONS", "HOME", "ASSETS", "COMMITMENTS", "ANALYTICS", "CATEGORIES"), reordered.orderedIds)
        assertEquals(original.unpinnedDestinations, reordered.unpinnedDestinations)
        assertEquals("more", reordered.pinnedDestinations.first().route)
    }
}
