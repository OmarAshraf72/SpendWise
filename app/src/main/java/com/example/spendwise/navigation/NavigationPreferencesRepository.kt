package com.example.spendwise.navigation

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.navigationDataStore by preferencesDataStore(name = "navigation_preferences")

class NavigationPreferencesRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.navigationDataStore)

    private val orderKey = stringPreferencesKey("primary_destination_order_v1")
    private val pinnedKey = stringSetPreferencesKey("pinned_primary_destinations_v1")

    val configuration: Flow<NavigationConfiguration> = store.data.map(::read)

    suspend fun saveOrder(orderedIds: List<String>) {
        require(orderedIds.size == MainDestination.configurable.size &&
            orderedIds.toSet() == NavigationConfiguration.DEFAULT.orderedIds.toSet())
        store.edit { it[orderKey] = orderedIds.joinToString(",") }
    }

    suspend fun savePinnedOrder(pinnedIdsInOrder: List<String>) {
        store.edit { preferences ->
            val updated = read(preferences).reorderPinned(pinnedIdsInOrder)
            preferences[orderKey] = updated.orderedIds.joinToString(",")
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        store.edit { preferences ->
            val current = read(preferences)
            val updated = current.withPinned(id, pinned)
            preferences[pinnedKey] = updated.pinnedIds
        }
    }

    suspend fun reset() {
        store.edit { preferences ->
            preferences.remove(orderKey)
            preferences.remove(pinnedKey)
        }
    }

    private fun read(preferences: Preferences): NavigationConfiguration = NavigationConfiguration.restored(
        preferences[orderKey]?.split(','), preferences[pinnedKey]
    )
}
