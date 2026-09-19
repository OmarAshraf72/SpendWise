package com.example.spendwise.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spendwise.data.CategoryEntity
import com.example.spendwise.data.CategoryRepository
import com.example.spendwise.data.ReceiptExpenseItem
import com.example.spendwise.data.SpendWiseDatabase
import com.example.spendwise.data.TransactionRepository
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReceiptItemDraft(
    val id: Long,
    val name: String,
    val amountMinor: Long,
    val categoryId: Long
)

enum class ReceiptSaveState { IDLE, SAVING, SUCCESS, ERROR }

data class ReceiptDraftUiState(
    val imageUri: String? = null,
    val merchant: String = "",
    val transactionDate: Long = receiptTodayMillis(),
    val items: List<ReceiptItemDraft> = emptyList(),
    val saveState: ReceiptSaveState = ReceiptSaveState.IDLE,
    val validationMessage: String? = null
) {
    val totalMinor: Long get() = items.fold(0L) { total, item -> total + item.amountMinor }
}

class ReceiptViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val transactionRepository = TransactionRepository(database.transactionDao())
    private val categoryRepository = CategoryRepository(database.categoryDao())
    private val nextItemId = AtomicLong(1)
    private val _draft = MutableStateFlow(ReceiptDraftUiState())
    val draft: StateFlow<ReceiptDraftUiState> = _draft.asStateFlow()

    val categories = categoryRepository.categories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList<CategoryEntity>()
    )

    fun resetDraft() {
        _draft.value = ReceiptDraftUiState()
    }

    fun setImage(uri: String) {
        _draft.value = ReceiptDraftUiState(imageUri = uri)
    }

    fun setMerchant(value: String) {
        _draft.value = _draft.value.copy(merchant = value, validationMessage = null)
    }

    fun setDate(value: Long) {
        _draft.value = _draft.value.copy(transactionDate = value, validationMessage = null)
    }

    fun addItem(name: String, amountMinor: Long, categoryId: Long) {
        val item = ReceiptItemDraft(nextItemId.getAndIncrement(), name.trim(), amountMinor, categoryId)
        _draft.value = _draft.value.copy(
            items = _draft.value.items + item,
            validationMessage = null
        )
    }

    fun updateItem(id: Long, name: String, amountMinor: Long, categoryId: Long) {
        _draft.value = _draft.value.copy(
            items = _draft.value.items.map { item ->
                if (item.id == id) item.copy(name = name.trim(), amountMinor = amountMinor, categoryId = categoryId)
                else item
            },
            validationMessage = null
        )
    }

    fun deleteItem(id: Long) {
        _draft.value = _draft.value.copy(
            items = _draft.value.items.filterNot { it.id == id },
            validationMessage = null
        )
    }

    fun saveReceipt(onSaved: () -> Unit) {
        val snapshot = _draft.value
        if (snapshot.saveState == ReceiptSaveState.SAVING || snapshot.saveState == ReceiptSaveState.SUCCESS) return
        val validation = validate(snapshot)
        if (validation != null) {
            _draft.value = snapshot.copy(saveState = ReceiptSaveState.ERROR, validationMessage = validation)
            return
        }
        _draft.value = snapshot.copy(saveState = ReceiptSaveState.SAVING, validationMessage = null)
        viewModelScope.launch {
            try {
                val receiptGroupId = UUID.randomUUID().toString()
                transactionRepository.addReceiptExpenses(
                    items = snapshot.items.map {
                        ReceiptExpenseItem(it.name, it.amountMinor, it.categoryId)
                    },
                    merchant = snapshot.merchant.trim(),
                    transactionDate = snapshot.transactionDate,
                    receiptGroupId = receiptGroupId
                )
                _draft.value = _draft.value.copy(saveState = ReceiptSaveState.SUCCESS)
                onSaved()
            } catch (_: Exception) {
                _draft.value = _draft.value.copy(
                    saveState = ReceiptSaveState.ERROR,
                    validationMessage = "Receipt could not be saved. Please try again."
                )
            }
        }
    }

    private fun validate(draft: ReceiptDraftUiState): String? = when {
        draft.transactionDate <= 0L -> "Select a receipt date."
        draft.items.isEmpty() -> "Add at least one receipt item."
        draft.items.any { it.name.isBlank() } -> "Every item needs a name."
        draft.items.any { it.amountMinor <= 0L } -> "Every item needs a valid positive price."
        draft.items.any { item -> categories.value.none { it.id == item.categoryId } } ->
            "Every item needs an active category."
        else -> null
    }
}

private fun receiptTodayMillis(): Long = LocalDate.now()
    .atStartOfDay(ZoneOffset.UTC)
    .toInstant()
    .toEpochMilli()
