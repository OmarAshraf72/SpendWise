package com.example.spendwise.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "item_category_mappings",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["normalizedItemName", "normalizedMerchant"], unique = true),
        Index(value = ["categoryId"])
    ]
)
data class ItemCategoryMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedItemName: String,
    val normalizedMerchant: String?,
    val categoryId: Long,
    val confirmationCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)
