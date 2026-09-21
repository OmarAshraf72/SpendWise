package com.example.spendwise.data

data class CategorySuggestion(val category: CategoryEntity, val matchRank: Int)

fun rankCategories(
    query: String,
    categoriesInUsageOrder: List<CategoryEntity>,
    suggestedCategoryId: Long? = null,
    limit: Int = 5
): List<CategorySuggestion> {
    val normalizedQuery = normalizeSearchText(query)
    return categoriesInUsageOrder.asSequence()
        .filterNot { it.isArchived }
        .mapIndexedNotNull { usageIndex, category ->
            val normalizedName = normalizeSearchText(category.name)
            val matchRank = when {
                normalizedQuery.isBlank() && category.id == suggestedCategoryId -> 0
                normalizedQuery.isBlank() -> 1
                normalizedName == normalizedQuery -> 0
                normalizedName.startsWith(normalizedQuery) -> 1
                normalizedName.contains(normalizedQuery) -> 2
                else -> return@mapIndexedNotNull null
            }
            Triple(CategorySuggestion(category, matchRank), usageIndex, category.id != suggestedCategoryId)
        }
        .sortedWith(compareBy<Triple<CategorySuggestion, Int, Boolean>> { it.first.matchRank }
            .thenBy { it.third }
            .thenBy { it.second })
        .take(limit)
        .map { it.first }
        .toList()
}

private fun normalizeSearchText(value: String): String = normalizeMerchantName(value)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")
