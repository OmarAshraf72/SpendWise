package com.example.spendwise.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MerchantDao {
    @Query("SELECT * FROM merchants ORDER BY lastUsedAt DESC, usageCount DESC, displayName ASC")
    fun observeMerchants(): Flow<List<MerchantEntity>>

    @Query("SELECT * FROM merchants WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun findByNormalizedName(normalizedName: String): MerchantEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(merchant: MerchantEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(merchants: List<MerchantEntity>): List<Long>

    @Query(
        """
        UPDATE merchants
        SET displayName = :displayName,
            usageCount = usageCount + 1,
            lastUsedAt = :usedAt,
            defaultCategoryId = :categoryId,
            source = 'USER'
        WHERE id = :id
        """
    )
    suspend fun recordUse(id: Long, displayName: String, categoryId: Long, usedAt: Long)
}
