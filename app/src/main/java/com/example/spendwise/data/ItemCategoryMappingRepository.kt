package com.example.spendwise.data

import com.example.spendwise.suggestion.ItemNameNormalizer
import com.example.spendwise.suggestion.ItemCategoryMappingStore
import com.example.spendwise.suggestion.ConfirmedExampleStore

class ItemCategoryMappingRepository(
    private val dao: ItemCategoryMappingDao
) : ItemCategoryMappingStore, ConfirmedExampleStore {
    override suspend fun findMerchantMapping(
        normalizedItemName: String,
        normalizedMerchant: String
    ): ItemCategoryMappingEntity? = dao.findActiveMerchantMapping(normalizedItemName, normalizedMerchant)

    override suspend fun findGenericMapping(
        normalizedItemName: String
    ): ItemCategoryMappingEntity? = dao.findActiveGenericMapping(normalizedItemName)

    override suspend fun getConfirmedExamples(
        categoryIds: List<Long>
    ): List<ItemCategoryMappingEntity> = if (categoryIds.isEmpty()) {
        emptyList()
    } else {
        dao.getActiveGenericMappingsForCategories(categoryIds)
    }

    suspend fun confirmSelection(
        itemName: String,
        merchant: String?,
        categoryId: Long,
        now: Long = System.currentTimeMillis()
    ) {
        val normalizedItem = ItemNameNormalizer.normalize(itemName)
        if (normalizedItem.isBlank()) return
        confirmNormalized(normalizedItem, normalizedMerchant = null, categoryId, now)
        val normalizedMerchant = ItemNameNormalizer.normalizeNullable(merchant)
        if (normalizedMerchant != null) {
            confirmNormalized(normalizedItem, normalizedMerchant, categoryId, now)
        }
    }

    private suspend fun confirmNormalized(
        normalizedItemName: String,
        normalizedMerchant: String?,
        categoryId: Long,
        now: Long
    ) {
        val existing = dao.findMapping(normalizedItemName, normalizedMerchant)
        dao.upsert(
            ItemCategoryMappingUpdater.confirm(
                existing = existing,
                normalizedItemName = normalizedItemName,
                normalizedMerchant = normalizedMerchant,
                categoryId = categoryId,
                now = now
            )
        )
    }
}

object ItemCategoryMappingUpdater {
    fun confirm(
        existing: ItemCategoryMappingEntity?,
        normalizedItemName: String,
        normalizedMerchant: String?,
        categoryId: Long,
        now: Long
    ): ItemCategoryMappingEntity = when {
        existing == null -> ItemCategoryMappingEntity(
            normalizedItemName = normalizedItemName,
            normalizedMerchant = normalizedMerchant,
            categoryId = categoryId,
            confirmationCount = 1,
            createdAt = now,
            updatedAt = now
        )
        existing.categoryId == categoryId -> existing.copy(
            confirmationCount = existing.confirmationCount + 1,
            updatedAt = now
        )
        else -> existing.copy(
            categoryId = categoryId,
            confirmationCount = 1,
            updatedAt = now
        )
    }
}
