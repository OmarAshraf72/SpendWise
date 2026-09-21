package com.example.spendwise.suggestion

import com.example.spendwise.data.CategoryEntity
import com.example.spendwise.data.ItemCategoryMappingEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

data class SemanticBatchResult(
    val suggestions: List<CategorySuggestion?>,
    val debugDetails: String
)

interface ConfirmedExampleStore {
    suspend fun getConfirmedExamples(categoryIds: List<Long>): List<ItemCategoryMappingEntity>
}

class LexicalCategorySuggestionEngine : CategorySuggestionEngine {
    override suspend fun suggest(
        itemName: String,
        merchant: String?,
        availableCategories: List<CategoryEntity>
    ): CategorySuggestion? {
        val normalized = ItemNameNormalizer.normalize(itemName)
        val tokens = normalized.split(' ').filter(String::isNotBlank).toSet()
        if (tokens.isEmpty()) return null
        val matches = availableCategories.asSequence()
            .filterNot(CategoryEntity::isArchived)
            .map { category ->
                category to CategorySemanticMetadata.lexicalTermsFor(category.name).count(tokens::contains)
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<CategoryEntity, Int>> { it.second }.thenBy { it.first.name })
            .toList()
        val best = matches.firstOrNull() ?: return null
        if (matches.getOrNull(1)?.second == best.second) return null
        val score = minOf(1.0, 0.75 + (best.second - 1) * 0.1)
        return CategorySuggestion(
            categoryId = best.first.id,
            confidence = score,
            source = CategorySuggestionSource.LEXICAL,
            top1Score = score,
            top2Score = 0.0,
            scoreGap = score,
            diagnostics = CategorySuggestionDiagnostics(
                normalizedItemText = normalized,
                topCandidates = listOf(CategoryCandidate(best.first.id, best.first.name, score)),
                scoreGap = score,
                inferenceMillis = 0L,
                detail = "Unique conservative lexical term match"
            )
        )
    }
}

