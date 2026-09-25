package com.example.spendwise.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class AssetType { VEHICLE, PHONE, COMPUTER, TABLET, ELECTRONICS, APPLIANCE, OTHER }
enum class OwnershipStatus { OWNED, SOLD, GIVEN_AWAY, LOST, DISPOSED }
enum class AssetIdentifierType { SERIAL_NUMBER, IMEI, VIN, OTHER }
enum class AssetWarrantyType { MANUFACTURER, SELLER, EXTENDED, OTHER }
enum class MaintenanceTriggerType { TIME, MILEAGE, TIME_OR_MILEAGE }
enum class MaintenanceRuleKind { SERVICE_SCHEDULE, MAINTENANCE_ITEM }
enum class AssetCommitmentRelationType { FINANCING, INSURANCE, OTHER }
enum class AssetTransactionRelationType { PURCHASE, MAINTENANCE, REPAIR, INSURANCE, FUEL, OTHER }
enum class AssetDocumentType { INVOICE, WARRANTY_CARD, PURCHASE_CONTRACT, SERVICE_RECEIPT, INSURANCE, REGISTRATION, OTHER, RECEIPT }

@Entity(tableName = "custom_asset_types", indices = [Index(value = ["normalizedName"], unique = true)])
data class CustomAssetTypeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean = false,
    val createdAt: Long
)

@Entity(
    tableName = "assets",
    foreignKeys = [ForeignKey(entity = MerchantEntity::class, parentColumns = ["id"], childColumns = ["sellerMerchantId"], onDelete = ForeignKey.NO_ACTION)],
    indices = [Index("sellerMerchantId"), Index("type"), Index("isArchived"), Index("categoryId"), Index("customTypeId"), Index("ownershipStatus")]
)
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AssetType,
    val brand: String?,
    val model: String?,
    val purchaseDateEpochDay: Long?,
    val purchasePriceMinor: Long?,
    val sellerMerchantId: Long?,
    val currentMileageKm: Long?,
    val notes: String?,
    val isArchived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val mileageUpdatedAt: Long? = null,
    val customTypeId: Long? = null,
    val categoryId: Long? = null,
    @ColumnInfo(defaultValue = "'OWNED'") val ownershipStatus: OwnershipStatus = OwnershipStatus.OWNED
)

@Entity(
    tableName = "asset_ownership_events",
    foreignKeys = [ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assetId"), Index(value = ["assetId", "createdAt"])]
)
data class AssetOwnershipEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long,
    val status: OwnershipStatus,
    val effectiveDateEpochDay: Long,
    val note: String?,
    val createdAt: Long
)

@Entity(
    tableName = "asset_identifiers",
    foreignKeys = [ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assetId"), Index(value = ["assetId", "type", "value"], unique = true)]
)
data class AssetIdentifierEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long,
    val type: AssetIdentifierType,
    val label: String?,
    val value: String
)

@Entity(
    tableName = "asset_warranties",
    foreignKeys = [ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assetId"), Index("endDateEpochDay")]
)
data class AssetWarrantyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long,
    val name: String,
    val type: AssetWarrantyType,
    val providerName: String?,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long,
    val phone: String?,
    val website: String?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "asset_maintenance_rules",
    foreignKeys = [ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assetId"), Index("isActive")]
)
data class AssetMaintenanceRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long,
    val title: String,
    val triggerType: MaintenanceTriggerType,
    val intervalMonths: Int?,
    val intervalKm: Long?,
    val baselineDateEpochDay: Long?,
    val baselineMileageKm: Long?,
    val warningDays: Int,
    val warningKm: Long,
    val isActive: Boolean = true,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "'MAINTENANCE_ITEM'") val kind: MaintenanceRuleKind = MaintenanceRuleKind.MAINTENANCE_ITEM
)

