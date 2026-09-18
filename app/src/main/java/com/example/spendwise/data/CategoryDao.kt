package com.example.spendwise.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY id ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Insert
    suspend fun insert(category: CategoryEntity)

    @Query("UPDATE categories SET name = :name WHERE id = :id AND type = 'CUSTOM'")
    suspend fun renameCustom(id: Long, name: String)

    @Query("DELETE FROM categories WHERE id = :id AND type = 'CUSTOM'")
    suspend fun deleteCustom(id: Long)
}
