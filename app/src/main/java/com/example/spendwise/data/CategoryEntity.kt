package com.example.spendwise.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class CategoryType { DEFAULT, CUSTOM }

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: CategoryType,
    val iconName: String,
    val createdAt: Long
)

class CategoryTypeConverter {
    @TypeConverter
    fun fromType(type: CategoryType): String = type.name

    @TypeConverter
    fun toType(value: String): CategoryType = CategoryType.valueOf(value)
}