class SemanticCategorySuggestionEngine(
    private val embedder: SemanticEmbedder,
    private val mappings: ConfirmedExampleStore,
    private val minimumScoreGap: Double = MINIMUM_SCORE_GAP
) : CategorySuggestionEngine {
    private val prototypeMutex = Mutex()
    @Volatile private var prototypeCache: PrototypeCache? = null

    override suspend fun suggest(
        itemName: String,
        merchant: String?,
        availableCategories: List<CategoryEntity>
    ): CategorySuggestion? = suggestBatch(listOf(itemName), availableCategories).suggestions.single()

    suspend fun suggestBatch(
        itemNames: List<String>,
        availableCategories: List<CategoryEntity>
    ): SemanticBatchResult {
        if (itemNames.isEmpty()) return SemanticBatchResult(emptyList(), "No semantic inputs")
        val active = availableCategories.filterNot(CategoryEntity::isArchived)
        if (active.isEmpty()) return SemanticBatchResult(List(itemNames.size) { null }, "No active categories")
        val confirmed = mappings.getConfirmedExamples(active.map(CategoryEntity::id))
        val prototypes = getOrBuildPrototypes(active, confirmed)
        val queryBatch = embedder.embedIndependently(itemNames, SemanticTextRole.QUERY)
        val diagnostics = StringBuilder().apply {
            appendLine("Semantic model")
            appendLine("prototypeCategories=${active.size} independentQueries=${itemNames.size}")
            appendLine("prototypeInferenceMs=${prototypes.buildInferenceMillis} queryInferenceMs=${queryBatch.inferenceMillis}")
        }
        val suggestions = itemNames.indices.map { index ->
            val normalized = ItemNameNormalizer.normalize(itemNames[index])
            val ranked = prototypes.categories.indices.map { categoryIndex ->
                val category = prototypes.categories[categoryIndex]
                CategoryCandidate(
                    categoryId = category.id,
                    categoryName = category.name,
                    score = dot(queryBatch.vectors[index], prototypes.vectors[categoryIndex]).toDouble()
                )
            }.sortedByDescending(CategoryCandidate::score)
            val top1 = ranked[0]
            val top2 = ranked.getOrNull(1)
            val gap = if (top2 == null) 1.0 else top1.score - top2.score
            val accepted = gap + SCORE_EPSILON >= minimumScoreGap
            diagnostics.appendLine("item=$normalized")
            diagnostics.appendLine(
                "top3=${ranked.take(3).joinToString { "${it.categoryName}=${"%.6f".format(it.score)}" }}"
            )
            diagnostics.appendLine("scoreGap=${"%.6f".format(gap)} selected=${if (accepted) top1.categoryName else "ABSTAIN"}")
            if (!accepted) {
                null
            } else {
                CategorySuggestion(
                    categoryId = top1.categoryId,
                    confidence = top1.score,
                    source = CategorySuggestionSource.SEMANTIC_MODEL,
                    top1Score = top1.score,
                    top2Score = top2?.score,
                    scoreGap = gap,
                    diagnostics = CategorySuggestionDiagnostics(
                        normalizedItemText = normalized,
                        topCandidates = ranked.take(3),
                        scoreGap = gap,
                        inferenceMillis = queryBatch.inferenceMillis
                    )
                )
            }
        }
        return SemanticBatchResult(suggestions, diagnostics.toString().trim())
    }

    private suspend fun getOrBuildPrototypes(
        categories: List<CategoryEntity>,
        confirmed: List<ItemCategoryMappingEntity>
    ): PrototypeCache {
        val signature = buildSignature(categories, confirmed)
        prototypeCache?.takeIf { it.signature == signature }?.let { return it }
        return prototypeMutex.withLock {
            prototypeCache?.takeIf { it.signature == signature } ?: buildPrototypes(
                signature,
                categories,
                confirmed
            ).also { prototypeCache = it }
        }
    }

    private suspend fun buildPrototypes(
        signature: String,
        categories: List<CategoryEntity>,
        confirmed: List<ItemCategoryMappingEntity>
    ): PrototypeCache {
        val descriptions = categories.map { CategorySemanticMetadata.descriptionFor(it.name) }
        val descriptionBatch = embedder.embedIndependently(descriptions, SemanticTextRole.PASSAGE)
        val distinctExamples = confirmed.map(ItemCategoryMappingEntity::normalizedItemName).distinct()
        val exampleBatch = embedder.embedIndependently(distinctExamples, SemanticTextRole.PASSAGE)
        val exampleVectors = distinctExamples.zip(exampleBatch.vectors).toMap()
        val groupedExamples = confirmed.groupBy(ItemCategoryMappingEntity::categoryId)
        val vectors = categories.mapIndexed { index, category ->
            val categoryExamples = groupedExamples[category.id].orEmpty()
                .mapNotNull { exampleVectors[it.normalizedItemName] }
            if (categoryExamples.isEmpty()) {
                descriptionBatch.vectors[index].copyOf()
            } else {
                val centroid = centroid(categoryExamples)
                normalize(FloatArray(centroid.size) { dimension ->
                    0.5f * descriptionBatch.vectors[index][dimension] + 0.5f * centroid[dimension]
                })
            }
        }
        return PrototypeCache(
            signature = signature,
            categories = categories,
            vectors = vectors,
            buildInferenceMillis = descriptionBatch.inferenceMillis + exampleBatch.inferenceMillis
        )
    }

    private fun buildSignature(
        categories: List<CategoryEntity>,
        confirmed: List<ItemCategoryMappingEntity>
    ): String = buildString {
        categories.sortedBy(CategoryEntity::id).forEach {
            append(it.id).append('|').append(it.name).append('|').append(it.isArchived).append(';')
        }
        confirmed.sortedWith(compareBy(ItemCategoryMappingEntity::categoryId, ItemCategoryMappingEntity::normalizedItemName))
            .forEach {
                append(it.categoryId).append('|').append(it.normalizedItemName).append('|')
                    .append(it.confirmationCount).append('|').append(it.updatedAt).append(';')
            }
    }

    private fun centroid(vectors: List<FloatArray>): FloatArray {
        val result = FloatArray(vectors.first().size)
        vectors.forEach { vector -> vector.indices.forEach { result[it] += vector[it] } }
        result.indices.forEach { result[it] /= vectors.size }
        return normalize(result)
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.fold(0.0) { sum, value -> sum + value * value }).toFloat()
        if (norm <= 1e-12f) return vector
        vector.indices.forEach { vector[it] /= norm }
        return vector
    }

    private fun dot(left: FloatArray, right: FloatArray): Float {
        var total = 0f
        for (index in left.indices) total += left[index] * right[index]
        return total
    }

    private data class PrototypeCache(
        val signature: String,
        val categories: List<CategoryEntity>,
        val vectors: List<FloatArray>,
        val buildInferenceMillis: Long
    )

    companion object {
        const val MINIMUM_SCORE_GAP = 0.01
        private const val SCORE_EPSILON = 1e-7
    }
}

