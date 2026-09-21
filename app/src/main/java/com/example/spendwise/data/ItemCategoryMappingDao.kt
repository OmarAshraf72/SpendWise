package com.example.spendwise.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ItemCategoryMappingDao {
    @Query(
        """
        SELECT mappings.* FROM item_category_mappings AS mappings
        INNER JOIN categories ON categories.id = mappings.categoryId
        WHERE mappings.normalizedItemName = :normalizedItemName
          AND mappings.normalizedMerchant = :normalizedMerchant
          AND categories.isArchived = 0
        LIMIT 1
        """
    )
    suspend fun findActiveMerchantMapping(
        normalizedItemName: String,
        normalizedMerchant: String
    ): ItemCategoryMappingEntity?

    @Query(
        """
        SELECT mappings.* FROM item_category_mappings AS mappings
        INNER JOIN categories ON categories.id = mappings.categoryId
        WHERE mappings.normalizedItemName = :normalizedItemName
          AND mappings.normalizedMerchant IS NULL
          AND categories.isArchived = 0
        LIMIT 1
        """
    )
    suspend fun findActiveGenericMapping(normalizedItemName: String): ItemCategoryMappingEntity?

    @Query(
        """
        SELECT * FROM item_category_mappings
        WHERE normalizedItemName = :normalizedItemName
          AND ((:normalizedMerchant IS NULL AND normalizedMerchant IS NULL)
            OR normalizedMerchant = :normalizedMerchant)
        LIMIT 1
        """
    )
    suspend fun findMapping(
        normalizedItemName: String,
        normalizedMerchant: String?
    ): ItemCategoryMappingEntity?

    @Query(
        """
        SELECT mappings.* FROM item_category_mappings AS mappings
        INNER JOIN categories ON categories.id = mappings.categoryId
        WHERE mappings.normalizedMerchant IS NULL
          AND mappings.categoryId IN (:categoryIds)
          AND categories.isArchived = 0
        ORDER BY mappings.categoryId, mappings.updatedAt DESC
        """
    )
    suspend fun getActiveGenericMappingsForCategories(
        categoryIds: List<Long>
    ): List<ItemCategoryMappingEntity>

    @Upsert
    suspend fun upsert(mapping: ItemCategoryMappingEntity)
}
