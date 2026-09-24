package com.example.spendwise.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class AssetCheckpointType { RENEWAL, INSPECTION, SERVICE, ADMINISTRATIVE, CUSTOM }
enum class CheckpointTriggerMode { DATE, MILEAGE, DATE_OR_MILEAGE }
enum class AssetAttentionSource { WARRANTY, MAINTENANCE, CHECKPOINT }
enum class AssetReminderTriggerKind { DAYS_BEFORE, KM_BEFORE, ON_DUE }

@Entity(
    tableName = "asset_checkpoints",
    foreignKeys = [ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assetId"), Index("isActive")]
)
data class AssetCheckpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long,
    val title: String,
    val checkpointType: AssetCheckpointType,
    val triggerMode: CheckpointTriggerMode,
    val dueDateEpochDay: Long?,
    val dueMileageKm: Long?,
    val repeatMonths: Int?,
    val repeatKm: Long?,
    val warningDays: Int?,
    val warningKm: Long?,
    val notes: String?,
    val isActive: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val rescheduledDueDateEpochDay: Long? = null,
    val rescheduledDueMileageKm: Long? = null
)

@Entity(
    tableName = "asset_checkpoint_events",
    foreignKeys = [ForeignKey(entity = AssetCheckpointEntity::class, parentColumns = ["id"], childColumns = ["checkpointId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("checkpointId")]
)
data class AssetCheckpointEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val checkpointId: Long,
    val completedDateEpochDay: Long,
    val completedMileageKm: Long?,
    val note: String?,
    val createdAt: Long,
    val dueDateEpochDay: Long? = null,
    val dueMileageKm: Long? = null
)

@Entity(
    tableName = "asset_reminder_rules",
    foreignKeys = [ForeignKey(entity = AssetEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assetId"), Index(value = ["sourceType", "sourceId", "triggerKind", "leadValue"], unique = true)]
)
data class AssetReminderRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long,
    val sourceType: AssetAttentionSource,
    val sourceId: Long,
    val triggerKind: AssetReminderTriggerKind,
    val leadValue: Long,
    val isEnabled: Boolean = true
)

@Entity(
    tableName = "asset_notification_deliveries",
    indices = [Index(value = ["ruleId", "occurrenceKey"], unique = true)]
)
data class AssetNotificationDeliveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val occurrenceKey: String,
    val deliveredAt: Long
)

class AssetReminderConverters {
    @TypeConverter fun checkpointType(value: AssetCheckpointType) = value.name
    @TypeConverter fun checkpointType(value: String) = AssetCheckpointType.valueOf(value)
    @TypeConverter fun checkpointTrigger(value: CheckpointTriggerMode) = value.name
    @TypeConverter fun checkpointTrigger(value: String) = CheckpointTriggerMode.valueOf(value)
    @TypeConverter fun attentionSource(value: AssetAttentionSource) = value.name
    @TypeConverter fun attentionSource(value: String) = AssetAttentionSource.valueOf(value)
    @TypeConverter fun reminderTrigger(value: AssetReminderTriggerKind) = value.name
    @TypeConverter fun reminderTrigger(value: String) = AssetReminderTriggerKind.valueOf(value)
}
