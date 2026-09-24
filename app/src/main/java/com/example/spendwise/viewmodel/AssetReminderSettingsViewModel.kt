package com.example.spendwise.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spendwise.data.AssetReminderSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AssetReminderSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AssetReminderSettings(application)
    val enabled = settings.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setEnabled(enabled: Boolean) = viewModelScope.launch { settings.setEnabled(enabled) }
}
