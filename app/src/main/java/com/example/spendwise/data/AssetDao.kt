package com.example.spendwise.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets WHERE isArchived = 0 ORDER BY updatedAt DESC, name ASC")
    fun observeActiveAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE id = :id LIMIT 1")
    fun observeAsset(id: Long): Flow<AssetEntity?>
    @Query("SELECT * FROM assets WHERE id = :id LIMIT 1")
    suspend fun getAsset(id: Long): AssetEntity?
    @Query("SELECT * FROM asset_identifiers WHERE assetId = :assetId ORDER BY id")
    suspend fun getIdentifiersForAsset(assetId: Long): List<AssetIdentifierEntity>
    @Query("SELECT * FROM asset_warranties WHERE assetId = :assetId ORDER BY id")
    suspend fun getWarrantiesForAsset(assetId: Long): List<AssetWarrantyEntity>
    @Query("SELECT * FROM asset_commitment_links WHERE assetId = :assetId")
    suspend fun getCommitmentLinksForAsset(assetId: Long): List<AssetCommitmentLinkEntity>

    @Query("SELECT * FROM asset_identifiers ORDER BY id")
    fun observeIdentifiers(): Flow<List<AssetIdentifierEntity>>

    @Query("SELECT * FROM asset_warranties ORDER BY endDateEpochDay")
    fun observeWarranties(): Flow<List<AssetWarrantyEntity>>

    @Query("SELECT * FROM asset_maintenance_rules WHERE isActive = 1 ORDER BY id")
    fun observeMaintenanceRules(): Flow<List<AssetMaintenanceRuleEntity>>
    @Query("SELECT * FROM asset_maintenance_rules ORDER BY id")
    fun observeAllMaintenanceRules(): Flow<List<AssetMaintenanceRuleEntity>>

    @Query("SELECT * FROM asset_maintenance_events ORDER BY performedDateEpochDay DESC, createdAt DESC")
    fun observeMaintenanceEvents(): Flow<List<AssetMaintenanceEventEntity>>

    @Query("SELECT * FROM asset_checkpoints ORDER BY isActive DESC, id")
    fun observeCheckpoints(): Flow<List<AssetCheckpointEntity>>

    @Query("SELECT * FROM asset_checkpoint_events ORDER BY completedDateEpochDay DESC, id DESC")
    fun observeCheckpointEvents(): Flow<List<AssetCheckpointEventEntity>>

    @Query("SELECT * FROM asset_checkpoints WHERE id = :id LIMIT 1")
    suspend fun getCheckpoint(id: Long): AssetCheckpointEntity?

    @Query("SELECT * FROM asset_checkpoint_events WHERE checkpointId = :id ORDER BY completedDateEpochDay DESC, createdAt DESC LIMIT 1")
    suspend fun latestCheckpointEvent(id: Long): AssetCheckpointEventEntity?

    @Insert suspend fun insertCheckpoint(item: AssetCheckpointEntity): Long
    @Update suspend fun updateCheckpoint(item: AssetCheckpointEntity)
    @Insert suspend fun insertCheckpointEvent(item: AssetCheckpointEventEntity): Long

    @Query("SELECT * FROM asset_documents ORDER BY createdAt DESC")
    fun observeDocuments(): Flow<List<AssetDocumentEntity>>
    @Query("SELECT * FROM asset_maintenance_document_links")
    fun observeMaintenanceDocumentLinks(): Flow<List<AssetMaintenanceDocumentLinkEntity>>
    @Query("SELECT COUNT(*) FROM asset_maintenance_document_links WHERE assetDocumentId = :documentId")
    suspend fun maintenanceLinkCount(documentId: Long): Int

    @Query("SELECT * FROM asset_commitment_links")
    fun observeCommitmentLinks(): Flow<List<AssetCommitmentLinkEntity>>

    @Query("SELECT * FROM asset_transaction_links")
    fun observeTransactionLinks(): Flow<List<AssetTransactionLinkEntity>>

    @Insert suspend fun insertAsset(asset: AssetEntity): Long
    @Update suspend fun updateAsset(asset: AssetEntity)
    @Insert suspend fun insertIdentifiers(items: List<AssetIdentifierEntity>)
    @Query("DELETE FROM asset_identifiers WHERE assetId = :assetId")
    suspend fun deleteIdentifiersForAsset(assetId: Long)
    @Insert suspend fun insertWarranty(item: AssetWarrantyEntity): Long
    @Update suspend fun updateWarranty(item: AssetWarrantyEntity)
    @Insert suspend fun insertRule(item: AssetMaintenanceRuleEntity): Long
    @Update suspend fun updateRule(item: AssetMaintenanceRuleEntity)
    @Query("SELECT * FROM asset_maintenance_rules WHERE id = :id LIMIT 1")
    suspend fun getRule(id: Long): AssetMaintenanceRuleEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvent(item: AssetMaintenanceEventEntity): Long
    @Query("SELECT * FROM asset_maintenance_events WHERE idempotencyKey = :key LIMIT 1")
    suspend fun eventByKey(key: String): AssetMaintenanceEventEntity?
    @Insert suspend fun insertDocument(item: AssetDocumentEntity): Long
    @Insert suspend fun linkMaintenanceDocument(item: AssetMaintenanceDocumentLinkEntity)
    @Query("SELECT * FROM asset_documents WHERE id = :id LIMIT 1") suspend fun getDocument(id: Long): AssetDocumentEntity?
    @Query("DELETE FROM asset_documents WHERE id = :id") suspend fun deleteDocument(id: Long)
    @Query("UPDATE asset_documents SET title = :title WHERE id = :id") suspend fun renameDocument(id: Long, title: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun linkCommitment(item: AssetCommitmentLinkEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun linkTransaction(item: AssetTransactionLinkEntity): Long
    @Query("UPDATE assets SET currentMileageKm = :mileage, mileageUpdatedAt = :now, updatedAt = :now WHERE id = :assetId")
    suspend fun updateMileage(assetId: Long, mileage: Long, now: Long)
    @Query("UPDATE assets SET isArchived = 1, updatedAt = :now WHERE id = :assetId")
    suspend fun archive(assetId: Long, now: Long)
}
