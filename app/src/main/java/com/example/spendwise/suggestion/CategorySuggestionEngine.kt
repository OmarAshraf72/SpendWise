package com.example.spendwise.suggestion

import com.example.spendwise.data.CategoryEntity
import com.example.spendwise.data.ItemCategoryMappingEntity

enum class CategorySuggestionSource { USER_LEARNED, LEXICAL, SEMANTIC_MODEL }

data class CategoryCandidate(
    val categoryId: Long,
    val categoryName: String,
    val score: Double
)

data class CategorySuggestionDiagnostics(
    val normalizedItemText: String,
    val topCandidates: List<CategoryCandidate>,
    val scoreGap: Double?,
    val inferenceMillis: Long,
    val detail: String? = null
)

data class CategorySuggestion(
    val categoryId: Long,
    val confidence: Double,
    val source: CategorySuggestionSource,
    val top1Score: Double? = null,
    val top2Score: Double? = null,
    val scoreGap: Double? = null,
    val diagnostics: CategorySuggestionDiagnostics? = null
)

interface CategorySuggestionEngine {
    suspend fun suggest(
        itemName: String,
        merchant: String?,
        availableCategories: List<CategoryEntity>
    ): CategorySuggestion?
}

interface ItemCategoryMappingStore {
    suspend fun findMerchantMapping(
        normalizedItemName: String,
        normalizedMerchant: String
    ): ItemCategoryMappingEntity?

    suspend fun findGenericMapping(normalizedItemName: String): ItemCategoryMappingEntity?
}

class UserLearnedCategorySuggestionEngine(
    private val mappings: ItemCategoryMappingStore
) : CategorySuggestionEngine {
    override suspend fun suggest(
        itemName: String,
        merchant: String?,
        availableCategories: List<CategoryEntity>
    ): CategorySuggestion? {
        val normalizedItem = ItemNameNormalizer.normalize(itemName)
        if (normalizedItem.isBlank()) return null
        val activeCategoryIds = availableCategories.asSequence()
            .filterNot(CategoryEntity::isArchived)
            .map(CategoryEntity::id)
            .toSet()
        if (activeCategoryIds.isEmpty()) return null

        val normalizedMerchant = ItemNameNormalizer.normalizeNullable(merchant)
        if (normalizedMerchant != null) {
            val merchantMapping = mappings.findMerchantMapping(normalizedItem, normalizedMerchant)
            if (merchantMapping != null && merchantMapping.categoryId in activeCategoryIds) {
                return CategorySuggestion(
                    categoryId = merchantMapping.categoryId,
                    confidence = MERCHANT_EXACT_CONFIDENCE,
                    source = CategorySuggestionSource.USER_LEARNED
                )
            }
        }
        val genericMapping = mappings.findGenericMapping(normalizedItem)
        return genericMapping?.takeIf { it.categoryId in activeCategoryIds }?.let { mapping ->
            CategorySuggestion(
                categoryId = mapping.categoryId,
                confidence = GENERIC_EXACT_CONFIDENCE,
                source = CategorySuggestionSource.USER_LEARNED
            )
        }
    }

    private companion object {
        const val MERCHANT_EXACT_CONFIDENCE = 0.98
        const val GENERIC_EXACT_CONFIDENCE = 0.90
    }
}