data class SuggestionPipelineResult(
    val suggestions: List<CategorySuggestion?>,
    val debugDetails: String
)

class CategorySuggestionPipeline(
    private val userLearned: CategorySuggestionEngine,
    private val lexical: CategorySuggestionEngine,
    private val semantic: SemanticCategorySuggestionEngine
) {
    suspend fun suggestAll(
        itemNames: List<String>,
        merchant: String?,
        availableCategories: List<CategoryEntity>
    ): SuggestionPipelineResult {
        val suggestions = MutableList<CategorySuggestion?>(itemNames.size) { null }
        val learnedResults = MutableList<CategorySuggestion?>(itemNames.size) { null }
        val lexicalResults = MutableList<CategorySuggestion?>(itemNames.size) { null }
        val semanticDetails = StringBuilder()
        itemNames.forEachIndexed { index, itemName ->
            learnedResults[index] = userLearned.suggest(itemName, merchant, availableCategories)
            suggestions[index] = learnedResults[index]
            if (suggestions[index] == null) {
                lexicalResults[index] = lexical.suggest(itemName, merchant, availableCategories)
                suggestions[index] = lexicalResults[index]
            }
        }
        val semanticIndices = suggestions.indices.filter { suggestions[it] == null }
        if (semanticIndices.isNotEmpty()) {
            try {
                val result = semantic.suggestBatch(
                    semanticIndices.map(itemNames::get),
                    availableCategories
                )
                semanticIndices.forEachIndexed { resultIndex, originalIndex ->
                    suggestions[originalIndex] = result.suggestions[resultIndex]
                }
                semanticDetails.appendLine(result.debugDetails)
            } catch (error: Throwable) {
                semanticDetails.appendLine("Semantic fallback unavailable: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        val categoryNames = availableCategories.associate { it.id to it.name }
        val details = buildString {
            itemNames.indices.forEach { index ->
                val learned = learnedResults[index]
                val lexicalMatch = lexicalResults[index]
                val final = suggestions[index]
                appendLine("item=${ItemNameNormalizer.normalize(itemNames[index])}")
                appendLine("userLearned=${learned?.let { categoryNames[it.categoryId] } ?: "none"}")
                appendLine(
                    "lexical=${lexicalMatch?.let { "${categoryNames[it.categoryId]} score=${"%.2f".format(it.confidence)}" } ?: "none"}"
                )
                appendLine(
                    "finalSource=${final?.source ?: "none"} finalCategory=${final?.let { categoryNames[it.categoryId] } ?: "none"}"
                )
            }
            if (semanticDetails.isNotBlank()) append(semanticDetails)
        }.trim()
        return SuggestionPipelineResult(suggestions, details)
    }
}
