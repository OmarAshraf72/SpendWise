package com.example.spendwise.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter

enum class CommitmentType { RENT, INSTALLMENT, DEBT_PAYMENT, SUBSCRIPTION, INSURANCE, SCHOOL_FEES, UTILITIES, CREDIT_CARD, GYM, OTHER }
enum class CommitmentFrequency { ONE_TIME, WEEKLY, MONTHLY, QUARTERLY, SEMI_ANNUAL, ANNUAL, CUSTOM }
enum class CommitmentOccurrenceStatus { UNPAID, PAID, SKIPPED }

@Entity(
    tableName = "financial_commitments",
    foreignKeys = [ForeignKey(
        entity = MerchantEntity::class,
        parentColumns = ["id"], childColumns = ["merchantId"],
        onDelete = ForeignKey.NO_ACTION
    )],
    indices = [Index("merchantId"), Index("nextDueDateEpochDay"), Index("isActive")]
)
data class FinancialCommitmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val amountMinor: Long,
    val type: CommitmentType,
    val frequency: CommitmentFrequency,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long?,
    val nextDueDateEpochDay: Long,
    val isActive: Boolean = true,
    val merchantId: Long?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "commitment_occurrence_overrides",
    foreignKeys = [
        ForeignKey(
            entity = FinancialCommitmentEntity::class,
            parentColumns = ["id"], childColumns = ["commitmentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"], childColumns = ["paidTransactionId"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["commitmentId", "dueDateEpochDay"], unique = true),
        Index("paidTransactionId")
    ]
)
data class CommitmentOccurrenceOverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commitmentId: Long,
    val dueDateEpochDay: Long,
    val status: CommitmentOccurrenceStatus,
    val paidTransactionId: Long?,
    val paidAt: Long?
)

data class CommitmentWithMerchant(
    @Embedded val commitment: FinancialCommitmentEntity,
    @Relation(parentColumn = "merchantId", entityColumn = "id") val merchant: MerchantEntity?
)

class CommitmentConverters {
    @TypeConverter fun fromType(value: CommitmentType): String = value.name
    @TypeConverter fun toType(value: String): CommitmentType = CommitmentType.valueOf(value)
    @TypeConverter fun fromFrequency(value: CommitmentFrequency): String = value.name
    @TypeConverter fun toFrequency(value: String): CommitmentFrequency = CommitmentFrequency.valueOf(value)
    @TypeConverter fun fromStatus(value: CommitmentOccurrenceStatus): String = value.name
    @TypeConverter fun toStatus(value: String): CommitmentOccurrenceStatus = CommitmentOccurrenceStatus.valueOf(value)
}

