package com.example.spendwise.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spendwise.data.CategoryEntity
import com.example.spendwise.data.CategoryRepository
import com.example.spendwise.data.ReceiptExpenseItem
import com.example.spendwise.data.SpendWiseDatabase
import com.example.spendwise.data.TransactionRepository
import com.example.spendwise.ocr.OcrEngineProvider
import com.example.spendwise.ocr.ReceiptParser
import android.net.Uri
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ReceiptItemDraft(
    val id: Long,
    val name: String,
    val amountMinor: Long,
    val categoryId: Long?
)

enum class ReceiptSaveState { IDLE, SAVING, SUCCESS, ERROR }
enum class ReceiptOcrState { IDLE, PROCESSING, COMPLETE, NO_TEXT, FAILED }

data class ReceiptDraftUiState(
    val imageUri: String? = null,
    val merchant: String = "",
    val transactionDate: Long = receiptTodayMillis(),
    val items: List<ReceiptItemDraft> = emptyList(),
    val detectedTotalMinor: Long? = null,
    val ocrState: ReceiptOcrState = ReceiptOcrState.IDLE,
    val ocrMessage: String? = null,
    val saveState: ReceiptSaveState = ReceiptSaveState.IDLE,
    val validationMessage: String? = null
) {
    val totalMinor: Long get() = items.fold(0L) { total, item -> total + item.amountMinor }
}

class ReceiptViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val transactionRepository = TransactionRepository(database.transactionDao())
    private val categoryRepository = CategoryRepository(database.categoryDao())
    private val ocrEngine = OcrEngineProvider.get(application)
    private val receiptParser = ReceiptParser()
    private val nextItemId = AtomicLong(1)
    private var ocrJob: Job? = null
    private val _draft = MutableStateFlow(ReceiptDraftUiState())
    val draft: StateFlow<ReceiptDraftUiState> = _draft.asStateFlow()

    val categories = categoryRepository.categories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList<CategoryEntity>()
    )

    fun resetDraft() {
        ocrJob?.cancel()
        _draft.value = ReceiptDraftUiState()
    }

    fun processImage(uri: String, onComplete: () -> Unit) {
        ocrJob?.cancel()
        _draft.value = ReceiptDraftUiState(
            imageUri = uri,
            ocrState = ReceiptOcrState.PROCESSING,
            ocrMessage = "Reading receipt text on your device…"
        )
        ocrJob = viewModelScope.launch {
            var shouldOpenReview = true
            try {
                val result = ocrEngine.recognize(Uri.parse(uri))
                if (result.lines.isEmpty()) {
                    _draft.value = _draft.value.copy(
                        ocrState = ReceiptOcrState.NO_TEXT,
                        ocrMessage = "Text could not be detected. Add the receipt details manually."
                    )
                } else {
                    val parsed = receiptParser.parse(result.lines)
                    _draft.value = _draft.value.copy(
                        merchant = parsed.merchant.orEmpty(),
                        transactionDate = parsed.transactionDate ?: receiptTodayMillis(),
                        items = parsed.items.map { item ->
                            ReceiptItemDraft(
                                id = nextItemId.getAndIncrement(),
                                name = item.name,
                                amountMinor = item.amountMinor,
                                categoryId = null
                            )
                        },
                        detectedTotalMinor = parsed.detectedTotalMinor,
                        ocrState = ReceiptOcrState.COMPLETE,
                        ocrMessage = if (parsed.items.isEmpty() && parsed.merchant == null && parsed.detectedTotalMinor == null) {
                            "Text was detected, but receipt details could not be identified reliably. Add them manually."
                        } else {
                            "Detected values are suggestions. Review and edit them before saving."
                        }
                    )
                }
            } catch (cancellation: CancellationException) {
                shouldOpenReview = false
                throw cancellation
            } catch (_: Throwable) {
                _draft.value = _draft.value.copy(
                    ocrState = ReceiptOcrState.FAILED,
                    ocrMessage = "Text could not be detected. Add the receipt details manually."
                )
            } finally {
                if (shouldOpenReview) onComplete()
            }
        }
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
                        ReceiptExpenseItem(it.name, it.amountMinor, requireNotNull(it.categoryId))
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
