package com.example.spendwise.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSelectorLogicTest {
    private val categories = listOf(
        category(1, "Groceries"),
        category(2, "Medicine"),
        category(3, "Personal Care"),
        category(4, "My Custom", CategoryType.CUSTOM),
        category(5, "Archived", CategoryType.CUSTOM, true),
        category(6, "Fruits & Vegetables")
    )

    @Test fun emptyCategoryQueryRanksMerchantSuggestionThenUsageOrderAndLimitsResults() {
        val result = rankCategories("", categories, suggestedCategoryId = 3, limit = 5)
        assertEquals(3L, result.first().category.id)
        assertEquals(5, result.size)
        assertFalse(result.any { it.category.isArchived })
    }

    @Test fun categorySearchSupportsPrefixContainsAndCustomCategories() {
        assertEquals("Groceries", rankCategories("gro", categories).single().category.name)
        assertEquals("Medicine", rankCategories("med", categories).single().category.name)
        assertEquals("Personal Care", rankCategories("personal", categories).single().category.name)
        assertEquals("My Custom", rankCategories("custom", categories).single().category.name)
        assertTrue(rankCategories("arch", categories).isEmpty())
    }

    @Test fun merchantAutocompleteForCommitmentsUsesSharedRankingAndNoDuplicateAdd() {
        val merchants = listOf(merchant(1, "Carrefour", 4), merchant(2, "Carrefour Market", 1))
        val results = MerchantRanker.rank("car", merchants)
        assertEquals(listOf("Carrefour", "Carrefour Market"), results.map { it.merchant.displayName })
        assertTrue(shouldOfferNewMerchant("Carr", results))
        assertFalse(shouldOfferNewMerchant("Carrefour", MerchantRanker.rank("Carrefour", merchants)))
    }

    @Test fun newMerchantIsPersistedOnceAndExistingMerchantIsPreserved() = runBlocking {
        val dao = SelectorFakeMerchantDao()
        val repository = MerchantRepository(dao)
        val first = repository.createIfMissing("New Merchant", 100)
        val second = repository.createIfMissing(" new   merchant ", 200)
        assertNotNull(first)
        assertEquals(first?.id, second?.id)
        assertEquals(1, dao.rows.size)
        assertEquals(0, dao.rows.single().usageCount)
    }

    private fun category(id: Long, name: String, type: CategoryType = CategoryType.DEFAULT, archived: Boolean = false) =
        CategoryEntity(id, name, type, "category", 1, archived)

    private fun merchant(id: Long, name: String, uses: Int) = MerchantEntity(
        id, name, normalizeMerchantName(name), uses, uses.toLong(), 1, null, MerchantSource.USER
    )
}

private class SelectorFakeMerchantDao : MerchantDao {
    val rows = mutableListOf<MerchantEntity>()
    private var nextId = 1L
    override fun observeMerchants(): Flow<List<MerchantEntity>> = flowOf(rows)
    override suspend fun findByNormalizedName(normalizedName: String) = rows.firstOrNull { it.normalizedName == normalizedName }
    override suspend fun insert(merchant: MerchantEntity): Long {
        if (findByNormalizedName(merchant.normalizedName) != null) return -1
        val id = nextId++
        rows += merchant.copy(id = id)
        return id
    }
    override suspend fun insertAll(merchants: List<MerchantEntity>) = merchants.map { insert(it) }
    override suspend fun recordUse(id: Long, displayName: String, categoryId: Long, usedAt: Long) = Unit
}
