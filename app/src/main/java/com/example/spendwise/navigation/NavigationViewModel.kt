package com.example.spendwise.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NavigationPreferencesRepository(application)
    val configuration = repository.configuration.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), NavigationConfiguration.DEFAULT
    )

    fun saveOrder(ids: List<String>) = viewModelScope.launch { repository.saveOrder(ids) }
    fun savePinnedOrder(ids: List<String>) = viewModelScope.launch { repository.savePinnedOrder(ids) }
    fun setPinned(id: String, pinned: Boolean) = viewModelScope.launch { repository.setPinned(id, pinned) }
    fun reset() = viewModelScope.launch { repository.reset() }
}
