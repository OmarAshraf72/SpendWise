package com.example.spendwise.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY id ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY id ASC")
    suspend fun getActiveCategories(): List<CategoryEntity>

    @Query(
        """
        SELECT categories.* FROM categories
        LEFT JOIN transactions ON transactions.categoryId = categories.id AND transactions.type = 'EXPENSE'
        WHERE categories.isArchived = 0
        GROUP BY categories.id
        ORDER BY COUNT(transactions.id) DESC, MAX(transactions.transactionDate) DESC, categories.id ASC
        """
    )
    fun observeCategoriesForEntry(): Flow<List<CategoryEntity>>

    @Insert
    suspend fun insert(category: CategoryEntity)

    @Query("UPDATE categories SET name = :name WHERE id = :id AND type = 'CUSTOM' AND isArchived = 0")
    suspend fun renameCustom(id: Long, name: String)

    @Query("UPDATE categories SET isArchived = 1 WHERE id = :id AND type = 'CUSTOM' AND isArchived = 0")
    suspend fun archiveCustom(id: Long)
}
