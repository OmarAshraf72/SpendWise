package com.example.spendwise.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.combine

data class AssetData(
    val assets: List<AssetEntity>,
    val identifiers: List<AssetIdentifierEntity>,
    val warranties: List<AssetWarrantyEntity>,
    val rules: List<AssetMaintenanceRuleEntity>,
    val events: List<AssetMaintenanceEventEntity>,
    val documents: List<AssetDocumentEntity>,
    val commitmentLinks: List<AssetCommitmentLinkEntity>,
    val transactionLinks: List<AssetTransactionLinkEntity>,
    val checkpoints: List<AssetCheckpointEntity>,
    val checkpointEvents: List<AssetCheckpointEventEntity>
)

data class NewAssetInput(
    val asset: AssetEntity,
    val identifiers: List<AssetIdentifierEntity> = emptyList(),
    val warranty: AssetWarrantyEntity? = null,
    val commitmentId: Long? = null
)

data class PendingAssetDocument(
    val draftRelativePath: String,
    val documentType: AssetDocumentType,
    val title: String,
    val originalFileName: String,
    val mimeType: String,
    val fileSizeBytes: Long
)

object PendingAssetDocumentDraftLogic {
    fun add(current: List<PendingAssetDocument>, document: PendingAssetDocument): List<PendingAssetDocument> =
        current + document

    fun remove(current: List<PendingAssetDocument>, document: PendingAssetDocument): List<PendingAssetDocument> =
        current - document
}

