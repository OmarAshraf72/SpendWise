package com.example.spendwise.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter

enum class TransactionType { EXPENSE, INCOME }

enum class TransactionSource { MANUAL, RECEIPT, BANK_NOTIFICATION, SMS }

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.NO_ACTION
        ),
        ForeignKey(
            entity = MerchantEntity::class,
            parentColumns = ["id"],
            childColumns = ["merchantId"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index("categoryId"), Index("merchantId"), Index("transactionDate"),
        Index("receiptGroupId"), Index("purchaseGroupId")
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TransactionType,
    val amountMinor: Long,
    val categoryId: Long?,
    val merchant: String?,
    val note: String?,
    val transactionDate: Long,
    val source: TransactionSource,
    val createdAt: Long,
    val receiptGroupId: String? = null,
    val merchantId: Long? = null,
    val purchaseGroupId: String? = null
)

data class TransactionWithCategory(
    @Embedded val transaction: TransactionEntity,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: CategoryEntity?
)

class TransactionConverters {
    @TypeConverter
    fun fromTransactionType(type: TransactionType): String = type.name

    @TypeConverter
    fun toTransactionType(value: String): TransactionType = TransactionType.valueOf(value)

    @TypeConverter
    fun fromTransactionSource(source: TransactionSource): String = source.name

    @TypeConverter
    fun toTransactionSource(value: String): TransactionSource = TransactionSource.valueOf(value)
}
