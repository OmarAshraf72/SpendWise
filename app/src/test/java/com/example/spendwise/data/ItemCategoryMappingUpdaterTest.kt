package com.example.spendwise.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemCategoryMappingUpdaterTest {
    @Test
    fun repeatedConfirmationIncrementsCountAndPreservesCreationTime() {
        val existing = mapping(categoryId = 4L, count = 2, createdAt = 100L, updatedAt = 200L)

        val updated = ItemCategoryMappingUpdater.confirm(
            existing = existing,
            normalizedItemName = existing.normalizedItemName,
            normalizedMerchant = existing.normalizedMerchant,
            categoryId = 4L,
            now = 300L
        )

        assertEquals(existing.id, updated.id)
        assertEquals(3, updated.confirmationCount)
        assertEquals(100L, updated.createdAt)
        assertEquals(300L, updated.updatedAt)
    }

    @Test
    fun changingCategoryMakesLatestChoiceWinAndRestartsConfirmationCount() {
        val existing = mapping(categoryId = 4L, count = 8, createdAt = 100L, updatedAt = 200L)

        val updated = ItemCategoryMappingUpdater.confirm(
            existing = existing,
            normalizedItemName = existing.normalizedItemName,
            normalizedMerchant = existing.normalizedMerchant,
            categoryId = 9L,
            now = 400L
        )

        assertEquals(9L, updated.categoryId)
        assertEquals(1, updated.confirmationCount)
        assertEquals(100L, updated.createdAt)
        assertEquals(400L, updated.updatedAt)
    }

    private fun mapping(
        categoryId: Long,
        count: Int,
        createdAt: Long,
        updatedAt: Long
    ) = ItemCategoryMappingEntity(
        id = 7L,
        normalizedItemName = "panadol extra",
        normalizedMerchant = "pharmacy",
        categoryId = categoryId,
        confirmationCount = count,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
