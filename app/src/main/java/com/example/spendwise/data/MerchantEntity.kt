package com.example.spendwise.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.text.Normalizer

enum class MerchantSource { USER, SEEDED, GLOBAL }

@Entity(
    tableName = "merchants",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["defaultCategoryId"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [Index(value = ["normalizedName"], unique = true), Index("defaultCategoryId")]
)
data class MerchantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val normalizedName: String,
    val usageCount: Int,
    val lastUsedAt: Long,
    val createdAt: Long,
    val defaultCategoryId: Long?,
    val source: MerchantSource
)

class MerchantSourceConverter {
    @TypeConverter fun fromMerchantSource(source: MerchantSource): String = source.name
    @TypeConverter fun toMerchantSource(value: String): MerchantSource = MerchantSource.valueOf(value)
}

fun normalizeMerchantName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .trim()
    .lowercase()
    .replace(Regex("[أإآٱ]"), "ا")
    .replace('ى', 'ي')
    .replace('ـ', ' ')
    .replace(Regex("\\s+"), " ")

