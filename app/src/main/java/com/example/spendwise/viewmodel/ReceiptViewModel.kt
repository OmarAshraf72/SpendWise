package com.example.spendwise.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.spendwise.data.CategoryEntity
import com.example.spendwise.data.CategoryRepository
import com.example.spendwise.data.ItemCategoryMappingRepository
import com.example.spendwise.data.ReceiptExpenseItem
import com.example.spendwise.data.SpendWiseDatabase
import com.example.spendwise.data.TransactionRepository
import com.example.spendwise.ocr.OcrEngineProvider
import com.example.spendwise.ocr.NumericOcrEngine
import com.example.spendwise.ocr.NumericRecoveryDebugEntry
import com.example.spendwise.ocr.PriceSource
import com.example.spendwise.ocr.ReceiptNumericRecovery
import com.example.spendwise.ocr.ReceiptParser
import com.example.spendwise.suggestion.CategorySuggestion
import com.example.spendwise.suggestion.UserLearnedCategorySuggestionEngine
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
    val categoryId: Long?,
    val priceSource: PriceSource = PriceSource.MANUAL,
    val requiresPriceReview: Boolean = false,
    val categorySuggestion: CategorySuggestion? = null
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
    val ocrDebugDetails: String? = null,
    val saveState: ReceiptSaveState = ReceiptSaveState.IDLE,
    val validationMessage: String? = null
) {
    val totalMinor: Long get() = items.fold(0L) { total, item -> total + item.amountMinor }
}

class ReceiptViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val transactionRepository = TransactionRepository(database.transactionDao())
    private val categoryRepository = CategoryRepository(database.categoryDao())
    private val mappingRepository = ItemCategoryMappingRepository(database.itemCategoryMappingDao())
    private val categorySuggestionEngine = UserLearnedCategorySuggestionEngine(mappingRepository)
    private val ocrEngine = OcrEngineProvider.get(application)
    private val receiptParser = ReceiptParser()
    private val numericRecovery = ReceiptNumericRecovery()
    private val isDebuggable = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
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
                    val recoveryTargets = numericRecovery.selectTargets(parsed)
                    val numericCandidates = if (recoveryTargets.isNotEmpty() && ocrEngine is NumericOcrEngine) {
                        try {
                            ocrEngine.recognizeNumericCrops(Uri.parse(uri), result, recoveryTargets)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                    val recovered = numericRecovery.recover(parsed, numericCandidates)
                    val finalReceipt = recovered.receipt
                    val activeCategories = categoryRepository.getActiveCategories()
                    val suggestedItems = finalReceipt.items.map { item ->
                        val suggestion = categorySuggestionEngine.suggest(
                            itemName = item.name,
                            merchant = finalReceipt.merchant,
                            availableCategories = activeCategories
                        )
                        ReceiptItemDraft(
                            id = nextItemId.getAndIncrement(),
                            name = item.name,
                            amountMinor = item.amountMinor,
                            categoryId = suggestion?.categoryId,
                            priceSource = item.priceSource,
                            requiresPriceReview = item.requiresReview,
                            categorySuggestion = suggestion
                        )
                    }
                    _draft.value = _draft.value.copy(
                        merchant = finalReceipt.merchant.orEmpty(),
                        transactionDate = finalReceipt.transactionDate ?: receiptTodayMillis(),
                        items = suggestedItems,
                        detectedTotalMinor = finalReceipt.detectedTotalMinor,
                        ocrState = ReceiptOcrState.COMPLETE,
                        ocrMessage = if (finalReceipt.items.isEmpty() && finalReceipt.merchant == null && finalReceipt.detectedTotalMinor == null) {
                            "Text was detected, but receipt details could not be identified reliably. Add them manually."
                        } else {
                            "Detected values are suggestions. Review and edit them before saving."
                        },
                        ocrDebugDetails = if (isDebuggable) {
                            buildOcrDebugDetails(result, recovered.debugEntries)
                        } else {
                            null
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
        val item = ReceiptItemDraft(
            nextItemId.getAndIncrement(), name.trim(), amountMinor, categoryId,
            priceSource = PriceSource.MANUAL
        )
        _draft.value = _draft.value.copy(
            items = _draft.value.items + item,
            validationMessage = null
        )
    }

    fun updateItem(id: Long, name: String, amountMinor: Long, categoryId: Long) {
        _draft.value = _draft.value.copy(
            items = _draft.value.items.map { item ->
                if (item.id == id) item.copy(
                    name = name.trim(),
                    amountMinor = amountMinor,
                    categoryId = categoryId,
                    priceSource = PriceSource.MANUAL,
                    requiresPriceReview = false,
                    categorySuggestion = null
                )
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
                database.withTransaction {
                    transactionRepository.addReceiptExpenses(
                        items = snapshot.items.map {
                            ReceiptExpenseItem(it.name, it.amountMinor, requireNotNull(it.categoryId))
                        },
                        merchant = snapshot.merchant.trim(),
                        transactionDate = snapshot.transactionDate,
                        receiptGroupId = receiptGroupId
                    )
                    val confirmationTime = System.currentTimeMillis()
                    snapshot.items.forEach { item ->
                        mappingRepository.confirmSelection(
                            itemName = item.name,
                            merchant = snapshot.merchant,
                            categoryId = requireNotNull(item.categoryId),
                            now = confirmationTime
                        )
                    }
                }
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

private fun buildOcrDebugDetails(
    result: com.example.spendwise.ocr.OcrResult,
    recoveries: List<NumericRecoveryDebugEntry>
): String = buildString {
    appendLine("Raw OCR (${result.imageWidth ?: "?"} x ${result.imageHeight ?: "?"})")
    result.lines.forEach { line ->
        appendLine("${line.text} | confidence=${line.confidence} | box=${line.boundingBox}")
    }
    if (recoveries.isNotEmpty()) appendLine("Numeric recovery")
    recoveries.forEach { recovery ->
        appendLine("item=${recovery.itemName} row=${recovery.rowBoundingBox}")
        appendLine(
            "original=${recovery.originalText} (${recovery.originalAmountMinor}) " +
                "confidence=${recovery.originalConfidence}"
        )
        recovery.candidates.forEach { candidate ->
            appendLine(
                "candidate=${candidate.text} confidence=${candidate.confidence} " +
                    "variant=${candidate.preprocessing} box=${candidate.boundingBox}"
            )
        }
        appendLine(
            "selected=${recovery.selectedText ?: recovery.selectedAmountMinor} " +
                "confidence=${recovery.selectedConfidence} source=${recovery.selectedSource}"
        )
    }
}

private fun receiptTodayMillis(): Long = LocalDate.now()
    .atStartOfDay(ZoneOffset.UTC)
    .toInstant()
    .toEpochMilli()
