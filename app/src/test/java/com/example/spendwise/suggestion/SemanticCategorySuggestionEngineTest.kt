package com.example.spendwise.suggestion

import com.example.spendwise.data.CategoryEntity
import com.example.spendwise.data.CategoryType
import com.example.spendwise.data.ItemCategoryMappingEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticCategorySuggestionEngineTest {
    private val medicine = category(1, "Medicine")
    private val books = category(2, "Books", CategoryType.CUSTOM)
    private val produce = category(3, "Fruits & Vegetables")

    @Test
    fun semanticGapAtThresholdIsAccepted() = runBlocking {
        val engine = SemanticCategorySuggestionEngine(
            embedder = FixedEmbedder(queryVector = floatArrayOf(1f, 0f)),
            mappings = FakeExamples(),
            minimumScoreGap = 0.01
        )

        val suggestion = engine.suggest("unknown product", null, listOf(medicine, books))

        assertEquals(medicine.id, suggestion?.categoryId)
        assertEquals(CategorySuggestionSource.SEMANTIC_MODEL, suggestion?.source)
    }

    @Test
    fun semanticGapBelowThresholdAbstains() = runBlocking {
        val engine = SemanticCategorySuggestionEngine(
            embedder = FixedEmbedder(queryVector = floatArrayOf(0.71f, 0.70f)),
            mappings = FakeExamples(),
            minimumScoreGap = 0.02
        )

        assertNull(engine.suggest("ambiguous product", null, listOf(medicine, books)))
    }

    @Test
    fun confirmedExamplesUseFiftyFiftyNormalizedHybridPrototype() = runBlocking {
        val confirmedExample = ItemCategoryMappingEntity(
            id = 1,
            normalizedItemName = "user confirmed example",
            normalizedMerchant = null,
            categoryId = medicine.id,
            confirmationCount = 1,
            createdAt = 1,
            updatedAt = 1
        )
        val engine = SemanticCategorySuggestionEngine(
            embedder = FixedEmbedder(queryVector = floatArrayOf(1f, 0f)),
            mappings = FakeExamples(listOf(confirmedExample)),
            minimumScoreGap = 0.01
        )

        val suggestion = engine.suggest("unseen medicine", null, listOf(medicine, books))

        assertEquals(medicine.id, suggestion?.categoryId)
        assertEquals(1.0 / kotlin.math.sqrt(2.0), suggestion?.top1Score ?: 0.0, 1e-6)
    }

    @Test
    fun conservativeLexicalMatchUsesLexicalSource() = runBlocking {
        val suggestion = LexicalCategorySuggestionEngine().suggest(
            "Panadol Extra",
            null,
            listOf(medicine, books)
        )

        assertEquals(medicine.id, suggestion?.categoryId)
        assertEquals(CategorySuggestionSource.LEXICAL, suggestion?.source)
    }

    @Test
    fun userLearnedSuggestionWinsWithoutCallingSemanticModel() = runBlocking {
        val learned = object : CategorySuggestionEngine {
            override suspend fun suggest(
                itemName: String,
                merchant: String?,
                availableCategories: List<CategoryEntity>
            ) = CategorySuggestion(medicine.id, 0.98, CategorySuggestionSource.USER_LEARNED)
        }
        val semantic = SemanticCategorySuggestionEngine(
            embedder = ThrowingEmbedder(),
            mappings = FakeExamples()
        )
        val pipeline = CategorySuggestionPipeline(learned, LexicalCategorySuggestionEngine(), semantic)

        val result = pipeline.suggestAll(listOf("Previously confirmed item"), null, listOf(medicine, books))

        assertEquals(CategorySuggestionSource.USER_LEARNED, result.suggestions.single()?.source)
    }

    @Test
    fun semanticFailureFallsBackToManualSelection() = runBlocking {
        val noMatch = object : CategorySuggestionEngine {
            override suspend fun suggest(
                itemName: String,
                merchant: String?,
                availableCategories: List<CategoryEntity>
            ): CategorySuggestion? = null
        }
        val pipeline = CategorySuggestionPipeline(
            noMatch,
            noMatch,
            SemanticCategorySuggestionEngine(ThrowingEmbedder(), FakeExamples())
        )

        val result = pipeline.suggestAll(listOf("Unknown"), null, listOf(medicine, books))

        assertNull(result.suggestions.single())
        assertTrue(result.debugDetails.contains("Semantic fallback unavailable"))
    }

    @Test
    fun productionEmbedsEveryQueryDescriptionAndExampleIndependently() = runBlocking {
        val example = ItemCategoryMappingEntity(
            id = 1,
            normalizedItemName = "confirmed medicine",
            normalizedMerchant = null,
            categoryId = medicine.id,
            confirmationCount = 1,
            createdAt = 1,
            updatedAt = 1
        )
        val embedder = RecordingEmbedder()
        val engine = SemanticCategorySuggestionEngine(
            embedder = embedder,
            mappings = FakeExamples(listOf(example)),
            minimumScoreGap = 0.01
        )

        engine.suggestBatch(listOf("first item", "second item"), listOf(medicine, books))

        assertTrue("Every ONNX call must contain exactly one text: ${embedder.calls}", embedder.calls.all { it.second == 1 })
        assertEquals(5, embedder.calls.size) // 2 descriptions + 1 example + 2 queries
        assertEquals(3, embedder.calls.count { it.first == SemanticTextRole.PASSAGE })
        assertEquals(2, embedder.calls.count { it.first == SemanticTextRole.QUERY })
    }

    @Test
    fun commonArabicProduceUsesSafeLexicalSuggestion() = runBlocking {
        val engine = LexicalCategorySuggestionEngine()

        listOf("برقوق", "جوافة", "خيار", "رمان", "تفاح أصفر", "تين").forEach { item ->
            val suggestion = engine.suggest(item, "فواكه و خضروات إسلام و ميدو", listOf(medicine, produce))

            assertEquals(item, produce.id, suggestion?.categoryId)
            assertEquals(item, CategorySuggestionSource.LEXICAL, suggestion?.source)
            assertEquals(item, 0.75, suggestion?.confidence ?: 0.0, 0.0)
        }
    }

    private fun category(id: Long, name: String, type: CategoryType = CategoryType.DEFAULT) = CategoryEntity(
        id = id,
        name = name,
        type = type,
        iconName = "category",
        createdAt = 1L
    )

    private class FakeExamples(
        private val examples: List<ItemCategoryMappingEntity> = emptyList()
    ) : ConfirmedExampleStore {
        override suspend fun getConfirmedExamples(categoryIds: List<Long>): List<ItemCategoryMappingEntity> = examples
    }

    private class FixedEmbedder(private val queryVector: FloatArray) : SemanticEmbedder {
        override suspend fun embed(texts: List<String>, role: SemanticTextRole): EmbeddingBatch {
            val vectors = when (role) {
                SemanticTextRole.QUERY -> texts.map { queryVector.copyOf() }
                SemanticTextRole.PASSAGE -> texts.map { text ->
                    if (text.contains("medicines")) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
                }
            }
            return EmbeddingBatch(vectors, 0L)
        }
    }

    private class ThrowingEmbedder : SemanticEmbedder {
        override suspend fun embed(texts: List<String>, role: SemanticTextRole): EmbeddingBatch {
            error("Model unavailable")
        }
    }

    private class RecordingEmbedder : SemanticEmbedder {
        val calls = mutableListOf<Pair<SemanticTextRole, Int>>()

        override suspend fun embed(texts: List<String>, role: SemanticTextRole): EmbeddingBatch {
            calls += role to texts.size
            return EmbeddingBatch(texts.map { floatArrayOf(1f, 0f) }, 0L)
        }
    }
}