fun AssetDocumentType.displayTitle(): String = when (this) {
    AssetDocumentType.INVOICE -> "Purchase invoice"
    AssetDocumentType.WARRANTY_CARD -> "Warranty card"
    AssetDocumentType.PURCHASE_CONTRACT -> "Purchase contract"
    AssetDocumentType.OTHER -> "Other document"
    else -> name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

data class AssetEditSnapshot(
    val asset: AssetEntity,
    val identifiers: List<AssetIdentifierEntity>,
    val warranties: List<AssetWarrantyEntity>,
    val commitmentLinks: List<AssetCommitmentLinkEntity>
)

data class MaintenanceEventInput(
    val assetId: Long,
    val ruleId: Long?,
    val title: String,
    val performedDate: LocalDate,
    val mileageKm: Long?,
    val costMinor: Long?,
    val merchantId: Long?,
    val merchantName: String?,
    val categoryId: Long?,
    val createExpense: Boolean,
    val notes: String?,
    val idempotencyKey: String
)

class AssetRepository(private val database: SpendWiseDatabase) {
    private val dao = database.assetDao()
    private val reminderDao = database.assetReminderDao()
    val data = combine(
        dao.observeActiveAssets(), dao.observeIdentifiers(), dao.observeWarranties(), dao.observeMaintenanceRules(),
        dao.observeMaintenanceEvents(), dao.observeDocuments(), dao.observeCommitmentLinks(), dao.observeTransactionLinks(),
        dao.observeCheckpoints(), dao.observeCheckpointEvents()
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        AssetData(
            values[0] as List<AssetEntity>, values[1] as List<AssetIdentifierEntity>,
            values[2] as List<AssetWarrantyEntity>, values[3] as List<AssetMaintenanceRuleEntity>,
            values[4] as List<AssetMaintenanceEventEntity>, values[5] as List<AssetDocumentEntity>,
            values[6] as List<AssetCommitmentLinkEntity>, values[7] as List<AssetTransactionLinkEntity>,
            values[8] as List<AssetCheckpointEntity>, values[9] as List<AssetCheckpointEventEntity>
        )
    }

    fun observeAsset(id: Long) = dao.observeAsset(id)

    suspend fun loadForEdit(id: Long): AssetEditSnapshot? = dao.getAsset(id)?.let { asset ->
        AssetEditSnapshot(asset, dao.getIdentifiersForAsset(id), dao.getWarrantiesForAsset(id), dao.getCommitmentLinksForAsset(id))
    }

    suspend fun save(input: NewAssetInput, pendingDocuments: List<PendingAssetDocument> = emptyList(), storage: AssetDocumentStorage? = null): Long {
        require(pendingDocuments.isEmpty() || storage != null)
        val promoted = mutableListOf<StoredAssetDocument>()
        try {
            pendingDocuments.forEach { promoted += requireNotNull(storage).promote(it) }
            return database.withTransaction { saveAssetAndDocuments(input, pendingDocuments, promoted) }
        } catch (error: Exception) {
            promoted.forEach { storage?.delete(it.relativePath) }
            throw error
        }
    }

    private suspend fun saveAssetAndDocuments(
        input: NewAssetInput,
        pendingDocuments: List<PendingAssetDocument>,
        promoted: List<StoredAssetDocument>
    ): Long {
        require(input.asset.name.isNotBlank())
        val assetId = if (input.asset.id == 0L) dao.insertAsset(input.asset) else {
            requireNotNull(dao.getAsset(input.asset.id))
            dao.updateAsset(input.asset)
            input.asset.id
        }
        if (input.asset.id != 0L) dao.deleteIdentifiersForAsset(assetId)
        require(input.identifiers.map { it.type to it.value.trim() }.distinct().size == input.identifiers.size)
        if (input.identifiers.isNotEmpty()) dao.insertIdentifiers(input.identifiers.map { it.copy(assetId = assetId) })
        input.warranty?.let { warranty ->
            if (warranty.id == 0L) dao.insertWarranty(warranty.copy(assetId = assetId))
            else {
                require(dao.getWarrantiesForAsset(assetId).any { it.id == warranty.id })
                dao.updateWarranty(warranty.copy(assetId = assetId))
            }
        }
        input.commitmentId?.let { dao.linkCommitment(AssetCommitmentLinkEntity(assetId, it, AssetCommitmentRelationType.FINANCING)) }
        pendingDocuments.zip(promoted).forEach { (draft, stored) ->
            dao.insertDocument(AssetDocumentEntity(
                assetId = assetId, documentType = draft.documentType, title = draft.title,
                storedRelativePath = stored.relativePath, mimeType = stored.mimeType,
                originalFileName = draft.originalFileName, fileSizeBytes = stored.sizeBytes,
                createdAt = System.currentTimeMillis()
            ))
        }
        return assetId
    }

    suspend fun create(input: NewAssetInput): Long = save(input)

    suspend fun addWarranty(warranty: AssetWarrantyEntity) = dao.insertWarranty(warranty)
    suspend fun addRule(rule: AssetMaintenanceRuleEntity) = dao.insertRule(rule)
    suspend fun saveCheckpoint(checkpoint: AssetCheckpointEntity): Long = database.withTransaction {
        AssetAttentionEngine.validateCheckpoint(checkpoint)
        require(dao.getAsset(checkpoint.assetId)?.isArchived == false)
        if (checkpoint.id == 0L) dao.insertCheckpoint(checkpoint.copy(title = checkpoint.title.trim())) else {
            val previous = requireNotNull(dao.getCheckpoint(checkpoint.id))
            require(previous.assetId == checkpoint.assetId)
            val current = AssetAttentionEngine.checkpointOccurrence(previous, dao.latestCheckpointEvent(previous.id))
            val updated = if (checkpoint.triggerMode != previous.triggerMode) checkpoint.copy(
                title = checkpoint.title.trim(), rescheduledDueDateEpochDay = null,
                rescheduledDueMileageKm = null
            ) else checkpoint.copy(
                title = checkpoint.title.trim(),
                dueDateEpochDay = previous.dueDateEpochDay,
                dueMileageKm = previous.dueMileageKm,
                rescheduledDueDateEpochDay = checkpoint.dueDateEpochDay.takeIf { it != current.dueDateEpochDay }
                    ?: previous.rescheduledDueDateEpochDay,
                rescheduledDueMileageKm = checkpoint.dueMileageKm.takeIf { it != current.dueMileageKm }
                    ?: previous.rescheduledDueMileageKm
            )
            dao.updateCheckpoint(updated)
            val existingRules = reminderDao.rulesForSource(AssetAttentionSource.CHECKPOINT, checkpoint.id)
            if (existingRules.isNotEmpty()) {
                suspend fun syncWarning(kind: AssetReminderTriggerKind, old: Long?, new: Long?) {
                    if (old == new) return
                    old?.let { reminderDao.setRuleEnabled(AssetAttentionSource.CHECKPOINT, checkpoint.id, kind, it, false) }
                    new?.takeIf { it > 0 }?.let { lead ->
                        reminderDao.insertRule(AssetReminderRuleEntity(assetId = checkpoint.assetId,
                            sourceType = AssetAttentionSource.CHECKPOINT, sourceId = checkpoint.id,
                            triggerKind = kind, leadValue = lead))
                        reminderDao.setRuleEnabled(AssetAttentionSource.CHECKPOINT, checkpoint.id, kind, lead, true)
                    }
                }
                syncWarning(AssetReminderTriggerKind.DAYS_BEFORE,
                    previous.warningDays?.toLong().takeIf { previous.triggerMode != CheckpointTriggerMode.MILEAGE },
                    checkpoint.warningDays?.toLong().takeIf { checkpoint.triggerMode != CheckpointTriggerMode.MILEAGE })
                syncWarning(AssetReminderTriggerKind.KM_BEFORE,
                    previous.warningKm.takeIf { previous.triggerMode != CheckpointTriggerMode.DATE },
                    checkpoint.warningKm.takeIf { checkpoint.triggerMode != CheckpointTriggerMode.DATE })
            }
            checkpoint.id
        }
    }

    suspend fun completeCheckpoint(id: Long, date: LocalDate, mileageKm: Long?, note: String?): Long = database.withTransaction {
        val checkpoint = requireNotNull(dao.getCheckpoint(id))
        require(checkpoint.isActive)
        if (checkpoint.repeatKm != null) require(mileageKm != null && mileageKm >= 0)
        val due = AssetAttentionEngine.checkpointOccurrence(checkpoint, dao.latestCheckpointEvent(id))
        val eventId = dao.insertCheckpointEvent(AssetCheckpointEventEntity(
            checkpointId = id, completedDateEpochDay = date.toEpochDay(), completedMileageKm = mileageKm,
            note = note?.trim()?.takeIf(String::isNotBlank), createdAt = System.currentTimeMillis(),
            dueDateEpochDay = due.dueDateEpochDay, dueMileageKm = due.dueMileageKm
        ))
        dao.updateCheckpoint(checkpoint.copy(
            isActive = checkpoint.repeatMonths != null || checkpoint.repeatKm != null,
            rescheduledDueDateEpochDay = null, rescheduledDueMileageKm = null,
            updatedAt = System.currentTimeMillis()
        ))
        eventId
    }

    suspend fun configureReminderRules(
        assetId: Long, source: AssetAttentionSource, sourceId: Long,
        available: Set<Pair<AssetReminderTriggerKind, Long>>,
        enabled: Set<Pair<AssetReminderTriggerKind, Long>>
    ) = database.withTransaction {
        require(enabled.all { it in available })
        val existing = reminderDao.rulesForSource(source, sourceId)
        existing.forEach { reminderDao.setRuleEnabled(source, sourceId, it.triggerKind, it.leadValue,
            (it.triggerKind to it.leadValue) in enabled) }
        available.filterNot { option -> existing.any { it.triggerKind == option.first && it.leadValue == option.second } }
            .forEach { (kind, lead) -> reminderDao.insertRule(AssetReminderRuleEntity(
                assetId = assetId, sourceType = source, sourceId = sourceId,
                triggerKind = kind, leadValue = lead, isEnabled = (kind to lead) in enabled
            )) }
    }
    suspend fun linkCommitment(assetId: Long, commitmentId: Long, type: AssetCommitmentRelationType) =
        dao.linkCommitment(AssetCommitmentLinkEntity(assetId, commitmentId, type))

    suspend fun updateMileage(asset: AssetEntity, mileage: Long): Boolean {
        if (!canUpdateAssetMileage(asset.currentMileageKm, mileage)) return false
        dao.updateMileage(asset.id, mileage, System.currentTimeMillis())
        return true
    }

    suspend fun recordMaintenance(input: MaintenanceEventInput): Long = database.withTransaction {
        val plan = planAssetMaintenanceWrite(dao.eventByKey(input.idempotencyKey), input.createExpense)
        plan.existingEventId?.let { return@withTransaction it }
        require(input.title.isNotBlank() && (input.costMinor == null || input.costMinor > 0))
        val transactionId = if (plan.createTransaction) {
            require(input.costMinor != null && input.categoryId != null)
            database.transactionDao().insert(
                TransactionEntity(
                    type = TransactionType.EXPENSE, amountMinor = input.costMinor, categoryId = input.categoryId,
                    merchant = input.merchantName?.takeIf(String::isNotBlank), note = input.title,
                    transactionDate = input.performedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    source = TransactionSource.MANUAL, createdAt = System.currentTimeMillis(), receiptGroupId = null,
                    merchantId = input.merchantId, purchaseGroupId = null
                )
            )
        } else null
        val eventId = dao.insertEvent(
            AssetMaintenanceEventEntity(
                assetId = input.assetId, maintenanceRuleId = input.ruleId, title = input.title.trim(),
                performedDateEpochDay = input.performedDate.toEpochDay(), mileageKm = input.mileageKm,
                costMinor = input.costMinor, serviceMerchantId = input.merchantId, linkedTransactionId = transactionId,
                notes = input.notes?.trim()?.takeIf(String::isNotBlank), idempotencyKey = input.idempotencyKey,
                createdAt = System.currentTimeMillis()
            )
        )
        if (transactionId != null) dao.linkTransaction(AssetTransactionLinkEntity(input.assetId, transactionId, AssetTransactionRelationType.MAINTENANCE))
        eventId
    }

    suspend fun attachDocument(document: AssetDocumentEntity) = dao.insertDocument(document)
    suspend fun renameDocument(id: Long, title: String) { require(title.isNotBlank()); dao.renameDocument(id, title.trim()) }
    suspend fun deleteDocument(id: Long, storage: AssetDocumentStorage) {
        dao.getDocument(id)?.let { storage.delete(it.storedRelativePath) }
        dao.deleteDocument(id)
    }
    suspend fun archive(assetId: Long) = dao.archive(assetId, System.currentTimeMillis())
}

data class StoredAssetDocument(val relativePath: String, val mimeType: String, val originalName: String, val sizeBytes: Long)

class AssetDocumentStorage(private val context: Context) {
    private val root = File(context.filesDir, "assets/documents")
    private val draftRoot = File(context.cacheDir, "assets/document-drafts")

    fun stage(uri: Uri, draftId: String, type: AssetDocumentType, title: String): PendingAssetDocument {
        require(draftId.matches(Regex("[a-f0-9-]{36}")))
        val details = sourceDetails(uri)
        require(details.mimeType == "application/pdf" || details.mimeType.startsWith("image/"))
        val directory = File(draftRoot, draftId).apply { mkdirs() }
        val file = File(directory, UUID.randomUUID().toString())
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Document could not be opened" }
                file.outputStream().use(input::copyTo)
            }
            require(file.length() > 0) { "Document is empty" }
            directory.setLastModified(System.currentTimeMillis())
            return PendingAssetDocument(
                "assets/document-drafts/$draftId/${file.name}", type,
                title.trim().ifBlank { type.displayTitle() }, details.originalName,
                details.mimeType, file.length()
            )
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun promote(draft: PendingAssetDocument): StoredAssetDocument {
        val source = draftFile(draft.draftRelativePath)
        require(source.isFile) { "Draft document is missing" }
        root.mkdirs()
        val extension = draft.originalFileName.substringAfterLast('.', "bin").take(10)
            .filter(Char::isLetterOrDigit).ifBlank { "bin" }
        val relative = "assets/documents/${UUID.randomUUID()}.$extension"
        val destination = File(context.filesDir, relative)
        try {
            source.copyTo(destination)
            return StoredAssetDocument(relative, draft.mimeType, draft.originalFileName, destination.length())
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun deleteDraft(draft: PendingAssetDocument): Boolean = draftFile(draft.draftRelativePath).delete()

    fun clearDraft(draftId: String) {
        require(draftId.matches(Regex("[a-f0-9-]{36}")))
        File(draftRoot, draftId).deleteRecursively()
    }

    fun pruneStaleDrafts(now: Long = System.currentTimeMillis()) {
        draftRoot.listFiles()?.filter { it.isDirectory && now - it.lastModified() > 24 * 60 * 60 * 1000L }
            ?.forEach(File::deleteRecursively)
    }

    private fun draftFile(relative: String): File {
        require(Regex("assets/document-drafts/[a-f0-9-]{36}/[a-f0-9-]{36}").matches(relative))
        return File(context.cacheDir, relative)
    }

    private fun sourceDetails(uri: Uri): StoredAssetDocument {
        var name = "document"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
        val declaredMime = context.contentResolver.getType(uri)
        val mime = declaredMime?.takeUnless { it == "application/octet-stream" }
            ?: when (name.substringAfterLast('.', "").lowercase()) {
                "pdf" -> "application/pdf"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }
        return StoredAssetDocument("", mime, name, 0)
    }

    fun copyFrom(uri: Uri): StoredAssetDocument {
        root.mkdirs()
        var name = "document"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0) ?: name
                size = if (cursor.isNull(1)) -1L else cursor.getLong(1)
            }
        }
        val extension = name.substringAfterLast('.', "bin").take(10).filter(Char::isLetterOrDigit).ifBlank { "bin" }
        val relative = "assets/documents/${UUID.randomUUID()}.$extension"
        val destination = File(context.filesDir, relative)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Document could not be opened" }
            destination.outputStream().use(input::copyTo)
        }
        return StoredAssetDocument(relative, context.contentResolver.getType(uri) ?: "application/octet-stream", name, if (size >= 0) size else destination.length())
    }

    fun file(relativePath: String): File? = File(context.filesDir, relativePath).takeIf { it.isFile }
    fun delete(relativePath: String): Boolean = isSafeAssetDocumentPath(relativePath) && File(context.filesDir, relativePath).delete()
}
