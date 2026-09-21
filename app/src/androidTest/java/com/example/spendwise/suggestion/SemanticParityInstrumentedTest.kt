package com.example.spendwise.suggestion

import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SemanticParityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext: Context = instrumentation.targetContext

    @Test
    fun tokenizerMatchesPythonParityIds() {
        val tokenizer = ExactUnigramTokenizer.fromAssets(appContext.assets)
        TOKEN_IDS.forEach { (text, expected) ->
            val actual = tokenizer.encode("query: $text", 128)
            assertTrue(
                "Tokenizer mismatch for '$text'\nexpected=${expected.contentToString()}\nactual=${actual.contentToString()}",
                expected.contentEquals(actual)
            )
        }
    }

    @Test
    fun embeddingsAndPredictionsMatchPythonCompactModel() = runBlocking {
        val embedder = OnnxSemanticEmbedder.get(appContext)
        val reference = loadPythonReference()
        assertReferenceContract(reference)
        val descriptions = CATEGORY_NAMES.map(CategorySemanticMetadata::descriptionFor)
        assertEquals("Android and Python category descriptions differ", reference.categoryDescriptions, descriptions)
        val prototypeVectors = embedder.embedIndependently(descriptions, SemanticTextRole.PASSAGE).vectors
        val prototypeComparison = compareEmbeddings(prototypeVectors, reference.categoryEmbeddings)
        Log.i(TAG, "Python/Android category prototypes: ${prototypeComparison.summary()}")
        val cases = reference.cases
        val queryVectors = embedder.embedIndependently(cases.map(ParityCase::text), SemanticTextRole.QUERY).vectors
        assertTrue(
            "Expected every query embedding to have 384 dimensions; actual=${queryVectors.map(FloatArray::size)}",
            queryVectors.all { it.size == 384 }
        )
        val measurements = cases.mapIndexed { index, expected ->
            val ranked = CATEGORY_NAMES.indices.map { categoryIndex ->
                CATEGORY_NAMES[categoryIndex] to dot(queryVectors[index], prototypeVectors[categoryIndex])
            }.sortedByDescending { it.second }
            val androidTop1 = ranked[0]
            val androidTop2 = ranked[1]
            val androidGap = androidTop1.second - androidTop2.second
            val embeddingComparison = compareEmbedding(queryVectors[index], expected.queryEmbedding)
            ParityMeasurement(
                item = expected.text,
                pythonTop1 = expected.category,
                androidTop1 = androidTop1.first,
                pythonTop1Score = expected.topScore,
                androidTop1Score = androidTop1.second,
                pythonTop2 = expected.top2Category,
                androidTop2 = androidTop2.first,
                pythonTop2Score = expected.top2Score,
                androidTop2Score = androidTop2.second,
                pythonGap = expected.gap,
                androidGap = androidGap,
                pythonAccepted = isAccepted(expected.gap),
                androidAccepted = isAccepted(androidGap),
                embeddingCosine = embeddingComparison.cosine,
                prototypeCosine = prototypeComparison.cosine
            )
        }
        val report = buildParityReport(measurements, prototypeComparison)
        Log.i(TAG, report)

        val thresholdMismatches = measurements.filter { it.pythonAccepted != it.androidAccepted }
        assertTrue(
            "$report\nThreshold decisions changed: ${thresholdMismatches.map(ParityMeasurement::item)}",
            thresholdMismatches.isEmpty()
        )
        val acceptedCategoryMismatches = measurements.filter {
            it.pythonAccepted && it.androidAccepted && it.pythonTop1 != it.androidTop1
        }
        assertTrue(
            "$report\nAccepted categories changed: ${acceptedCategoryMismatches.map(ParityMeasurement::item)}",
            acceptedCategoryMismatches.isEmpty()
        )
        assertTrue(
            "$report\nEmbedding cosine fell below $MIN_EMBEDDING_COSINE",
            measurements.minOf(ParityMeasurement::embeddingCosine) >= MIN_EMBEDDING_COSINE
        )
        assertTrue(
            "$report\nPrototype cosine fell below $MIN_PROTOTYPE_COSINE",
            prototypeComparison.cosine >= MIN_PROTOTYPE_COSINE
        )
        assertTrue(
            "$report\nTop-1 score drift exceeded $MAX_TOP1_SCORE_DRIFT",
            measurements.maxOf(ParityMeasurement::top1ScoreDrift) <= MAX_TOP1_SCORE_DRIFT
        )
        assertTrue(
            "$report\nTop-2 score drift exceeded $MAX_TOP2_SCORE_DRIFT",
            measurements.maxOf(ParityMeasurement::top2ScoreDrift) <= MAX_TOP2_SCORE_DRIFT
        )
        assertTrue(
            "$report\nScore-gap drift exceeded $MAX_SCORE_GAP_DRIFT",
            measurements.maxOf(ParityMeasurement::gapDrift) <= MAX_SCORE_GAP_DRIFT
        )
    }

    @Test
    fun productionResultsAreInvariantToSurroundingReceiptItems() = runBlocking {
        val embedder = OnnxSemanticEmbedder.get(appContext)
        val prototypes = embedder.embedIndependently(
            CATEGORY_NAMES.map(CategorySemanticMetadata::descriptionFor),
            SemanticTextRole.PASSAGE
        ).vectors
        val allItems = TOKEN_IDS.keys.toList()
        allItems.forEachIndexed { index, target ->
            val baselineVector = embedder.embedIndependently(listOf(target), SemanticTextRole.QUERY).vectors.single()
            val baseline = rank(baselineVector, prototypes)
            val others = allItems.filterNot { it == target }
            val compositions = listOf(
                listOf(target, others[0]),
                listOf(target, *others.take(3).toTypedArray()),
                allItems.drop(index) + allItems.take(index)
            )
            compositions.forEach { composition ->
                val vectors = embedder.embedIndependently(composition, SemanticTextRole.QUERY).vectors
                val actual = rank(vectors[composition.indexOf(target)], prototypes)
                val diagnostics = "item=$target batchSize=${composition.size} " +
                    "baseline=$baseline actual=$actual"
                assertEquals("$diagnostics\nTop-1 changed", baseline.top1, actual.top1)
                assertEquals("$diagnostics\nThreshold decision changed", baseline.accepted, actual.accepted)
                assertWithin("$diagnostics\nTop-1 score changed", baseline.top1Score, actual.top1Score)
                assertWithin("$diagnostics\nTop-2 score changed", baseline.top2Score, actual.top2Score)
            }
        }
    }

    @Test
    fun logsArm64DevicePerformance() = runBlocking {
        val embedder = OnnxSemanticEmbedder.get(appContext)
        val firstStarted = System.nanoTime()
        embedder.embed(listOf("Panadol Extra"), SemanticTextRole.QUERY)
        val firstMillis = elapsedMillis(firstStarted)
        val warmStarted = System.nanoTime()
        embedder.embed(listOf("Folic Acid 600 MCG"), SemanticTextRole.QUERY)
        val warmMillis = elapsedMillis(warmStarted)
        val sequentialFiveStarted = System.nanoTime()
        embedder.embedIndependently(TOKEN_IDS.keys.take(5), SemanticTextRole.QUERY)
        val sequentialFiveMillis = elapsedMillis(sequentialFiveStarted)
        val sequentialNineStarted = System.nanoTime()
        embedder.embedIndependently(TOKEN_IDS.keys.toList(), SemanticTextRole.QUERY)
        val sequentialNineMillis = elapsedMillis(sequentialNineStarted)
        Log.i(
            TAG,
            "initMs=${embedder.initializationMillis} firstMs=$firstMillis warmMs=$warmMillis " +
                "sequential5Ms=$sequentialFiveMillis sequential9Ms=$sequentialNineMillis pssKb=${Debug.getPss()}"
        )
        assertTrue("First inference duration was negative: $firstMillis", firstMillis >= 0L)
        assertTrue("Warm inference duration was negative: $warmMillis", warmMillis >= 0L)
        assertTrue("Five-item sequential duration was negative: $sequentialFiveMillis", sequentialFiveMillis >= 0L)
        assertTrue("Nine-item sequential duration was negative: $sequentialNineMillis", sequentialNineMillis >= 0L)
    }

    private fun loadPythonReference(): PythonReference {
        val text = instrumentation.context.assets.open(PYTHON_REFERENCE_ASSET)
            .bufferedReader()
            .use { it.readText() }
        val root = JSONObject(text)
        val categories = root.getJSONArray("category_names")
        val descriptions = root.getJSONArray("category_descriptions")
        val categoryEmbeddings = root.getJSONArray("category_embeddings")
        val cases = root.getJSONArray("cases")
        return PythonReference(
            model = root.getString("model"),
            modelSha256 = root.getString("model_sha256"),
            tokenizerSha256 = root.getString("tokenizer_sha256"),
            quantization = root.getString("quantization"),
            queryPrefix = root.getString("query_prefix"),
            passagePrefix = root.getString("passage_prefix"),
            maxSequenceLength = root.getInt("max_sequence_length"),
            prototypeStrategy = root.getString("prototype_strategy"),
            inferenceContract = root.getString("inference_contract"),
            categoryNames = List(categories.length()) { categories.getString(it) },
            categoryDescriptions = List(descriptions.length()) { descriptions.getString(it) },
            categoryEmbeddings = List(categoryEmbeddings.length()) { categoryEmbeddings.getJSONArray(it).toFloatArray() },
            cases = List(cases.length()) { index ->
                val value = cases.getJSONObject(index)
                ParityCase(
                    text = value.getString("text"),
                    category = value.getString("expected_category"),
                    topScore = value.getDouble("expected_top1_score"),
                    top2Category = value.getString("expected_top2_category"),
                    top2Score = value.getDouble("expected_top2_score"),
                    gap = value.getDouble("expected_gap"),
                    queryEmbedding = value.getJSONArray("query_embedding").toFloatArray()
                )
            }
        )
    }

    private fun assertReferenceContract(reference: PythonReference) {
        assertEquals("Wrong Python reference model", EXPECTED_MODEL, reference.model)
        assertEquals("Wrong model artifact in Python reference", reference.modelSha256, sha256("ml/category/model.onnx"))
        assertEquals(
            "Wrong tokenizer artifact in Python reference",
            reference.tokenizerSha256,
            sha256("ml/category/tokenizer/tokenizer.json")
        )
        assertEquals("Wrong quantization reference", "dynamic QInt8 per-channel", reference.quantization)
        assertEquals("Wrong query prefix", "query: ", reference.queryPrefix)
        assertEquals("Wrong passage prefix", "passage: ", reference.passagePrefix)
        assertEquals("Wrong sequence limit", 128, reference.maxSequenceLength)
        assertEquals("Wrong category order", CATEGORY_NAMES, reference.categoryNames)
        assertEquals(
            "Unexpected prototype fixture",
            "DESCRIPTION_ONLY_NO_CONFIRMED_EXAMPLES_SINGLE_ITEM",
            reference.prototypeStrategy
        )
        assertEquals("Unexpected inference contract", "SINGLE_ITEM", reference.inferenceContract)
    }

    private fun sha256(assetPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        appContext.assets.open(assetPath).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun org.json.JSONArray.toFloatArray(): FloatArray =
        FloatArray(length()) { getDouble(it).toFloat() }

    private fun compareEmbeddings(
        android: List<FloatArray>,
        python: List<FloatArray>
    ): EmbeddingComparison {
        require(android.size == python.size)
        val comparisons = android.indices.map { compareEmbedding(android[it], python[it]) }
        return EmbeddingComparison(
            cosine = comparisons.minOf(EmbeddingComparison::cosine),
            maxDifference = comparisons.maxOf(EmbeddingComparison::maxDifference),
            meanDifference = comparisons.map(EmbeddingComparison::meanDifference).average()
        )
    }

    private fun compareEmbedding(android: FloatArray, python: FloatArray): EmbeddingComparison {
        require(android.size == python.size)
        val differences = android.indices.map { abs(android[it] - python[it]).toDouble() }
        val androidNorm = kotlin.math.sqrt(android.sumOf { it.toDouble() * it.toDouble() })
        val pythonNorm = kotlin.math.sqrt(python.sumOf { it.toDouble() * it.toDouble() })
        return EmbeddingComparison(
            cosine = dot(android, python) / (androidNorm * pythonNorm),
            maxDifference = differences.maxOrNull() ?: 0.0,
            meanDifference = differences.average()
        )
    }

    private fun assertWithin(message: String, expected: Double, actual: Double) {
        val difference = abs(actual - expected)
        assertTrue(
            "$message\nabsoluteDifference=$difference tolerance=$SCORE_TOLERANCE",
            difference < SCORE_TOLERANCE
        )
    }

    private fun dot(left: FloatArray, right: FloatArray): Double =
        left.indices.sumOf { left[it].toDouble() * right[it].toDouble() }

    private fun isAccepted(gap: Double): Boolean =
        gap >= SemanticCategorySuggestionEngine.MINIMUM_SCORE_GAP

    private fun buildParityReport(
        measurements: List<ParityMeasurement>,
        prototypeComparison: EmbeddingComparison
    ): String = buildString {
        appendLine("Android/Python single-item production parity")
        appendLine(
            "item | pythonTop1 | androidTop1 | pythonTop1Score | androidTop1Score | " +
                "pythonTop2 | androidTop2 | pythonTop2Score | androidTop2Score | pythonGap | androidGap | pythonAccepted | " +
                "androidAccepted | embeddingCosine | prototypeCosine | top1Drift | top2Drift | gapDrift"
        )
        measurements.forEach { appendLine(it.tableRow()) }
        appendLine("minimumEmbeddingCosine=${measurements.minOf(ParityMeasurement::embeddingCosine)}")
        appendLine("minimumPrototypeCosine=${prototypeComparison.cosine}")
        appendLine("maximumTop1ScoreDrift=${measurements.maxOf(ParityMeasurement::top1ScoreDrift)}")
        appendLine("maximumTop2ScoreDrift=${measurements.maxOf(ParityMeasurement::top2ScoreDrift)}")
        appendLine("maximumScoreGapDrift=${measurements.maxOf(ParityMeasurement::gapDrift)}")
        appendLine("top1Changes=${measurements.count { it.pythonTop1 != it.androidTop1 }}")
        appendLine(
            "acceptedCategoryChanges=${measurements.count { it.pythonAccepted && it.androidAccepted && it.pythonTop1 != it.androidTop1 }}"
        )
        appendLine("thresholdDecisionChanges=${measurements.count { it.pythonAccepted != it.androidAccepted }}")
        appendLine("prototypeComparison=${prototypeComparison.summary()}")
    }.trim()

    private fun rank(query: FloatArray, prototypes: List<FloatArray>): RankedResult {
        val ranked = CATEGORY_NAMES.indices.map { index ->
            CATEGORY_NAMES[index] to dot(query, prototypes[index])
        }.sortedByDescending { it.second }
        return RankedResult(
            top1 = ranked[0].first,
            top1Score = ranked[0].second,
            top2 = ranked[1].first,
            top2Score = ranked[1].second,
            accepted = ranked[0].second - ranked[1].second >= SemanticCategorySuggestionEngine.MINIMUM_SCORE_GAP
        )
    }

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000L

    private data class ParityCase(
        val text: String,
        val category: String,
        val topScore: Double,
        val top2Category: String,
        val top2Score: Double,
        val gap: Double,
        val queryEmbedding: FloatArray
    )

    private data class PythonReference(
        val model: String,
        val modelSha256: String,
        val tokenizerSha256: String,
        val quantization: String,
        val queryPrefix: String,
        val passagePrefix: String,
        val maxSequenceLength: Int,
        val prototypeStrategy: String,
        val inferenceContract: String,
        val categoryNames: List<String>,
        val categoryDescriptions: List<String>,
        val categoryEmbeddings: List<FloatArray>,
        val cases: List<ParityCase>
    )

    private data class RankedResult(
        val top1: String,
        val top1Score: Double,
        val top2: String,
        val top2Score: Double,
        val accepted: Boolean
    )

    private data class ParityMeasurement(
        val item: String,
        val pythonTop1: String,
        val androidTop1: String,
        val pythonTop1Score: Double,
        val androidTop1Score: Double,
        val pythonTop2: String,
        val androidTop2: String,
        val pythonTop2Score: Double,
        val androidTop2Score: Double,
        val pythonGap: Double,
        val androidGap: Double,
        val pythonAccepted: Boolean,
        val androidAccepted: Boolean,
        val embeddingCosine: Double,
        val prototypeCosine: Double
    ) {
        val top1ScoreDrift: Double get() = abs(pythonTop1Score - androidTop1Score)
        val top2ScoreDrift: Double get() = abs(pythonTop2Score - androidTop2Score)
        val gapDrift: Double get() = abs(pythonGap - androidGap)

        fun tableRow(): String = listOf(
            item,
            pythonTop1,
            androidTop1,
            pythonTop1Score,
            androidTop1Score,
            pythonTop2,
            androidTop2,
            pythonTop2Score,
            androidTop2Score,
            pythonGap,
            androidGap,
            pythonAccepted,
            androidAccepted,
            embeddingCosine,
            prototypeCosine,
            top1ScoreDrift,
            top2ScoreDrift,
            gapDrift
        ).joinToString(" | ")
    }

    private data class EmbeddingComparison(
        val cosine: Double,
        val maxDifference: Double,
        val meanDifference: Double
    ) {
        fun summary(): String = "cosine=$cosine maxDiff=$maxDifference meanDiff=$meanDifference"
    }

    private companion object {
        const val TAG = "SpendWiseSemanticPerf"
        const val SCORE_TOLERANCE = 0.002
        // Calibrated from all nine single-item fixtures for this exact dynamic INT8 graph.
        // Windows x64 vs Android ARM64 observed: embedding >= 0.994064, prototype = 0.996727,
        // Top1 drift <= 0.008116, Top2 drift <= 0.008539, and gap drift <= 0.007106.
        // These small margins catch material numerical regressions. Production acceptance and
        // accepted-category agreement remain exact requirements independent of these bounds.
        const val MIN_EMBEDDING_COSINE = 0.9935
        const val MIN_PROTOTYPE_COSINE = 0.996
        const val MAX_TOP1_SCORE_DRIFT = 0.009
        const val MAX_TOP2_SCORE_DRIFT = 0.009
        const val MAX_SCORE_GAP_DRIFT = 0.008
        const val PYTHON_REFERENCE_ASSET = "ml/category/python_semantic_parity.json"
        const val EXPECTED_MODEL = "alphaedge-ai/multilingual-e5-small-arb-32768"
        val CATEGORY_NAMES = listOf(
            "Groceries", "Fruits & Vegetables", "Restaurants", "Transport", "Household",
            "Medicine", "Shopping", "Bills", "Entertainment", "Personal Care", "Other", "Books"
        )
        val TOKEN_IDS = linkedMapOf(
            "Panadol Extra" to intArrayOf(0, 41, 1006, 12, 9181, 3078, 10058, 2),
            "Folic Acid 600 MCG" to intArrayOf(0, 41, 1006, 12, 9736, 1285, 62, 14727, 3247, 266, 14788, 2),
            "IMMULANT PLUS 20 CAP" to intArrayOf(0, 41, 1006, 12, 13121, 29046, 10595, 22943, 357, 17454, 2),
            "Dettol Floor Cleaner" to intArrayOf(0, 41, 1006, 12, 511, 5652, 29098, 17467, 56, 2),
            "Chicken Breast" to intArrayOf(0, 41, 1006, 12, 30524, 23436, 2),
            "طماطم" to intArrayOf(0, 41, 1006, 12, 6591, 1838, 20072, 2),
            "شامبو دوف" to intArrayOf(0, 41, 1006, 12, 8664, 6762, 178, 4989, 2),
            "Atomic Habits" to intArrayOf(0, 41, 1006, 12, 16941, 1285, 10405, 5879, 2),
            "Clean Code" to intArrayOf(0, 41, 1006, 12, 17467, 9468, 2)
        )
    }
}
