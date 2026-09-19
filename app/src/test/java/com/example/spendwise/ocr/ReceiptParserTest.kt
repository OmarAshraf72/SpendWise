package com.example.spendwise.ocr

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptParserTest {
    private val parser = ReceiptParser()

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
        assertEquals(listOf(6_500L, 8_000L), result.items.map { it.amountMinor })
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

    @Test
    fun pairsSpatiallyAlignedItemNameAndPrice() {
        val result = parser.parse(
            listOf(
                OcrLine("Milk", 0.9f, box(10f, 30f, 100f, 50f)),
                OcrLine("65.00", 0.9f, box(220f, 31f, 280f, 51f))
            )
        )

        assertEquals(listOf(ParsedReceiptItem("Milk", 6_500L)), result.items)
    }

    private fun line(text: String) = OcrLine(text, confidence = 0.95f, boundingBox = null)

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = OcrBoundingBox(
        listOf(OcrPoint(left, top), OcrPoint(right, top), OcrPoint(right, bottom), OcrPoint(left, bottom))
    )
}
