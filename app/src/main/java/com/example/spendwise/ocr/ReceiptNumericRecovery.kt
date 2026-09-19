package com.example.spendwise.ocr

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max

data class NumericRecoveryDebugEntry(
    val itemName: String,
    val rowBoundingBox: OcrBoundingBox?,
    val originalText: String,
    val originalAmountMinor: Long,
    val originalConfidence: Float?,
    val candidates: List<NumericOcrCandidate>,
    val selectedText: String?,
    val selectedAmountMinor: Long?,
    val selectedConfidence: Float?,
    val selectedSource: PriceSource
)

data class NumericRecoveryResult(
    val receipt: ParsedReceipt,
    val debugEntries: List<NumericRecoveryDebugEntry>
)

class ReceiptNumericRecovery {
    fun selectTargets(receipt: ParsedReceipt): List<PriceRecoveryTarget> {
        if (receipt.recoveryTargets.isEmpty()) return emptyList()
        val total = receipt.detectedTotalMinor
        val calculated = receipt.items.sumOf(ParsedReceiptItem::amountMinor)
        val majorMismatch = total != null && abs(total - calculated) > max(MIN_MAJOR_MISMATCH_MINOR, total / 20L)
        val sortedAmounts = receipt.items.map(ParsedReceiptItem::amountMinor).sorted()
        val median = sortedAmounts.getOrNull(sortedAmounts.size / 2) ?: 0L
        return receipt.recoveryTargets.mapNotNull { target ->
            val lowConfidence = (target.originalConfidence ?: 0f) < LOW_ORIGINAL_CONFIDENCE
            val suspiciouslySmall = receipt.items.size > 1 && median > 0L && target.originalAmountMinor * 4L < median
            if (!lowConfidence && !suspiciouslySmall && !majorMismatch) return@mapNotNull null
            val priority = (if (lowConfidence) 2 else 0) + (if (suspiciouslySmall) 2 else 0) +
                (if (majorMismatch) 1 else 0)
            target to priority
        }.sortedByDescending { (_, priority) -> priority }
            .take(MAX_RECOVERY_CROPS)
            .map { (target, _) -> target }
    }

    fun recover(
        receipt: ParsedReceipt,
        candidates: List<NumericOcrCandidate>
    ): NumericRecoveryResult {
        val updatedItems = receipt.items.toMutableList()
        val resultingConfidence = receipt.recoveryTargets.associate { target ->
            target.itemIndex to (target.originalConfidence ?: 0f)
        }.toMutableMap()
        val debug = mutableListOf<NumericRecoveryDebugEntry>()

        receipt.recoveryTargets.forEach { target ->
            val item = updatedItems.getOrNull(target.itemIndex) ?: return@forEach
            val candidateScores = candidates.asSequence()
                .filter { it.itemIndex == target.itemIndex }
                .mapNotNull { candidate ->
                    val amount = parseStrictMoney(candidate.text) ?: return@mapNotNull null
                    if (amount !in MIN_PRICE_MINOR..MAX_PRICE_MINOR) return@mapNotNull null
                    ScoredCandidate(
                        candidate = candidate,
                        amountMinor = amount,
                        score = scoreCandidate(receipt, target, candidate, amount, candidates)
                    )
                }
                .sortedByDescending(ScoredCandidate::score)
                .toList()
            val selected = candidateScores.firstOrNull()?.takeIf { scored ->
                val agreeingVariants = candidateScores.count { it.amountMinor == scored.amountMinor }
                val independentEvidence = scored.candidate.confidence >= STRONG_RECOVERY_CONFIDENCE ||
                    agreeingVariants >= 2 || (target.originalConfidence ?: 0f) < LOW_ORIGINAL_CONFIDENCE
                scored.score >= MIN_RECOVERY_SCORE &&
                    scored.candidate.confidence >= MIN_RECOVERY_CONFIDENCE && independentEvidence
            }

            if (selected != null && selected.amountMinor != item.amountMinor) {
                val agreeingVariants = candidateScores.count { it.amountMinor == selected.amountMinor }
                val uncertain = selected.candidate.confidence < REVIEW_CONFIDENCE || agreeingVariants < 2
                updatedItems[target.itemIndex] = item.copy(
                    amountMinor = selected.amountMinor,
                    priceSource = PriceSource.OCR_NUMERIC_RECOVERY,
                    requiresReview = uncertain
                )
                resultingConfidence[target.itemIndex] = selected.candidate.confidence
            }
            val finalItem = updatedItems[target.itemIndex]
            debug += NumericRecoveryDebugEntry(
                itemName = item.name,
                rowBoundingBox = target.rowBoundingBox,
                originalText = target.originalText,
                originalAmountMinor = target.originalAmountMinor,
                originalConfidence = target.originalConfidence,
                candidates = candidates.filter { it.itemIndex == target.itemIndex },
                selectedText = selected?.candidate?.text,
                selectedAmountMinor = finalItem.amountMinor,
                selectedConfidence = selected?.candidate?.confidence,
                selectedSource = finalItem.priceSource
            )
        }

        applySafeReconciliation(receipt, updatedItems, resultingConfidence, debug)
        val calculated = updatedItems.sumOf(ParsedReceiptItem::amountMinor)
        val consistency = when (val total = receipt.detectedTotalMinor) {
            null -> ReceiptTotalConsistency.NOT_AVAILABLE
            else -> if (abs(total - calculated) <= TOTAL_TOLERANCE_MINOR) {
                ReceiptTotalConsistency.MATCH
            } else {
                ReceiptTotalConsistency.MISMATCH
            }
        }
        return NumericRecoveryResult(
            receipt = receipt.copy(items = updatedItems, totalConsistency = consistency),
            debugEntries = debug
        )
    }

