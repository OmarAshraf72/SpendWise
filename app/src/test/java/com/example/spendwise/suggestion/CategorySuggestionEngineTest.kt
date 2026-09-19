package com.example.spendwise.suggestion

import com.example.spendwise.data.CategoryEntity
import com.example.spendwise.data.CategoryType
import com.example.spendwise.data.ItemCategoryMappingEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategorySuggestionEngineTest {
    private val medicine = category(1, "Medicine")
    private val restaurants = category(2, "Restaurants")

    @Test
    fun exactMerchantSpecificMatchUsesVeryHighDeterministicConfidence() = runBlocking {
        val store = FakeMappingStore(
            merchantMappings = listOf(mapping("cappuccino", "starbucks", restaurants.id))
        )
        val engine = UserLearnedCategorySuggestionEngine(store)

        val suggestion = engine.suggest("Cappuccino", " Starbucks ", listOf(medicine, restaurants))

        assertEquals(restaurants.id, suggestion?.categoryId)
        assertEquals(0.98, suggestion?.confidence ?: 0.0, 0.0)
        assertEquals(CategorySuggestionSource.USER_LEARNED, suggestion?.source)
    }

    @Test
    fun genericItemMatchIsUsedWithoutMerchantSpecificMapping() = runBlocking {
        val store = FakeMappingStore(genericMappings = listOf(mapping("panadol extra", null, medicine.id)))
        val engine = UserLearnedCategorySuggestionEngine(store)

        val suggestion = engine.suggest("PANADOL-EXTRA", "Any Pharmacy", listOf(medicine, restaurants))

        assertEquals(medicine.id, suggestion?.categoryId)
        assertEquals(0.90, suggestion?.confidence ?: 0.0, 0.0)
    }

    @Test
    fun merchantSpecificMappingWinsOverGenericMapping() = runBlocking {
        val store = FakeMappingStore(
            merchantMappings = listOf(mapping("cappuccino", "starbucks", restaurants.id)),
            genericMappings = listOf(mapping("cappuccino", null, medicine.id))
        )
        val engine = UserLearnedCategorySuggestionEngine(store)

        val suggestion = engine.suggest("Cappuccino", "Starbucks", listOf(medicine, restaurants))

        assertEquals(restaurants.id, suggestion?.categoryId)
    }

    @Test
    fun archivedCategoryIsIgnored() = runBlocking {
        val archivedMedicine = medicine.copy(isArchived = true)
        val store = FakeMappingStore(genericMappings = listOf(mapping("panadol", null, medicine.id)))
        val engine = UserLearnedCategorySuggestionEngine(store)

        val suggestion = engine.suggest("Panadol", null, listOf(archivedMedicine, restaurants))

        assertNull(suggestion)
    }

    @Test
    fun noMappingReturnsNoSuggestion() = runBlocking {
        val engine = UserLearnedCategorySuggestionEngine(FakeMappingStore())

        assertNull(engine.suggest("Unknown item", "Shop", listOf(medicine, restaurants)))
    }

    private fun category(id: Long, name: String) = CategoryEntity(
        id = id,
        name = name,
        type = CategoryType.DEFAULT,
        iconName = "category",
        createdAt = 1L
    )

    private fun mapping(item: String, merchant: String?, categoryId: Long) = ItemCategoryMappingEntity(
        id = (item + merchant + categoryId).hashCode().toLong(),
        normalizedItemName = item,
        normalizedMerchant = merchant,
        categoryId = categoryId,
        confirmationCount = 1,
        createdAt = 1L,
        updatedAt = 1L
    )

    private class FakeMappingStore(
        private val merchantMappings: List<ItemCategoryMappingEntity> = emptyList(),
        private val genericMappings: List<ItemCategoryMappingEntity> = emptyList()
    ) : ItemCategoryMappingStore {
        override suspend fun findMerchantMapping(
            normalizedItemName: String,
            normalizedMerchant: String
        ): ItemCategoryMappingEntity? = merchantMappings.firstOrNull {
            it.normalizedItemName == normalizedItemName && it.normalizedMerchant == normalizedMerchant
        }

        override suspend fun findGenericMapping(
            normalizedItemName: String
        ): ItemCategoryMappingEntity? = genericMappings.firstOrNull {
            it.normalizedItemName == normalizedItemName && it.normalizedMerchant == null
        }
    }
}
