package com.example.spendwise.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.spendwise.data.CategoryRepository
import com.example.spendwise.data.ManualExpensePart
import com.example.spendwise.data.MerchantRanker
import com.example.spendwise.data.MerchantRepository
import com.example.spendwise.data.MerchantSuggestion
import com.example.spendwise.data.SpendWiseDatabase
import com.example.spendwise.data.SplitExpenseInput
import com.example.spendwise.data.TransactionRepository
import com.example.spendwise.data.validateSplitAllocation
import com.example.spendwise.data.categoriesAreActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class TransactionsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val transactionRepository = TransactionRepository(database.transactionDao())
    private val categoryRepository = CategoryRepository(database.categoryDao())
    private val merchantRepository = MerchantRepository(database.merchantDao())
    private val merchantQuery = MutableStateFlow("")

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

    val entryCategories = categoryRepository.categoriesForEntry.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val merchantSuggestions = combine(merchantQuery, merchantRepository.merchants) { query, merchants ->
        MerchantRanker.rank(query, merchants)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateMerchantQuery(value: String) { merchantQuery.value = value }

    fun createMerchant(name: String, onCreated: (com.example.spendwise.data.MerchantEntity?) -> Unit) {
        viewModelScope.launch {
            onCreated(merchantRepository.createIfMissing(name, System.currentTimeMillis()))
        }
    }

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

    fun saveManualPurchase(
        totalMinor: Long,
        categoryId: Long?,
        merchantName: String,
        transactionDate: Long,
        splits: List<SplitExpenseInput>?,
        onSaved: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (totalMinor <= 0L) return onError("Enter an amount greater than zero.")
        val parts = if (splits == null) {
            val selected = categoryId ?: return onError("Select a category.")
            listOf(ManualExpensePart(totalMinor, selected))
        } else {
            val allocation = validateSplitAllocation(totalMinor, splits)
            if (!allocation.isExact) return onError(checkNotNull(allocation.message))
            splits.map { ManualExpensePart(checkNotNull(it.amountMinor), checkNotNull(it.categoryId), it.note?.trim()) }
        }
        val activeIds = entryCategories.value.mapTo(mutableSetOf()) { it.id }
        if (!categoriesAreActive(parts, activeIds)) return onError("Every expense needs an active category.")
        viewModelScope.launch {
            try {
                database.withTransaction {
                    val learnedCategory = parts.maxByOrNull(ManualExpensePart::amountMinor)?.categoryId
                    val merchant = learnedCategory?.let {
                        merchantRepository.recordUse(merchantName, it, System.currentTimeMillis())
                    }
                    transactionRepository.addManualPurchase(
                        parts = parts,
                        merchantId = merchant?.id,
                        merchantName = merchant?.displayName ?: merchantName.trim(),
                        transactionDate = transactionDate,
                        purchaseGroupId = if (parts.size > 1) UUID.randomUUID().toString() else null
                    )
                }
                onSaved()
            } catch (_: Exception) {
                onError("Expense could not be saved. Please try again.")
            }
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

class TransactionDetailViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val transactionId: Long = checkNotNull(savedStateHandle["transactionId"])
    val transaction = TransactionRepository(SpendWiseDatabase.getInstance(application).transactionDao()).transactions
        .map { rows -> rows.firstOrNull { it.transaction.id == transactionId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
