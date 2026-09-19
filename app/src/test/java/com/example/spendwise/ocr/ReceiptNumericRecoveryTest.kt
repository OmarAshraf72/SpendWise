package com.example.spendwise.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptNumericRecoveryTest {
    private val parser = ReceiptParser()
    private val recovery = ReceiptNumericRecovery()

    @Test
    fun recoversMisreadPharmacyPriceFromTargetedNumericCandidates() {
        val parsed = parser.parse(
            listOf(
                positioned("IMMULANT PLUS 20 CAP", 30f, 100f, 380f),
                positioned("5.05", 700f, 100f, 790f, confidence = 0.62f),
                positioned("FOLIC ACID 600 MCG", 30f, 140f, 390f),
                positioned("40.00", 700f, 140f, 790f),
                positioned("TOTAL", 30f, 220f, 180f),
                positioned("100.00", 700f, 220f, 790f),
                positioned("NET REQUIRED", 30f, 260f, 270f),
                positioned("100.00", 700f, 260f, 790f)
            )
        )
        assertEquals(listOf(505L, 4_000L), parsed.items.map(ParsedReceiptItem::amountMinor))
        val target = parsed.recoveryTargets.first { it.itemIndex == 0 }
        assertTrue(recovery.selectTargets(parsed).any { it.itemIndex == 0 })
        val candidateBox = requireNotNull(target.priceBoundingBox)

        val recovered = recovery.recover(
            parsed,
            listOf(
                NumericOcrCandidate(0, "60.00", 0.93f, "contrast_3x", candidateBox),
                NumericOcrCandidate(0, "60.00", 0.90f, "adaptive_threshold_4x", candidateBox),
                NumericOcrCandidate(0, "5.05", 0.58f, "grayscale_3x", candidateBox)
            )
        ).receipt

        assertEquals(
            listOf("IMMULANT PLUS 20 CAP", "FOLIC ACID 600 MCG"),
            recovered.items.map(ParsedReceiptItem::name)
        )
        assertEquals(listOf(6_000L, 4_000L), recovered.items.map(ParsedReceiptItem::amountMinor))
        assertEquals(10_000L, recovered.detectedTotalMinor)
        assertEquals(10_000L, recovered.items.sumOf(ParsedReceiptItem::amountMinor))
        assertEquals(ReceiptTotalConsistency.MATCH, recovered.totalConsistency)
        assertEquals(PriceSource.OCR_NUMERIC_RECOVERY, recovered.items.first().priceSource)
        assertFalse(recovered.items.first().requiresReview)
    }

    @Test
    fun weakCandidateIsNotChosenOnlyBecauseItMatchesTotal() {
        val parsed = parser.parse(
            listOf(
                positioned("IMMULANT", 30f, 100f, 300f),
                positioned("5.05", 700f, 100f, 790f, confidence = 0.9f),
                positioned("FOLIC ACID", 30f, 140f, 300f),
                positioned("40.00", 700f, 140f, 790f),
                positioned("TOTAL", 30f, 220f, 180f),
                positioned("100.00", 700f, 220f, 790f)
            )
        )
        val target = parsed.recoveryTargets.first { it.itemIndex == 0 }

        val recovered = recovery.recover(
            parsed,
            listOf(
                NumericOcrCandidate(
                    0, "60.00", 0.2f, "adaptive_threshold_4x", requireNotNull(target.priceBoundingBox)
                ),
                NumericOcrCandidate(
                    0, "PRICE 60.00", 0.99f, "contrast_3x", requireNotNull(target.priceBoundingBox)
                )
            )
        ).receipt

        assertEquals(505L, recovered.items.first().amountMinor)
        assertEquals(PriceSource.OCR_ORIGINAL, recovered.items.first().priceSource)
    }

    @Test
    fun reconciliationRequiresReliableTotalSingleUncertainItemAndNoAdjustments() {
        val parsed = parser.parse(
            listOf(
                positioned("IMMULANT", 30f, 100f, 300f),
                positioned("5.05", 700f, 100f, 790f, confidence = 0.55f),
                positioned("FOLIC ACID", 30f, 140f, 300f),
                positioned("40.00", 700f, 140f, 790f, confidence = 0.95f),
                positioned("TOTAL", 30f, 220f, 180f),
                positioned("100.00", 700f, 220f, 790f),
                positioned("NET REQUIRED", 30f, 260f, 270f),
                positioned("100.00", 700f, 260f, 790f)
            )
        )

        val recovered = recovery.recover(parsed, emptyList()).receipt

        assertEquals(6_000L, recovered.items.first().amountMinor)
        assertEquals(PriceSource.TOTAL_RECONCILIATION, recovered.items.first().priceSource)
        assertTrue(recovered.items.first().requiresReview)
    }

    @Test
    fun reconciliationIsDisabledWhenReceiptContainsAdjustments() {
        val parsed = parser.parse(
            listOf(
                positioned("IMMULANT", 30f, 100f, 300f),
                positioned("5.05", 700f, 100f, 790f, confidence = 0.55f),
                positioned("FOLIC ACID", 30f, 140f, 300f),
                positioned("40.00", 700f, 140f, 790f),
                positioned("VAT", 30f, 180f, 120f),
                positioned("0.00", 700f, 180f, 790f),
                positioned("TOTAL", 30f, 220f, 180f),
                positioned("100.00", 700f, 220f, 790f),
                positioned("NET REQUIRED", 30f, 260f, 270f),
                positioned("100.00", 700f, 260f, 790f)
            )
        )

        val recovered = recovery.recover(parsed, emptyList()).receipt

        assertTrue(parsed.hasAdjustments)
        assertEquals(505L, recovered.items.first().amountMinor)
        assertEquals(PriceSource.OCR_ORIGINAL, recovered.items.first().priceSource)
    }

    private fun positioned(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        confidence: Float = 0.95f
    ) = OcrLine(text, confidence, box(left, top, right, top + 20f))

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = OcrBoundingBox(
        listOf(OcrPoint(left, top), OcrPoint(right, top), OcrPoint(right, bottom), OcrPoint(left, bottom))
    )
}
