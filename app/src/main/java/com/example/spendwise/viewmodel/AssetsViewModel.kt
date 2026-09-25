package com.example.spendwise.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.spendwise.data.*
import java.time.LocalDate
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class AssetCardUi(val asset: AssetEntity, val attention: String?, val secondary: String? = null,
                       val latestOwnershipEvent: AssetOwnershipEventEntity? = null)
data class AssetAttentionUi(val assetName: String, val item: AssetAttentionItem)
data class AssetsUiState(val cards: List<AssetCardUi> = emptyList(), val attentionCount: Int = 0,
                         val topAttention: List<AssetAttentionUi> = emptyList())

class AssetsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val repository = AssetRepository(database)
    val customTypes = database.assetDao().observeAllCustomTypes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = database.categoryDao().observeAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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
    val uiState = combine(repository.data, commitmentRepository.data, assetTodayFlow()) { assets, commitmentData, today -> buildAssetCards(assets, today, commitmentData) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetsUiState())

    suspend fun loadForEdit(id: Long): AssetEditSnapshot? = repository.loadForEdit(id)
    suspend fun loadPurchasePrefill(id: Long): AssetPurchasePrefill? = repository.purchasePrefill(id)
    fun createCustomType(name: String, done: (Long?, String?) -> Unit) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { repository.createCustomType(name) } }
            .onSuccess { done(it, null) }.onFailure { done(null, it.message ?: "Could not add item type") }
    }
    fun archiveCustomType(id: Long, done: (String?) -> Unit) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { repository.archiveCustomType(id) } }
            .onSuccess { done(null) }.onFailure { done(it.message ?: "Could not archive item type") }
    }

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
                .onFailure { onError("Item could not be saved. Check the details and try again.") }
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
    val checkpoints: List<AssetCheckpointEntity> = emptyList(),
    val checkpointEvents: List<AssetCheckpointEventEntity> = emptyList(),
    val attention: List<AssetAttentionItem> = emptyList(),
    val documents: List<AssetDocumentEntity> = emptyList(),
    val maintenanceDocumentIds: Set<Long> = emptySet(),
    val linkedCommitments: List<Pair<AssetCommitmentLinkEntity, CommitmentWithMerchant>> = emptyList(),
    val dueByRule: Map<Long, MaintenanceDueResult> = emptyMap(),
    val purchaseTransactionIds: List<Long> = emptyList(),
    val ownershipEvents: List<AssetOwnershipEventEntity> = emptyList()
)

class AssetDetailViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val repository = AssetRepository(database)
    private val storage = AssetDocumentStorage(application)
    private val assetId: Long = checkNotNull(savedStateHandle["assetId"])
    val customTypes = database.assetDao().observeAllCustomTypes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allCategories = database.categoryDao().observeAllCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val transactions = database.transactionDao().observeTransactions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val merchants = MerchantRepository(database.merchantDao()).merchants.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = database.categoryDao().observeCategoriesForEntry().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val commitments = CommitmentRepository(database).data.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommitmentData(emptyList(), emptyList()))
    val reminderRules = database.assetReminderDao().observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState = combine(repository.data, commitments, assetTodayFlow()) { data, commitmentData, today ->
        val asset = data.assets.firstOrNull { it.id == assetId }
        val events = data.events.filter { it.assetId == assetId }
        val rules = data.rules.filter { it.assetId == assetId && it.isActive }
        AssetDetailUiState(
            asset = asset,
            identifiers = data.identifiers.filter { it.assetId == assetId },
            warranties = data.warranties.filter { it.assetId == assetId },
            rules = rules,
            events = events,
            checkpoints = data.checkpoints.filter { it.assetId == assetId },
            checkpointEvents = data.checkpointEvents.filter { event -> data.checkpoints.any { it.id == event.checkpointId && it.assetId == assetId } },
            attention = AssetAttentionEngine.evaluate(data, today, assetId),
            documents = data.documents.filter { it.assetId == assetId },
            maintenanceDocumentIds = data.maintenanceDocumentLinks.mapTo(mutableSetOf()) { it.assetDocumentId },
            purchaseTransactionIds = data.transactionLinks.filter { it.assetId == assetId && it.relationType == AssetTransactionRelationType.PURCHASE }.map { it.transactionId },
            ownershipEvents = data.ownershipEvents.filter { it.assetId == assetId }.sortedWith(compareByDescending<AssetOwnershipEventEntity> { it.createdAt }.thenByDescending { it.id }),
            linkedCommitments = data.commitmentLinks.filter { it.assetId == assetId }.mapNotNull { link ->
                commitmentData.commitments.firstOrNull { it.commitment.id == link.commitmentId }?.let { link to it }
            },
            dueByRule = rules.associate { rule ->
                val latest = events.filter { it.maintenanceRuleId == rule.id }.maxByOrNull { it.performedDateEpochDay }
                rule.id to MaintenanceDueCalculator.calculate(rule, today, asset?.currentMileageKm, latest)
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetDetailUiState())

    fun updateMileage(mileage: Long, result: (Boolean) -> Unit) = viewModelScope.launch {
        val asset = uiState.value.asset ?: return@launch
        val accepted = repository.updateMileage(asset, mileage)
        result(accepted)
        if (accepted) runCatching { AssetReminderCoordinator(getApplication()).evaluate(AssetReminderEvaluation.MILEAGE, assetId) }
    }
    fun addWarranty(warranty: AssetWarrantyEntity) = viewModelScope.launch { repository.addWarranty(warranty.copy(assetId = assetId)) }
    fun addRule(rule: AssetMaintenanceRuleEntity) = viewModelScope.launch { repository.addRule(rule.copy(assetId = assetId)) }
    fun saveCheckpoint(checkpoint: AssetCheckpointEntity, done: () -> Unit) = viewModelScope.launch {
        repository.saveCheckpoint(checkpoint.copy(assetId = assetId))
        done()
    }
    fun completeCheckpoint(id: Long, date: LocalDate, mileageKm: Long?, note: String?, done: () -> Unit) = viewModelScope.launch {
        repository.completeCheckpoint(id, date, mileageKm, note)
        done()
    }
    fun configureReminderRules(source: AssetAttentionSource, sourceId: Long,
                               available: Set<Pair<AssetReminderTriggerKind, Long>>,
                               enabled: Set<Pair<AssetReminderTriggerKind, Long>>, done: () -> Unit) = viewModelScope.launch {
        repository.configureReminderRules(assetId, source, sourceId, available, enabled)
        done()
    }
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
    fun setOwnershipStatus(status: OwnershipStatus, effectiveDate: LocalDate, note: String?, done: (String?) -> Unit) = viewModelScope.launch {
        runCatching { repository.setOwnershipStatus(assetId, status, effectiveDate, note) }
            .onSuccess { done(null) }.onFailure { done(it.message ?: "Could not change ownership status") }
    }
}

data class MaintenanceUiState(
    val asset: AssetEntity? = null,
    val rules: List<AssetMaintenanceRuleEntity> = emptyList(),
    val events: List<AssetMaintenanceEventEntity> = emptyList(),
    val dueByRule: Map<Long, MaintenanceDueResult> = emptyMap(),
    val documents: List<AssetDocumentEntity> = emptyList(),
    val documentLinks: List<AssetMaintenanceDocumentLinkEntity> = emptyList()
)

class MaintenanceManagementViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val assetId: Long = checkNotNull(savedStateHandle["assetId"])
    private val repository = AssetRepository(SpendWiseDatabase.getInstance(application))
    private val storage = AssetDocumentStorage(application)
    private val draftId = UUID.randomUUID().toString()
    private val _pendingDocuments = MutableStateFlow<List<PendingAssetDocument>>(emptyList())
    val pendingDocuments = _pendingDocuments.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var stagingJob: Job? = null
    private var saveJob: Job? = null
    private val database = SpendWiseDatabase.getInstance(application)
    val categories = database.categoryDao().observeCategoriesForEntry().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val merchants = MerchantRepository(database.merchantDao()).merchants.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val uiState = combine(repository.data, assetTodayFlow()) { data, today ->
        val asset = data.assets.firstOrNull { it.id == assetId }
        val rules = data.rules.filter { it.assetId == assetId }
        val events = data.events.filter { it.assetId == assetId }
        MaintenanceUiState(asset, rules, events,
            rules.filter { it.isActive }.associate { rule ->
                val latest = events.filter { it.maintenanceRuleId == rule.id }
                    .maxWithOrNull(compareBy<AssetMaintenanceEventEntity> { it.performedDateEpochDay }.thenBy { it.createdAt })
                rule.id to MaintenanceDueCalculator.calculate(rule, today, asset?.currentMileageKm, latest)
            }, data.documents.filter { it.assetId == assetId }, data.maintenanceDocumentLinks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MaintenanceUiState())

    fun saveRule(rule: AssetMaintenanceRuleEntity, done: (String?) -> Unit) = viewModelScope.launch {
        runCatching { repository.saveRule(rule.copy(assetId = assetId)) }
            .onSuccess { done(null) }.onFailure { done(it.message ?: "Rule could not be saved") }
    }
    fun archiveRule(id: Long, done: (String?) -> Unit) = viewModelScope.launch {
        runCatching { repository.archiveRule(id, assetId) }
            .onSuccess { done(null) }.onFailure { done(it.message ?: "Rule could not be archived") }
    }
    fun stageDocuments(uris: List<Uri>, done: (String?) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        stagingJob = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                uris.map { storage.stage(it, draftId, AssetDocumentType.SERVICE_RECEIPT, "Service receipt") }
            } }.onSuccess { _pendingDocuments.value = _pendingDocuments.value + it; done(null) }
                .onFailure { done("Document could not be copied. Choose an image or PDF.") }
            _busy.value = false
        }
    }
    fun removeDocument(document: PendingAssetDocument) {
        _pendingDocuments.value = _pendingDocuments.value - document
        viewModelScope.launch(Dispatchers.IO) { storage.deleteDraft(document) }
    }
    fun clearDrafts() {
        _pendingDocuments.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { stagingJob?.join(); storage.clearDraft(draftId) }
    }
    fun complete(input: MaintenanceEventInput, done: (String?) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        saveJob = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                repository.completeMaintenance(input.copy(assetId = assetId), _pendingDocuments.value, storage)
            } }.onSuccess {
                _pendingDocuments.value = emptyList()
                withContext(Dispatchers.IO) { storage.clearDraft(draftId) }
                runCatching { AssetReminderCoordinator(getApplication()).evaluate(AssetReminderEvaluation.MILEAGE, assetId) }
                done(null)
            }.onFailure { done(it.message ?: "Maintenance could not be saved") }
            _busy.value = false
        }
    }
    fun documentFile(relativePath: String) = storage.file(relativePath)
    override fun onCleared() {
        CoroutineScope(Dispatchers.IO).launch {
            stagingJob?.join(); saveJob?.join(); storage.clearDraft(draftId)
        }
        super.onCleared()
    }
}

