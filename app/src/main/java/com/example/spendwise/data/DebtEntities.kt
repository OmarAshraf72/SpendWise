package com.example.spendwise.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class RepaymentMode { OPEN_ENDED, FIXED_INSTALLMENTS }
enum class FinancingType { FIXED_TOTAL, USER_PROVIDED_RATE, UNKNOWN_DETAILS }
enum class RatePeriod { ANNUAL, MONTHLY }
enum class RateBasis { ORIGINAL_PRINCIPAL, OUTSTANDING_BALANCE }
enum class FinancingCalculationMethod { SIMPLE, COMPOUND, CONTRACT_DEFINED }
enum class LateChargeType { FIXED_ONCE, PERCENT_ONCE, FIXED_PERIODIC, PERCENT_PERIODIC }
enum class LateChargeInterval { ONCE, DAILY, WEEKLY, MONTHLY }
enum class LateChargeBasis { OVERDUE_INSTALLMENT, OUTSTANDING_PRINCIPAL }

@Entity(
    tableName = "debt_profiles",
    foreignKeys = [ForeignKey(
        entity = FinancialCommitmentEntity::class,
        parentColumns = ["id"], childColumns = ["commitmentId"],
        onDelete = ForeignKey.NO_ACTION
    )],
    indices = [Index(value = ["commitmentId"], unique = true), Index("repaymentMode"), Index("isArchived")]
)
data class DebtProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val commitmentId: Long,
    val creditorName: String?,
    val originalPrincipalMinor: Long,
    val startDateEpochDay: Long,
    val expectedEndDateEpochDay: Long?,
    val repaymentMode: RepaymentMode,
    val notes: String?,
    @ColumnInfo(defaultValue = "0") val isArchived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "debt_payments",
    foreignKeys = [
        ForeignKey(entity = DebtProfileEntity::class, parentColumns = ["id"], childColumns = ["debtProfileId"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["linkedTransactionId"], onDelete = ForeignKey.NO_ACTION)
    ],
    indices = [Index("debtProfileId"), Index("occurrenceDueDateEpochDay"), Index("linkedTransactionId"), Index(value = ["idempotencyKey"], unique = true)]
)
data class DebtPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val debtProfileId: Long,
    val occurrenceDueDateEpochDay: Long?,
    val paymentDateEpochDay: Long,
    val totalPaidMinor: Long,
    val principalPaidMinor: Long,
    val financingCostPaidMinor: Long,
    val lateChargePaidMinor: Long,
    val linkedTransactionId: Long?,
    val note: String?,
    val idempotencyKey: String,
    val createdAt: Long
)

@Entity(
    tableName = "financing_terms",
    foreignKeys = [ForeignKey(entity = DebtProfileEntity::class, parentColumns = ["id"], childColumns = ["debtProfileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["debtProfileId"], unique = true)]
)
data class FinancingTermsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val debtProfileId: Long,
    val financingType: FinancingType,
    val totalRepayableMinor: Long?,
    val rateBasisPoints: Long?,
    val ratePeriod: RatePeriod?,
    val rateBasis: RateBasis?,
    val calculationMethod: FinancingCalculationMethod?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "late_payment_rules",
    foreignKeys = [ForeignKey(entity = DebtProfileEntity::class, parentColumns = ["id"], childColumns = ["debtProfileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["debtProfileId"], unique = true)]
)
data class LatePaymentRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val debtProfileId: Long,
    val gracePeriodDays: Int,
    val chargeType: LateChargeType,
    val fixedChargeMinor: Long?,
    val rateBasisPoints: Long?,
    val chargeInterval: LateChargeInterval,
    val chargeBasis: LateChargeBasis,
    val minimumChargeMinor: Long?,
    val maximumChargeMinor: Long?,
    @ColumnInfo(defaultValue = "0") val isCompounding: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

class DebtConverters {
    @TypeConverter fun fromRepaymentMode(v: RepaymentMode) = v.name
    @TypeConverter fun toRepaymentMode(v: String) = RepaymentMode.valueOf(v)
    @TypeConverter fun fromFinancingType(v: FinancingType) = v.name
    @TypeConverter fun toFinancingType(v: String) = FinancingType.valueOf(v)
    @TypeConverter fun fromRatePeriod(v: RatePeriod?) = v?.name
    @TypeConverter fun toRatePeriod(v: String?) = v?.let(RatePeriod::valueOf)
    @TypeConverter fun fromRateBasis(v: RateBasis?) = v?.name
    @TypeConverter fun toRateBasis(v: String?) = v?.let(RateBasis::valueOf)
    @TypeConverter fun fromCalculation(v: FinancingCalculationMethod?) = v?.name
    @TypeConverter fun toCalculation(v: String?) = v?.let(FinancingCalculationMethod::valueOf)
    @TypeConverter fun fromChargeType(v: LateChargeType) = v.name
    @TypeConverter fun toChargeType(v: String) = LateChargeType.valueOf(v)
    @TypeConverter fun fromChargeInterval(v: LateChargeInterval) = v.name
    @TypeConverter fun toChargeInterval(v: String) = LateChargeInterval.valueOf(v)
    @TypeConverter fun fromChargeBasis(v: LateChargeBasis) = v.name
    @TypeConverter fun toChargeBasis(v: String) = LateChargeBasis.valueOf(v)
}