    private fun applySafeReconciliation(
        original: ParsedReceipt,
        items: MutableList<ParsedReceiptItem>,
        confidences: Map<Int, Float>,
        debug: MutableList<NumericRecoveryDebugEntry>
    ) {
        val total = original.detectedTotalMinor ?: return
        if (!original.detectedTotalReliable || original.hasAdjustments || items.isEmpty()) return
        if (abs(items.sumOf(ParsedReceiptItem::amountMinor) - total) <= TOTAL_TOLERANCE_MINOR) return
        val unreliable = items.indices.filter { index ->
            (confidences[index] ?: 0f) < RELIABLE_PRICE_CONFIDENCE
        }
        if (unreliable.size != 1) return
        val uncertainIndex = unreliable.single()
        val reliableSum = items.indices.filterNot { it == uncertainIndex }.sumOf { items[it].amountMinor }
        val remaining = total - reliableSum
        if (remaining !in MIN_PRICE_MINOR..MAX_PRICE_MINOR) return
        val item = items[uncertainIndex]
        items[uncertainIndex] = item.copy(
            amountMinor = remaining,
            priceSource = PriceSource.TOTAL_RECONCILIATION,
            requiresReview = true
        )
        val debugIndex = debug.indexOfFirst { it.itemName == item.name && it.originalAmountMinor == item.amountMinor }
        if (debugIndex >= 0) {
            debug[debugIndex] = debug[debugIndex].copy(
                selectedAmountMinor = remaining,
                selectedSource = PriceSource.TOTAL_RECONCILIATION
            )
        }
    }

    private fun scoreCandidate(
        receipt: ParsedReceipt,
        target: PriceRecoveryTarget,
        candidate: NumericOcrCandidate,
        amountMinor: Long,
        allCandidates: List<NumericOcrCandidate>
    ): Double {
        val confidenceScore = candidate.confidence.coerceIn(0f, 1f) * 0.5
        val syntaxScore = if (normalizeDigits(candidate.text).contains(Regex("[.,]\\d{1,2}"))) 0.12 else 0.08
        val columnScore = columnAlignmentScore(target, candidate.boundingBox) * 0.1
        val rangeScore = when (amountMinor) {
            in 1L..10_000_000L -> 0.1
            else -> 0.04
        }
        val agreementCount = allCandidates.count { other ->
            other.itemIndex == candidate.itemIndex && parseStrictMoney(other.text) == amountMinor
        }
        val agreementScore = ((agreementCount - 1).coerceIn(0, 2)) * 0.05
        val totalScore = receipt.detectedTotalMinor?.let { total ->
            val currentSum = receipt.items.sumOf(ParsedReceiptItem::amountMinor)
            val originalDifference = abs(total - currentSum)
            val recoveredDifference = abs(total - (currentSum - target.originalAmountMinor + amountMinor))
            if (recoveredDifference < originalDifference) {
                0.1 * (1.0 - recoveredDifference.toDouble() / originalDifference.coerceAtLeast(1L))
            } else 0.0
        } ?: 0.0
        return confidenceScore + syntaxScore + columnScore + rangeScore + agreementScore + totalScore
    }

    private fun columnAlignmentScore(target: PriceRecoveryTarget, candidateBox: OcrBoundingBox): Double {
        val expectedX = target.expectedColumnX ?: return 0.5
        val candidateX = (candidateBox.left + candidateBox.right) / 2f
        val rowWidth = target.rowBoundingBox?.let { (it.right - it.left).coerceAtLeast(1f) } ?: 100f
        return (1.0 - kotlin.math.min(1.0, abs(candidateX - expectedX).toDouble() / (rowWidth * 0.15f))).coerceAtLeast(0.0)
    }

    private fun parseStrictMoney(value: String): Long? {
        val normalized = normalizeDigits(value).trim()
            .replace(CURRENCY_REGEX, "")
            .replace(" ", "")
        if (!MONEY_REGEX.matches(normalized)) return null
        val canonical = when {
            normalized.contains(',') && normalized.contains('.') -> normalized.replace(",", "")
            normalized.count { it == ',' } == 1 && normalized.substringAfter(',').length <= 2 -> normalized.replace(',', '.')
            else -> normalized.replace(",", "")
        }
        return runCatching {
            BigDecimal(canonical).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
        }.getOrNull()
    }

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    in '\u0660'..'\u0669' -> '0' + (character - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (character - '\u06F0')
                    '\u066B' -> '.'
                    '\u066C' -> ','
                    else -> character
                }
            )
        }
    }

    private data class ScoredCandidate(
        val candidate: NumericOcrCandidate,
        val amountMinor: Long,
        val score: Double
    )

    private companion object {
        const val LOW_ORIGINAL_CONFIDENCE = 0.78f
        const val RELIABLE_PRICE_CONFIDENCE = 0.78f
        const val MIN_RECOVERY_CONFIDENCE = 0.55f
        const val STRONG_RECOVERY_CONFIDENCE = 0.82f
        const val REVIEW_CONFIDENCE = 0.86f
        const val MIN_RECOVERY_SCORE = 0.58
        const val MIN_MAJOR_MISMATCH_MINOR = 100L
        const val MIN_PRICE_MINOR = 1L
        const val MAX_PRICE_MINOR = 100_000_000L
        const val MAX_RECOVERY_CROPS = 6
        const val TOTAL_TOLERANCE_MINOR = 2L
        val MONEY_REGEX = Regex("^(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:[.,]\\d{1,2})?$")
        val CURRENCY_REGEX = Regex("(?i)(?:EGP|L\\.?E\\.?|ج(?:نيه)?|جم)")
    }
}