private fun emptyAssetData() = AssetData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

private fun assetTodayFlow() = flow {
    while (true) {
        emit(LocalDate.now())
        val now = ZonedDateTime.now()
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L))
    }
}

private fun buildAssetCards(data: AssetData, today: LocalDate, commitments: CommitmentData? = null): AssetsUiState {
    val attention = AssetAttentionEngine.evaluate(data, today)
    val cards = data.assets.map { asset ->
        val mostImportant = attention.firstOrNull { it.assetId == asset.id && it.status != AssetAttentionStatus.UPCOMING }
        val financing = data.commitmentLinks.firstOrNull { it.assetId == asset.id && it.relationType == AssetCommitmentRelationType.FINANCING }
            ?.let { link -> commitments?.commitments?.firstOrNull { it.commitment.id == link.commitmentId } }
            ?.let { "Financing: ${formatEgp(it.commitment.amountMinor)} · ${LocalDate.ofEpochDay(it.commitment.nextDueDateEpochDay)}" }
        AssetCardUi(asset, mostImportant?.message, financing,
            data.ownershipEvents.filter { it.assetId == asset.id }.maxWithOrNull(compareBy<AssetOwnershipEventEntity> { it.createdAt }.thenBy { it.id }))
    }
    return AssetsUiState(cards, cards.count { it.attention != null },
        attention.filter { it.status != AssetAttentionStatus.UPCOMING }.take(3).mapNotNull { item ->
            data.assets.firstOrNull { it.id == item.assetId }?.let { AssetAttentionUi(it.name, item) }
        })
}
