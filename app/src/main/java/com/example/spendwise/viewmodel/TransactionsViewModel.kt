package com.example.spendwise.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spendwise.data.CategoryRepository
import com.example.spendwise.data.SpendWiseDatabase
import com.example.spendwise.data.TransactionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TransactionsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val transactionRepository = TransactionRepository(database.transactionDao())
    private val categoryRepository = CategoryRepository(database.categoryDao())

    val transactions = transactionRepository.transactions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val categories = categoryRepository.categories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun addManualExpense(
        amountMinor: Long,
        categoryId: Long,
        merchant: String?,
        note: String?,
        transactionDate: Long,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            transactionRepository.addManualExpense(
                amountMinor = amountMinor,
                categoryId = categoryId,
                merchant = merchant,
                note = note,
                transactionDate = transactionDate
            )
            onSaved()
        }
    }

    fun addManualIncome(
        amountMinor: Long,
        name: String?,
        note: String?,
        transactionDate: Long,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            transactionRepository.addManualIncome(
                amountMinor = amountMinor,
                name = name,
                note = note,
                transactionDate = transactionDate
            )
            onSaved()
        }
    }
}
