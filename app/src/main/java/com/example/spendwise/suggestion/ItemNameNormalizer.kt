package com.example.spendwise.suggestion

import java.text.Normalizer

object ItemNameNormalizer {
    fun normalize(value: String): String {
        val normalizedDigits = buildString(value.length) {
            Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase().forEach { character ->
                append(
                    when (character) {
                        in '\u0660'..'\u0669' -> '0' + (character - '\u0660')
                        in '\u06F0'..'\u06F9' -> '0' + (character - '\u06F0')
                        '\u0640' -> ' '
                        else -> character
                    }
                )
            }
        }
        val withoutNoise = normalizedDigits
            .replace(ARABIC_DIACRITICS_REGEX, "")
            .replace(PUNCTUATION_AND_SYMBOLS_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        if (withoutNoise.isEmpty()) return withoutNoise
        return repairSpacedCharacters(withoutNoise.split(' '))
            .joinToString(" ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    fun normalizeNullable(value: String?): String? = value
        ?.let(::normalize)
        ?.takeIf(String::isNotBlank)

    private fun repairSpacedCharacters(tokens: List<String>): List<String> {
        val repaired = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            if (isSingleLetterOrDigit(tokens[index])) {
                var end = index
                while (end < tokens.size && isSingleLetterOrDigit(tokens[end])) end++
                if (end - index >= MIN_SPACED_SEQUENCE) {
                    repaired += tokens.subList(index, end).joinToString("")
                } else {
                    repaired += tokens.subList(index, end)
                }
                index = end
            } else {
                repaired += tokens[index]
                index++
            }
        }
        return repaired
    }

    private fun isSingleLetterOrDigit(value: String): Boolean =
        value.length == 1 && value.single().isLetterOrDigit()

    private const val MIN_SPACED_SEQUENCE = 3
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val PUNCTUATION_AND_SYMBOLS_REGEX = Regex("[\\p{P}\\p{S}]+")
    private val ARABIC_DIACRITICS_REGEX = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
}
