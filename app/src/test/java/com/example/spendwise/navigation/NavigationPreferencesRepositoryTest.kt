package com.example.spendwise.navigation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NavigationPreferencesRepositoryTest {
    @Test fun orderPinAndResetUseStablePreferencesAcrossRepositoryRecreation() = runBlocking {
        val store = MemoryPreferencesStore()
        val first = NavigationPreferencesRepository(store)
        assertEquals(NavigationConfiguration.DEFAULT, first.configuration.first())
        val order = listOf("HOME", "ASSETS", "COMMITMENTS", "TRANSACTIONS", "ANALYTICS", "MORE", "CATEGORIES")
        first.saveOrder(order)
        first.setPinned("ANALYTICS", false)
        assertEquals(order, first.configuration.first().orderedIds)
        assertEquals(4, first.configuration.first().pinnedDestinations.size)
        val recreated = NavigationPreferencesRepository(store)
        assertEquals(order, recreated.configuration.first().orderedIds)
        assertEquals(4, recreated.configuration.first().pinnedDestinations.size)
        recreated.savePinnedOrder(listOf("ASSETS", "HOME", "COMMITMENTS", "TRANSACTIONS"))
        assertEquals(listOf("ASSETS", "HOME", "COMMITMENTS", "TRANSACTIONS"), recreated.configuration.first().pinnedDestinations.map { it.stableId })
        val afterReload = NavigationPreferencesRepository(store)
        assertEquals(listOf("ASSETS", "HOME", "COMMITMENTS", "TRANSACTIONS"), afterReload.configuration.first().pinnedDestinations.map { it.stableId })
        recreated.reset()
        assertEquals(NavigationConfiguration.DEFAULT, recreated.configuration.first())
    }

    @Test fun invalidOrderIsNeverStored() = runBlocking {
        val repository = NavigationPreferencesRepository(MemoryPreferencesStore())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.saveOrder(listOf("HOME", "HOME")) }
        }
        assertEquals(NavigationConfiguration.DEFAULT, repository.configuration.first())
    }

    @Test fun customizationReordersUnpinnedRowsWithoutChangingPinsAndBottomBarUsesSameOrder() = runBlocking {
        val store = MemoryPreferencesStore()
        val customization = NavigationPreferencesRepository(store)
        val pinsBefore = customization.configuration.first().pinnedIds
        val fullOrder = listOf("HOME", "CATEGORIES", "TRANSACTIONS", "COMMITMENTS", "ANALYTICS", "ASSETS", "MORE")
        customization.saveOrder(fullOrder)
        val bottomBar = NavigationPreferencesRepository(store)
        assertEquals(fullOrder, bottomBar.configuration.first().orderedIds)
        assertEquals(pinsBefore, bottomBar.configuration.first().pinnedIds)
        bottomBar.savePinnedOrder(listOf("ASSETS", "HOME", "TRANSACTIONS", "COMMITMENTS", "ANALYTICS"))
        val reloaded = NavigationPreferencesRepository(store).configuration.first()
        assertEquals(listOf("ASSETS", "CATEGORIES", "HOME", "TRANSACTIONS", "COMMITMENTS", "ANALYTICS", "MORE"), reloaded.orderedIds)
        assertEquals(pinsBefore, reloaded.pinnedIds)
        assertEquals("categories", reloaded.orderedDestinations[1].route)
    }

    private class MemoryPreferencesStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
