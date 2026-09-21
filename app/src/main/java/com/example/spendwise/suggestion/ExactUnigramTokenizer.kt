package com.example.spendwise.suggestion

import android.content.res.AssetManager
import java.text.Normalizer
import org.json.JSONObject

internal data class TokenizedBatch(
    val inputIds: Array<LongArray>,
    val attentionMask: Array<LongArray>
)

internal class ExactUnigramTokenizer private constructor(
    private val root: TrieNode,
    private val unknownId: Int,
    private val minimumScore: Double,
    private val bosId: Int,
    private val eosId: Int,
    private val padId: Int
) {
    fun tokenizeBatch(texts: List<String>, maxLength: Int): TokenizedBatch {
        require(texts.isNotEmpty())
        require(maxLength >= 2)
        val encoded = texts.map { encode(it, maxLength) }
        val sequenceLength = encoded.maxOf { it.size }
        val ids = Array(encoded.size) { LongArray(sequenceLength) { padId.toLong() } }
        val masks = Array(encoded.size) { LongArray(sequenceLength) }
        encoded.forEachIndexed { row, tokens ->
            tokens.forEachIndexed { column, token ->
                ids[row][column] = token.toLong()
                masks[row][column] = 1L
            }
        }
        return TokenizedBatch(ids, masks)
    }

    fun encode(text: String, maxLength: Int): IntArray {
        val normalized = normalize(text)
        val bestScore = DoubleArray(normalized.length + 1) { Double.NEGATIVE_INFINITY }
        val previous = IntArray(normalized.length + 1) { -1 }
        val previousToken = IntArray(normalized.length + 1) { unknownId }
        bestScore[0] = 0.0

        for (start in normalized.indices) {
            if (!bestScore[start].isFinite()) continue
            var node: TrieNode? = root
            var end = start
            var foundPiece = false
            while (end < normalized.length) {
                node = node?.children?.get(normalized[end]) ?: break
                end++
                node.piece?.let { piece ->
                    foundPiece = true
                    val candidate = bestScore[start] + piece.score
                    if (candidate > bestScore[end]) {
                        bestScore[end] = candidate
                        previous[end] = start
                        previousToken[end] = piece.id
                    }
                }
            }
            if (!foundPiece) {
                val endOfCodePoint = start + Character.charCount(Character.codePointAt(normalized, start))
                val candidate = bestScore[start] + minimumScore - UNKNOWN_PENALTY
                if (candidate > bestScore[endOfCodePoint]) {
                    bestScore[endOfCodePoint] = candidate
                    previous[endOfCodePoint] = start
                    previousToken[endOfCodePoint] = unknownId
                }
            }
        }

        val pieces = ArrayList<Int>()
        var cursor = normalized.length
        while (cursor > 0 && previous[cursor] >= 0) {
            pieces += previousToken[cursor]
            cursor = previous[cursor]
        }
        if (cursor != 0) pieces.apply { clear(); add(unknownId) }
        pieces.reverse()
        val contentLimit = maxLength - 2
        val result = IntArray(minOf(pieces.size, contentLimit) + 2)
        result[0] = bosId
        for (index in 1 until result.lastIndex) result[index] = pieces[index - 1]
        result[result.lastIndex] = eosId
        return result
    }

    private fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .map { character -> if (character.isWhitespace()) ' ' else character }
            .joinToString("")
            .replace(REPEATED_SPACES, " ")
        return METASPACE + nfkc.replace(' ', METASPACE)
    }

    companion object {
        private const val UNKNOWN_PENALTY = 10.0
        private const val METASPACE = '▁'
        private val REPEATED_SPACES = Regex(" {2,}")

        fun fromAssets(
            assets: AssetManager,
            path: String = "ml/category/tokenizer/tokenizer.json"
        ): ExactUnigramTokenizer {
            val json = assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return fromJson(json)
        }

        internal fun fromJson(json: String): ExactUnigramTokenizer {
            val document = JSONObject(json)
            val model = document.getJSONObject("model")
            require(model.getString("type") == "Unigram") { "Expected a Unigram tokenizer" }
            val vocabulary = model.getJSONArray("vocab")
            val root = TrieNode()
            var minimumScore = Double.POSITIVE_INFINITY
            for (id in 0 until vocabulary.length()) {
                val entry = vocabulary.getJSONArray(id)
                val token = entry.getString(0)
                val score = entry.getDouble(1)
                minimumScore = minOf(minimumScore, score)
                if (id in SPECIAL_TOKEN_IDS) continue
                var node = root
                token.forEach { character ->
                    node = node.children.getOrPut(character) { TrieNode() }
                }
                node.piece = Piece(id, score)
            }
            return ExactUnigramTokenizer(
                root = root,
                unknownId = model.getInt("unk_id"),
                minimumScore = minimumScore,
                bosId = 0,
                eosId = 2,
                padId = 1
            )
        }

        private val SPECIAL_TOKEN_IDS = setOf(0, 1, 2, 3, 32767)
    }

    private data class Piece(val id: Int, val score: Double)
    private class TrieNode(
        val children: MutableMap<Char, TrieNode> = HashMap(),
        var piece: Piece? = null
    )
}
