package com.example.spendwise.suggestion

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class SemanticTextRole { QUERY, PASSAGE }

data class EmbeddingBatch(
    val vectors: List<FloatArray>,
    val inferenceMillis: Long
)

interface SemanticEmbedder {
    suspend fun embed(texts: List<String>, role: SemanticTextRole): EmbeddingBatch
}

suspend fun SemanticEmbedder.embedIndependently(
    texts: List<String>,
    role: SemanticTextRole
): EmbeddingBatch {
    if (texts.isEmpty()) return EmbeddingBatch(emptyList(), 0L)
    val results = texts.map { text -> embed(listOf(text), role) }
    return EmbeddingBatch(
        vectors = results.map { it.vectors.single() },
        inferenceMillis = results.sumOf(EmbeddingBatch::inferenceMillis)
    )
}

class OnnxSemanticEmbedder private constructor(
    private val appContext: Context
) : SemanticEmbedder {
    private val initializationMutex = Mutex()
    @Volatile private var runtime: RuntimeState? = null
    @Volatile var initializationMillis: Long? = null
        private set

    override suspend fun embed(
        texts: List<String>,
        role: SemanticTextRole
    ): EmbeddingBatch = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) return@withContext EmbeddingBatch(emptyList(), 0L)
        val state = ensureRuntime()
        val prefix = if (role == SemanticTextRole.QUERY) state.contract.queryPrefix else state.contract.passagePrefix
        val tokenized = state.tokenizer.tokenizeBatch(
            texts = texts.map { prefix + it },
            maxLength = state.contract.maxSequenceLength
        )
        val batchSize = tokenized.inputIds.size
        val sequenceLength = tokenized.inputIds.first().size
        val shape = longArrayOf(batchSize.toLong(), sequenceLength.toLong())
        val flatIds = tokenized.inputIds.flatMap(LongArray::asIterable).toLongArray()
        val flatMask = tokenized.attentionMask.flatMap(LongArray::asIterable).toLongArray()
        val started = System.nanoTime()
        OnnxTensor.createTensor(state.environment, LongBuffer.wrap(flatIds), shape).use { idsTensor ->
            OnnxTensor.createTensor(state.environment, LongBuffer.wrap(flatMask), shape).use { maskTensor ->
                state.session.run(mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val vectors = output[0].value as Array<FloatArray>
                    EmbeddingBatch(
                        vectors = vectors.map { it.copyOf() },
                        inferenceMillis = (System.nanoTime() - started) / 1_000_000L
                    )
                }
            }
        }
    }

    private suspend fun ensureRuntime(): RuntimeState = runtime ?: initializationMutex.withLock {
        runtime ?: run {
            val started = System.nanoTime()
            val contract = DeploymentContract.fromAssets(appContext)
            val tokenizer = ExactUnigramTokenizer.fromAssets(appContext.assets)
            val modelFile = copyModelAssetIfNeeded(appContext, contract.modelBytes)
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = environment.createSession(modelFile.absolutePath, options)
            check(session.inputNames == setOf("input_ids", "attention_mask")) {
                "Unexpected semantic model inputs: ${session.inputNames}"
            }
            check("sentence_embedding" in session.outputNames) {
                "Semantic model output sentence_embedding is missing"
            }
            RuntimeState(environment, session, tokenizer, contract).also {
                initializationMillis = (System.nanoTime() - started) / 1_000_000L
                runtime = it
            }
        }
    }

    private fun copyModelAssetIfNeeded(context: Context, expectedBytes: Long): File {
        val directory = File(context.noBackupFilesDir, "ml/category").apply { mkdirs() }
        val target = File(directory, "model.onnx")
        if (target.length() == expectedBytes) return target
        val temporary = File(directory, "model.onnx.tmp")
        context.assets.open(MODEL_ASSET).use { input ->
            temporary.outputStream().buffered().use(input::copyTo)
        }
        check(temporary.length() == expectedBytes) { "Semantic model asset size mismatch" }
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "Could not prepare semantic model" }
        return target
    }

    private data class RuntimeState(
        val environment: OrtEnvironment,
        val session: OrtSession,
        val tokenizer: ExactUnigramTokenizer,
        val contract: DeploymentContract
    )

    private data class DeploymentContract(
        val queryPrefix: String,
        val passagePrefix: String,
        val maxSequenceLength: Int,
        val modelBytes: Long
    ) {
        companion object {
            fun fromAssets(context: Context): DeploymentContract {
                val text = context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
                val document = JSONObject(text)
                val graph = document.getJSONObject("graph_contract")
                check(graph.getInt("embedding_dimension") == 384)
                check(graph.getBoolean("pooling_in_graph"))
                check(graph.getBoolean("l2_normalization_in_graph"))
                val textContract = document.getJSONObject("text_contract")
                val artifact = document.getJSONObject("artifacts").getJSONObject("model")
                return DeploymentContract(
                    queryPrefix = textContract.getString("query_prefix"),
                    passagePrefix = textContract.getString("passage_prefix"),
                    maxSequenceLength = textContract.getInt("max_sequence_length"),
                    modelBytes = artifact.getLong("bytes")
                )
            }
        }
    }

    companion object {
        private const val MODEL_ASSET = "ml/category/model.onnx"
        private const val MANIFEST_ASSET = "ml/category/deployment_manifest.json"
        @Volatile private var instance: OnnxSemanticEmbedder? = null

        fun get(context: Context): OnnxSemanticEmbedder = instance ?: synchronized(this) {
            instance ?: OnnxSemanticEmbedder(context.applicationContext).also { instance = it }
        }
    }
}
