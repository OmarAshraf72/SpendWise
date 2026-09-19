package com.example.spendwise.ocr

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptParserTest {
    private val parser = ReceiptParser()

    @Test
    fun parsesRealPharmacyReceiptWithoutTurningTotalsIntoItems() {
        val result = parser.parse(
            listOf(
                positioned("IMMULANT PLUS 20 CAP", 30f, 100f, 380f),
                positioned("60.00", 700f, 101f, 790f),
                positioned("FOLIC ACID 600 MCG", 30f, 140f, 390f),
                positioned("40.00", 700f, 141f, 790f),
                positioned("TOTAL", 30f, 230f, 180f),
                positioned("100.00", 700f, 231f, 790f),
                positioned("NET REQUIRED", 30f, 270f, 270f),
                positioned("100.00", 700f, 271f, 790f)
            )
        )

        assertEquals(
            listOf(
                ParsedReceiptItem("IMMULANT PLUS 20 CAP", 6_000L),
                ParsedReceiptItem("FOLIC ACID 600 MCG", 4_000L)
            ),
            result.items
        )
        assertEquals(10_000L, result.items.sumOf(ParsedReceiptItem::amountMinor))
        assertEquals(10_000L, result.detectedTotalMinor)
        assertEquals(ReceiptTotalConsistency.MATCH, result.totalConsistency)
    }

    @Test
    fun removesOverlappingDuplicateOcrLines() {
        val result = parser.parse(
            listOf(
                positioned("Milk", 30f, 100f, 250f),
                positioned("Milk", 32f, 102f, 252f),
                positioned("65.00", 700f, 100f, 790f),
                positioned("65.00", 701f, 102f, 791f),
                positioned("TOTAL", 30f, 180f, 180f),
                positioned("65.00", 700f, 180f, 790f)
            )
        )

        assertEquals(listOf(ParsedReceiptItem("Milk", 6_500L)), result.items)
        assertEquals(ReceiptTotalConsistency.MATCH, result.totalConsistency)
    }

    @Test
    fun mergesWrappedProductNameIntoPricedRow() {
        val result = parser.parse(
            listOf(
                positioned("FOLIC ACID 600 MCG 20", 30f, 100f, 420f),
                positioned("TAB (MEPACO)", 30f, 126f, 300f),
                positioned("40.00", 700f, 126f, 790f),
                positioned("TOTAL", 30f, 200f, 180f),
                positioned("40.00", 700f, 200f, 790f)
            )
        )

        assertEquals(1, result.items.size)
        assertEquals("FOLIC ACID 600 MCG 20 TAB (MEPACO)", result.items.single().name)
        assertEquals(4_000L, result.items.single().amountMinor)
    }

    @Test
    fun learnsLineTotalColumnInsteadOfUnitPriceOrQuantity() {
        val result = parser.parse(
            listOf(
                positioned("40.00", 30f, 100f, 100f),
                positioned("1.00", 150f, 100f, 210f),
                positioned("40.00", 280f, 100f, 350f),
                positioned("FOLIC ACID", 410f, 100f, 720f),
                positioned("60.00", 30f, 140f, 100f),
                positioned("2.00", 150f, 140f, 210f),
                positioned("120.00", 280f, 140f, 365f),
                positioned("IMMULANT PLUS", 410f, 140f, 720f),
                positioned("TOTAL", 30f, 220f, 180f),
                positioned("160.00", 280f, 220f, 365f)
            )
        )

        assertEquals(listOf(4_000L, 12_000L), result.items.map(ParsedReceiptItem::amountMinor))
        assertEquals(listOf("FOLIC ACID", "IMMULANT PLUS"), result.items.map(ParsedReceiptItem::name))
        assertEquals(16_000L, result.detectedTotalMinor)
        assertEquals(ReceiptTotalConsistency.MATCH, result.totalConsistency)
    }

    @Test
    fun excludesArabicSummaryKeywordsFromItems() {
        val result = parser.parse(
            listOf(
                positioned("بانادول", 30f, 100f, 300f),
                positioned("80.00", 700f, 100f, 790f),
                positioned("الإجمالي", 30f, 180f, 230f),
                positioned("80.00", 700f, 180f, 790f),
                positioned("الصافي المطلوب", 30f, 220f, 300f),
                positioned("80.00", 700f, 220f, 790f)
            )
        )

        assertEquals(listOf(ParsedReceiptItem("بانادول", 8_000L)), result.items)
        assertEquals(8_000L, result.detectedTotalMinor)
    }

    @Test
    fun excludesEverySupportedArabicSummaryAndPaymentLabel() {
        val labels = listOf(
            "الإجمالي", "اجمالي", "الإجمالى", "الصافي", "الصافى", "الصافي المطلوب",
            "المطلوب", "إجمالي المطلوب", "الخصم", "الضريبة", "القيمة المضافة", "نقدي", "الباقي"
        )

        labels.forEach { label ->
            assertEquals(label, emptyList<ParsedReceiptItem>(), parser.parse(listOf(line("$label 100.00"))).items)
        }
    }

    @Test
    fun preservesMixedArabicAndEnglishProductNames() {
        val result = parser.parse(
            listOf(
                positioned("Panadol مسكن", 30f, 100f, 350f),
                positioned("80.00", 700f, 100f, 790f)
            )
        )

        assertEquals(listOf(ParsedReceiptItem("Panadol مسكن", 8_000L)), result.items)
    }

    @Test
    fun marksLargeTotalMismatchForManualReviewWithoutInventingItems() {
        val result = parser.parse(listOf(line("Milk 65.00"), line("TOTAL 100.00")))

        assertEquals(listOf(ParsedReceiptItem("Milk", 6_500L)), result.items)
        assertEquals(10_000L, result.detectedTotalMinor)
        assertEquals(ReceiptTotalConsistency.MISMATCH, result.totalConsistency)
    }

    @Test
    fun parsesMixedReceiptAndSkipsTotalAndNoise() {
        val result = parser.parse(
            listOf(
                line("Carrefour"),
                line("Date 19/09/2026"),
                line("Milk 65.00"),
                line("Panadol 80"),
                line("Invoice 123456789"),
                line("الإجمالي 145.00")
            )
        )

        assertEquals("Carrefour", result.merchant)
        assertEquals(
            LocalDate.of(2026, 9, 19).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            result.transactionDate
        )
        assertEquals(listOf(6_500L, 8_000L), result.items.map(ParsedReceiptItem::amountMinor))
        assertEquals(14_500L, result.detectedTotalMinor)
    }

    @Test
    fun rejectsQuantityPhoneAndTimestampAsItems() {
        val result = parser.parse(
            listOf(
                line("Shop"),
                line("Phone 01012345678"),
                line("Qty 2 x 65"),
                line("Time 13:45"),
                line("TOTAL 130")
            )
        )

        assertEquals(emptyList<ParsedReceiptItem>(), result.items)
        assertEquals(13_000L, result.detectedTotalMinor)
    }

    @Test
    fun returnsEmptySuggestionsForUnusableText() {
        val result = parser.parse(listOf(line("123456789"), line("13:45")))

        assertNull(result.merchant)
        assertEquals(emptyList<ParsedReceiptItem>(), result.items)
        assertNull(result.detectedTotalMinor)
    }

    private fun line(text: String) = OcrLine(text, confidence = 0.95f, boundingBox = null)

    private fun positioned(text: String, left: Float, top: Float, right: Float) =
        OcrLine(text, confidence = 0.95f, boundingBox = box(left, top, right, top + 20f))

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = OcrBoundingBox(
        listOf(OcrPoint(left, top), OcrPoint(right, top), OcrPoint(right, bottom), OcrPoint(left, bottom))
    )
}
