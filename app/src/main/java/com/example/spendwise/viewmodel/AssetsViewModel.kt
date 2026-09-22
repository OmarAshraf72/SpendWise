package com.example.spendwise.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.spendwise.data.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AssetCardUi(val asset: AssetEntity, val attention: String?, val secondary: String? = null)
data class AssetsUiState(val cards: List<AssetCardUi> = emptyList(), val attentionCount: Int = 0)

class AssetsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val repository = AssetRepository(database)
    private val documentStorage = AssetDocumentStorage(application)
    private val draftId = UUID.randomUUID().toString()
    private val _pendingDocuments = MutableStateFlow<List<PendingAssetDocument>>(emptyList())
    val pendingDocuments = _pendingDocuments.asStateFlow()
    private val _isStagingDocument = MutableStateFlow(false)
    val isStagingDocument = _isStagingDocument.asStateFlow()
    private var stagingJob: Job? = null
    private var saveJob: Job? = null
    init { viewModelScope.launch(Dispatchers.IO) { documentStorage.pruneStaleDrafts() } }
    private val commitmentRepository = CommitmentRepository(database)
    private val merchantRepository = MerchantRepository(database.merchantDao())
    val merchants = merchantRepository.merchants.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val commitments = commitmentRepository.data.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommitmentData(emptyList(), emptyList()))
    val data = repository.data.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyAssetData())
    val uiState = repository.data.combine(commitmentRepository.data) { assets, commitmentData -> buildAssetCards(assets, LocalDate.now(), commitmentData) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetsUiState())

    suspend fun loadForEdit(id: Long): AssetEditSnapshot? = repository.loadForEdit(id)

    fun addPendingDocument(uri: Uri, type: AssetDocumentType, title: String, onResult: (String?) -> Unit) {
        if (_isStagingDocument.value) return
        _isStagingDocument.value = true
        stagingJob = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { documentStorage.stage(uri, draftId, type, title) } }
                .onSuccess {
                    _pendingDocuments.value = PendingAssetDocumentDraftLogic.add(_pendingDocuments.value, it)
                    onResult(null)
                }
                .onFailure { onResult("Document could not be copied. Choose an image or PDF.") }
            _isStagingDocument.value = false
        }
    }

    fun removePendingDocument(draft: PendingAssetDocument) {
        _pendingDocuments.value = PendingAssetDocumentDraftLogic.remove(_pendingDocuments.value, draft)
        viewModelScope.launch(Dispatchers.IO) { documentStorage.deleteDraft(draft) }
    }

    fun save(input: NewAssetInput, onSaved: (Long) -> Unit, onError: (String) -> Unit) {
        saveJob = viewModelScope.launch {
            val documents = _pendingDocuments.value
            runCatching { withContext(Dispatchers.IO) { repository.save(input, documents, documentStorage) } }
                .onSuccess { id ->
                    _pendingDocuments.value = emptyList()
                    withContext(Dispatchers.IO) { documentStorage.clearDraft(draftId) }
                    onSaved(id)
                }
                .onFailure { onError("Asset could not be saved. Check the details and try again.") }
        }
    }

    override fun onCleared() {
        CoroutineScope(Dispatchers.IO).launch {
            stagingJob?.join()
            saveJob?.join()
            documentStorage.clearDraft(draftId)
        }
        super.onCleared()
    }
}

data class AssetDetailUiState(
    val asset: AssetEntity? = null,
    val identifiers: List<AssetIdentifierEntity> = emptyList(),
    val warranties: List<AssetWarrantyEntity> = emptyList(),
    val rules: List<AssetMaintenanceRuleEntity> = emptyList(),
    val events: List<AssetMaintenanceEventEntity> = emptyList(),
    val documents: List<AssetDocumentEntity> = emptyList(),
    val linkedCommitments: List<Pair<AssetCommitmentLinkEntity, CommitmentWithMerchant>> = emptyList(),
    val dueByRule: Map<Long, MaintenanceDueResult> = emptyMap()
)

class AssetDetailViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val repository = AssetRepository(database)
    private val storage = AssetDocumentStorage(application)
    private val assetId: Long = checkNotNull(savedStateHandle["assetId"])
    val merchants = MerchantRepository(database.merchantDao()).merchants.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = database.categoryDao().observeCategoriesForEntry().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val commitments = CommitmentRepository(database).data.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommitmentData(emptyList(), emptyList()))

    val uiState = combine(repository.data, commitments) { data, commitmentData ->
        val asset = data.assets.firstOrNull { it.id == assetId }
        val events = data.events.filter { it.assetId == assetId }
        val rules = data.rules.filter { it.assetId == assetId }
        AssetDetailUiState(
            asset = asset,
            identifiers = data.identifiers.filter { it.assetId == assetId },
            warranties = data.warranties.filter { it.assetId == assetId },
            rules = rules,
            events = events,
            documents = data.documents.filter { it.assetId == assetId },
            linkedCommitments = data.commitmentLinks.filter { it.assetId == assetId }.mapNotNull { link ->
                commitmentData.commitments.firstOrNull { it.commitment.id == link.commitmentId }?.let { link to it }
            },
            dueByRule = rules.associate { rule ->
                val latest = events.filter { it.maintenanceRuleId == rule.id }.maxByOrNull { it.performedDateEpochDay }
                rule.id to MaintenanceDueCalculator.calculate(rule, LocalDate.now(), asset?.currentMileageKm, latest)
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetDetailUiState())

    fun updateMileage(mileage: Long, result: (Boolean) -> Unit) = viewModelScope.launch {
        val asset = uiState.value.asset ?: return@launch
        result(repository.updateMileage(asset, mileage))
    }
    fun addWarranty(warranty: AssetWarrantyEntity) = viewModelScope.launch { repository.addWarranty(warranty.copy(assetId = assetId)) }
    fun addRule(rule: AssetMaintenanceRuleEntity) = viewModelScope.launch { repository.addRule(rule.copy(assetId = assetId)) }
    fun linkCommitment(id: Long, type: AssetCommitmentRelationType) = viewModelScope.launch { repository.linkCommitment(assetId, id, type) }
    fun recordMaintenance(input: MaintenanceEventInput, saved: () -> Unit) = viewModelScope.launch { repository.recordMaintenance(input.copy(assetId = assetId)); saved() }
    fun attachDocument(uri: Uri, type: AssetDocumentType, title: String, result: (Boolean) -> Unit) = viewModelScope.launch {
        runCatching {
            val stored = storage.copyFrom(uri)
            repository.attachDocument(AssetDocumentEntity(assetId = assetId, documentType = type, title = title.ifBlank { stored.originalName }, storedRelativePath = stored.relativePath, mimeType = stored.mimeType, originalFileName = stored.originalName, fileSizeBytes = stored.sizeBytes, createdAt = System.currentTimeMillis()))
        }.onSuccess { result(true) }.onFailure { result(false) }
    }
    fun documentFile(relativePath: String) = storage.file(relativePath)
    fun deleteDocument(id: Long) = viewModelScope.launch { repository.deleteDocument(id, storage) }
    fun renameDocument(id: Long, title: String) = viewModelScope.launch { repository.renameDocument(id, title) }
    fun archive(done: () -> Unit) = viewModelScope.launch { repository.archive(assetId); done() }
}

private fun emptyAssetData() = AssetData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

private fun buildAssetCards(data: AssetData, today: LocalDate, commitments: CommitmentData? = null): AssetsUiState {
    val cards = data.assets.map { asset ->
        val warrantyAttention = data.warranties.filter { it.assetId == asset.id }.mapNotNull { warranty ->
            val status = WarrantyCalculator.status(LocalDate.ofEpochDay(warranty.endDateEpochDay), today)
            if (status.state == WarrantyState.ACTIVE) null else WarrantyCalculator.remainingLabel(LocalDate.ofEpochDay(warranty.endDateEpochDay), today)
        }.firstOrNull()
        val events = data.events.filter { it.assetId == asset.id }
        val maintenance = data.rules.filter { it.assetId == asset.id }.mapNotNull { rule ->
            val latest = events.filter { it.maintenanceRuleId == rule.id }.maxByOrNull { it.performedDateEpochDay }
            val due = MaintenanceDueCalculator.calculate(rule, today, asset.currentMileageKm, latest)
            if (due.status == MaintenanceDueStatus.OK) null else when (due.triggerReason) {
                MaintenanceTriggerReason.MILEAGE -> "${rule.title}: ${due.remainingKm?.let { if (it < 0) "overdue by ${-it} km" else "due in $it km" }}"
                else -> "${rule.title}: ${due.remainingDays?.let { if (it < 0) "overdue by ${-it} days" else "due in $it days" }}"
            }
        }.firstOrNull()
        val financing = data.commitmentLinks.firstOrNull { it.assetId == asset.id && it.relationType == AssetCommitmentRelationType.FINANCING }
            ?.let { link -> commitments?.commitments?.firstOrNull { it.commitment.id == link.commitmentId } }
            ?.let { "Financing: ${formatEgp(it.commitment.amountMinor)} · ${LocalDate.ofEpochDay(it.commitment.nextDueDateEpochDay)}" }
        AssetCardUi(asset, maintenance ?: warrantyAttention, financing)
    }
    return AssetsUiState(cards, cards.count { it.attention != null })
}
