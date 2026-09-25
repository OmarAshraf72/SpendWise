package com.example.spendwise.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.spendwise.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class CategoriesViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val repository = CategoryRepository(database.categoryDao())

    private val _selectedPeriod = MutableStateFlow(SpendingPeriod.THIS_MONTH)
    val selectedPeriod: StateFlow<SpendingPeriod> = _selectedPeriod.asStateFlow()

    val explorer = CategoryExplorerRepository(database)
        .entriesForPeriod(_selectedPeriod)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories = repository.categories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun setPeriod(period: SpendingPeriod) {
        _selectedPeriod.value = period
    }

    fun addCustom(name: String) {
        viewModelScope.launch { repository.addCustom(name.trim()) }
    }

    fun renameCustom(id: Long, name: String) {
        viewModelScope.launch { repository.renameCustom(id, name.trim()) }
    }

    fun archiveCustom(id: Long) {
        viewModelScope.launch { repository.archiveCustom(id) }
    }
}

class CategoryDetailViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val categoryId: Long = checkNotNull(savedStateHandle["categoryId"])
    private val initialPeriodName: String? = savedStateHandle["period"]
    val initialPeriod: SpendingPeriod = initialPeriodName?.let {
        runCatching { SpendingPeriod.valueOf(it) }.getOrNull()
    } ?: SpendingPeriod.THIS_MONTH

    private val _selectedPeriod = MutableStateFlow(initialPeriod)
    val selectedPeriod: StateFlow<SpendingPeriod> = _selectedPeriod.asStateFlow()

    private val database = SpendWiseDatabase.getInstance(application)
    val customTypes = database.assetDao().observeAllCustomTypes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val entry = CategoryExplorerRepository(database)
        .entriesForPeriod(_selectedPeriod)
        .map { entries -> entries.firstOrNull { it.category.id == categoryId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPeriod(period: SpendingPeriod) {
        _selectedPeriod.value = period
    }
}
