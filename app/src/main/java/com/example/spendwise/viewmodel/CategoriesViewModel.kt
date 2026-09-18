package com.example.spendwise.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spendwise.data.CategoryRepository
import com.example.spendwise.data.SpendWiseDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoriesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CategoryRepository(SpendWiseDatabase.getInstance(application).categoryDao())

    val categories = repository.categories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun addCustom(name: String) {
        viewModelScope.launch { repository.addCustom(name.trim()) }
    }

    fun renameCustom(id: Long, name: String) {
        viewModelScope.launch { repository.renameCustom(id, name.trim()) }
    }

    fun deleteCustom(id: Long) {
        viewModelScope.launch { repository.deleteCustom(id) }
    }
}