@Entity(
    tableName = "asset_maintenance_events",
    foreignKeys = [
        ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AssetMaintenanceRuleEntity::class, parentColumns = ["id"], childColumns = ["maintenanceRuleId"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = MerchantEntity::class, parentColumns = ["id"], childColumns = ["serviceMerchantId"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["linkedTransactionId"], onDelete = ForeignKey.NO_ACTION)
    ],
    indices = [Index("assetId"), Index("maintenanceRuleId"), Index("serviceMerchantId"), Index("linkedTransactionId"), Index(value = ["idempotencyKey"], unique = true)]
)
data class AssetMaintenanceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long,
    val maintenanceRuleId: Long?,
    val title: String,
    val performedDateEpochDay: Long,
    val mileageKm: Long?,
    val costMinor: Long?,
    val serviceMerchantId: Long?,
    val providerNameSnapshot: String? = null,
    val linkedTransactionId: Long?,
    val notes: String?,
    val idempotencyKey: String,
    val createdAt: Long
)

@Entity(
    tableName = "asset_documents",
    foreignKeys = [ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assetId"), Index("documentType")]
)
data class AssetDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long,
    val documentType: AssetDocumentType,
    val title: String,
    val storedRelativePath: String,
    val mimeType: String,
    val originalFileName: String,
    val fileSizeBytes: Long,
    val createdAt: Long
)

@Entity(
    tableName = "asset_maintenance_document_links",
    primaryKeys = ["maintenanceEventId", "assetDocumentId"],
    foreignKeys = [
        ForeignKey(entity = AssetMaintenanceEventEntity::class, parentColumns = ["id"], childColumns = ["maintenanceEventId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AssetDocumentEntity::class, parentColumns = ["id"], childColumns = ["assetDocumentId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("assetDocumentId")]
)
data class AssetMaintenanceDocumentLinkEntity(val maintenanceEventId: Long, val assetDocumentId: Long)

@Entity(
    tableName = "asset_commitment_links",
    primaryKeys = ["assetId", "commitmentId", "relationType"],
    foreignKeys = [
        ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FinancialCommitmentEntity::class, parentColumns = ["id"], childColumns = ["commitmentId"], onDelete = ForeignKey.NO_ACTION)
    ],
    indices = [Index("commitmentId")]
)
data class AssetCommitmentLinkEntity(val assetId: Long, val commitmentId: Long, val relationType: AssetCommitmentRelationType)

@Entity(
    tableName = "asset_transaction_links",
    primaryKeys = ["assetId", "transactionId", "relationType"],
    foreignKeys = [
        ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.NO_ACTION)
    ],
    indices = [Index("transactionId")]
)
data class AssetTransactionLinkEntity(val assetId: Long, val transactionId: Long, val relationType: AssetTransactionRelationType)

class AssetConverters {
    @TypeConverter fun assetType(v: AssetType) = v.name
    @TypeConverter fun assetType(v: String) = AssetType.valueOf(v)
    @TypeConverter fun ownershipStatus(v: OwnershipStatus) = v.name
    @TypeConverter fun ownershipStatus(v: String) = OwnershipStatus.valueOf(v)
    @TypeConverter fun identifierType(v: AssetIdentifierType) = v.name
    @TypeConverter fun identifierType(v: String) = AssetIdentifierType.valueOf(v)
    @TypeConverter fun warrantyType(v: AssetWarrantyType) = v.name
    @TypeConverter fun warrantyType(v: String) = AssetWarrantyType.valueOf(v)
    @TypeConverter fun triggerType(v: MaintenanceTriggerType) = v.name
    @TypeConverter fun triggerType(v: String) = MaintenanceTriggerType.valueOf(v)
    @TypeConverter fun ruleKind(v: MaintenanceRuleKind) = v.name
    @TypeConverter fun ruleKind(v: String) = MaintenanceRuleKind.valueOf(v)
    @TypeConverter fun commitmentRelation(v: AssetCommitmentRelationType) = v.name
    @TypeConverter fun commitmentRelation(v: String) = AssetCommitmentRelationType.valueOf(v)
    @TypeConverter fun transactionRelation(v: AssetTransactionRelationType) = v.name
    @TypeConverter fun transactionRelation(v: String) = AssetTransactionRelationType.valueOf(v)
    @TypeConverter fun documentType(v: AssetDocumentType) = v.name
    @TypeConverter fun documentType(v: String) = AssetDocumentType.valueOf(v)
}
