package com.example.spendwise.data

data class MerchantSuggestion(val merchant: MerchantEntity, val matchKind: MerchantMatchKind)
enum class MerchantMatchKind { EXACT, PREFIX, CONTAINS, FUZZY, RECENT }
data class MerchantSelection(val displayName: String, val categoryId: Long?)

fun shouldOfferNewMerchant(
    query: String,
    suggestions: List<MerchantSuggestion>,
    minimumLength: Int = 3
): Boolean {
    val normalized = normalizeMerchantName(query)
    return normalized.length >= minimumLength && suggestions.none { it.merchant.normalizedName == normalized }
}

fun resolveMerchantSelection(
    suggestion: MerchantSuggestion,
    activeCategoryIds: Set<Long>,
    currentCategoryId: Long?
): MerchantSelection = MerchantSelection(
    displayName = suggestion.merchant.displayName,
    categoryId = suggestion.merchant.defaultCategoryId?.takeIf(activeCategoryIds::contains) ?: currentCategoryId
)

object MerchantRanker {
    fun rank(query: String, merchants: List<MerchantEntity>, limit: Int = 6): List<MerchantSuggestion> {
        val normalizedQuery = normalizeMerchantName(query)
        return merchants.mapNotNull { merchant ->
            val kind = when {
                normalizedQuery.isBlank() -> MerchantMatchKind.RECENT
                merchant.normalizedName == normalizedQuery -> MerchantMatchKind.EXACT
                merchant.normalizedName.startsWith(normalizedQuery) -> MerchantMatchKind.PREFIX
                merchant.normalizedName.contains(normalizedQuery) -> MerchantMatchKind.CONTAINS
                normalizedQuery.length >= 3 && levenshtein(merchant.normalizedName, normalizedQuery) <=
                    maxOf(1, normalizedQuery.length / 3) -> MerchantMatchKind.FUZZY
                else -> return@mapNotNull null
            }
            MerchantSuggestion(merchant, kind)
        }.sortedWith(
            compareBy<MerchantSuggestion> { it.matchKind.ordinal }
                .thenByDescending { it.merchant.usageCount }
                .thenByDescending { it.merchant.lastUsedAt }
                .thenBy { if (it.merchant.source == MerchantSource.SEEDED) 1 else 0 }
                .thenBy { it.merchant.displayName }
        ).take(limit)
    }

    private fun levenshtein(first: String, second: String): Int {
        val previous = IntArray(second.length + 1) { it }
        val current = IntArray(second.length + 1)
        first.forEachIndexed { firstIndex, firstChar ->
            current[0] = firstIndex + 1
            second.forEachIndexed { secondIndex, secondChar ->
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    previous[secondIndex] + if (firstChar == secondChar) 0 else 1
                )
            }
            current.copyInto(previous)
        }
        return previous[second.length]
    }
}
