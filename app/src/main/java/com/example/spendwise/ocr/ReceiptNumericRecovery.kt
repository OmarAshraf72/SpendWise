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
        val selectedItemIndexes = receipt.recoveryTargets.asSequence()
            .filter { it.role == NumericCellRole.LINE_TOTAL }
            .mapNotNull { target ->
            val lowConfidence = (target.originalConfidence ?: 0f) < LOW_ORIGINAL_CONFIDENCE
            val suspiciouslySmall = receipt.items.size > 1 && median > 0L && target.originalAmountMinor * 4L < median
            val structurallyRecovered = receipt.items.getOrNull(target.itemIndex)?.requiresReview == true
            if (!lowConfidence && !suspiciouslySmall && !majorMismatch && !structurallyRecovered) return@mapNotNull null
            val priority = (if (lowConfidence) 2 else 0) + (if (suspiciouslySmall) 2 else 0) +
                (if (majorMismatch) 1 else 0) + (if (structurallyRecovered) 2 else 0)
            target to priority
        }.sortedByDescending { (_, priority) -> priority }
            .take(MAX_RECOVERY_ROWS)
            .map { (target, _) -> target.itemIndex }
            .toSet()
        return receipt.recoveryTargets.filter { it.itemIndex in selectedItemIndexes }
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

        receipt.recoveryTargets.filter { it.role == NumericCellRole.LINE_TOTAL }.forEach { target ->
            val item = updatedItems.getOrNull(target.itemIndex) ?: return@forEach
            val candidateScores = candidates.asSequence()
                .filter { it.itemIndex == target.itemIndex && it.role == NumericCellRole.LINE_TOTAL }
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
                val preservesStrongerArithmeticEvidence = item.priceSource != PriceSource.ARITHMETIC_RECOVERY ||
                    scored.amountMinor == item.amountMinor || improvesCurrentTotalConsistency(receipt, item, scored.amountMinor)
                scored.score >= MIN_RECOVERY_SCORE &&
                    scored.candidate.confidence >= MIN_RECOVERY_CONFIDENCE && independentEvidence &&
                    preservesStrongerArithmeticEvidence
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
            other.itemIndex == candidate.itemIndex && other.role == candidate.role && parseStrictMoney(other.text) == amountMinor
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
        val arithmeticScore = arithmeticAgreementScore(candidate, amountMinor, allCandidates)
        return confidenceScore + syntaxScore + columnScore + rangeScore + agreementScore + totalScore + arithmeticScore
    }

    private fun arithmeticAgreementScore(
        lineTotal: NumericOcrCandidate,
        amountMinor: Long,
        allCandidates: List<NumericOcrCandidate>
    ): Double {
        val unit = bestDecimalCandidate(allCandidates, lineTotal.itemIndex, NumericCellRole.UNIT_PRICE, 2) ?: return 0.0
        val weight = bestDecimalCandidate(allCandidates, lineTotal.itemIndex, NumericCellRole.WEIGHT, 3) ?: return 0.0
        val expectedMinor = unit.first.multiply(weight.first).setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2).longValueExact()
        val difference = abs(expectedMinor - amountMinor)
        return when {
            difference <= ARITHMETIC_TOLERANCE_MINOR -> 0.16
            difference <= 10L -> 0.05
            else -> 0.0
        }
    }

    private fun bestDecimalCandidate(
        candidates: List<NumericOcrCandidate>,
        itemIndex: Int,
        role: NumericCellRole,
        maxScale: Int
    ): Pair<BigDecimal, Float>? = candidates.asSequence()
        .filter { it.itemIndex == itemIndex && it.role == role }
        .mapNotNull { candidate ->
            parseStrictDecimal(candidate.text, maxScale)
                ?.takeIf { decimal -> fitsColumnFormat(decimal, role) }
                ?.let { it to candidate.confidence }
        }
        .groupBy { it.first }
        .map { (value, matches) -> value to (matches.maxOf { it.second } + matches.size.coerceAtMost(3) * 0.03f) }
        .maxByOrNull { it.second }

    private fun fitsColumnFormat(value: BigDecimal, role: NumericCellRole): Boolean = when (role) {
        NumericCellRole.UNIT_PRICE -> value > BigDecimal.ZERO && value.scale() <= 2
        NumericCellRole.WEIGHT -> value > BigDecimal.ZERO && value <= MAX_REASONABLE_WEIGHT && value.scale() == 3
        NumericCellRole.LINE_TOTAL -> value > BigDecimal.ZERO && value.scale() <= 2
    }

    private fun parseStrictDecimal(value: String, maxScale: Int): BigDecimal? {
        val normalized = repairNumericOcr(normalizeDigits(value)).trim().replace(CURRENCY_REGEX, "").replace(" ", "")
        if (!Regex("^(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:[.,]\\d{1,$maxScale})?$").matches(normalized)) return null
        val canonical = when {
            normalized.contains(',') && normalized.contains('.') -> normalized.replace(",", "")
            normalized.count { it == ',' } == 1 && normalized.substringAfter(',').length <= maxScale -> normalized.replace(',', '.')
            else -> normalized.replace(",", "")
        }
        return canonical.toBigDecimalOrNull()
    }

    private fun improvesCurrentTotalConsistency(
        receipt: ParsedReceipt,
        currentItem: ParsedReceiptItem,
        candidateAmountMinor: Long
    ): Boolean {
        val total = receipt.detectedTotalMinor ?: return false
        val currentSum = receipt.items.sumOf(ParsedReceiptItem::amountMinor)
        val candidateSum = currentSum - currentItem.amountMinor + candidateAmountMinor
        return abs(total - candidateSum) < abs(total - currentSum)
    }

    private fun columnAlignmentScore(target: PriceRecoveryTarget, candidateBox: OcrBoundingBox): Double {
        val expectedX = target.expectedColumnX ?: return 0.5
        val candidateX = (candidateBox.left + candidateBox.right) / 2f
        val rowWidth = target.rowBoundingBox?.let { (it.right - it.left).coerceAtLeast(1f) } ?: 100f
        return (1.0 - kotlin.math.min(1.0, abs(candidateX - expectedX).toDouble() / (rowWidth * 0.15f))).coerceAtLeast(0.0)
    }

    private fun parseStrictMoney(value: String): Long? {
        val normalized = repairNumericOcr(normalizeDigits(value)).trim()
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

    private fun repairNumericOcr(value: String): String {
        val withoutCurrency = value.replace(CURRENCY_REGEX, "").trim()
        if (!withoutCurrency.any(Char::isDigit) || !NUMERIC_CROP_TEXT_REGEX.matches(withoutCurrency)) return value
        return withoutCurrency.map { character ->
            when (character) {
                'O', 'o' -> '0'
                'I', 'l', '|' -> '1'
                'B' -> '8'
                'S', 's' -> '5'
                else -> character
            }
        }.joinToString("")
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
        const val MAX_RECOVERY_ROWS = 6
        const val ARITHMETIC_TOLERANCE_MINOR = 2L
        val MAX_REASONABLE_WEIGHT: BigDecimal = BigDecimal("1000.000")
        const val TOTAL_TOLERANCE_MINOR = 2L
        val MONEY_REGEX = Regex("^(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:[.,]\\d{1,2})?$")
        val NUMERIC_CROP_TEXT_REGEX = Regex("[0-9OoIl|BSs., ]{1,24}")
        val CURRENCY_REGEX = Regex("(?i)(?:EGP|L\\.?E\\.?|ج(?:نيه)?|جم)")
    }
}
