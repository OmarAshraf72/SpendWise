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
            "المطلوب", "إجمالي المطلوب", "المجموع", "المبلغ", "المدفوع", "متبقي", "المتبقي",
            "الخصم", "الضريبة", "القيمة المضافة", "نقدي", "كاش", "الباقي"
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
    fun parsesRealArabicProduceReceiptUsingColumnsArithmeticAndFooterBoundary() {
        val result = parser.parse(
            buildList {
                add(positioned("فواكه و خضروات", 330f, 30f, 610f))
                add(positioned("إسلام و ميدو", 350f, 55f, 650f))
                add(positioned("01275129516 - 01203014491", 330f, 90f, 760f))
                add(positioned("2026.09.16", 470f, 130f, 610f))
                add(positioned("الصنف", 310f, 180f, 390f))
                add(positioned("السعر", 500f, 180f, 570f))
                add(positioned("الوزن", 610f, 180f, 680f))
                add(positioned("القيمة", 710f, 180f, 790f))
                addAll(produceRow("برقوق", "130.00", "0.840", "109.2O", 220f))
                addAll(produceRow("جوافة", "85.00", "0.635", "53.90", 265f))
                addAll(produceRow("خيار", "25.00", "0.450", "11.25", 310f))
                addAll(produceRow("رمان", "60.00", "1.175", "70.50", 355f))
                addAll(produceRow("تفاح أصفر", "100.00", "0.810", "1.00", 400f))
                add(positioned("تين", 310f, 447f, 450f, confidence = 0.20f))
                add(positioned("75.00", 500f, 445f, 575f))
                add(positioned("0.9B5", 610f, 443f, 680f))
                add(positioned("73.8B", 710f, 446f, 790f))
                add(positioned("الإجمالي", 390f, 520f, 520f))
                add(positioned("399.81", 650f, 520f, 790f))
                add(positioned("المدفوع", 390f, 555f, 520f))
                add(positioned("399.81", 650f, 555f, 790f))
                add(positioned("متبقي", 390f, 590f, 500f))
                add(positioned("0.00", 680f, 590f, 790f))
            }
        )

        assertEquals("فواكه و خضروات إسلام و ميدو", result.merchant)
        assertEquals(
            LocalDate.of(2026, 9, 16).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            result.transactionDate
        )
        assertEquals(
            listOf("برقوق", "جوافة", "خيار", "رمان", "تفاح أصفر", "تين"),
            result.items.map(ParsedReceiptItem::name)
        )
        assertEquals(
            listOf(10_920L, 5_398L, 1_125L, 7_050L, 8_100L, 7_388L),
            result.items.map(ParsedReceiptItem::amountMinor)
        )
        assertEquals(39_981L, result.items.sumOf(ParsedReceiptItem::amountMinor))
        assertEquals(39_981L, result.detectedTotalMinor)
        assertEquals(ReceiptTotalConsistency.MATCH, result.totalConsistency)
        assertEquals(
            listOf(PriceSource.ARITHMETIC_RECOVERY, PriceSource.ARITHMETIC_RECOVERY),
            result.items.filter(ParsedReceiptItem::requiresReview).map(ParsedReceiptItem::priceSource)
        )
        assertEquals(6, result.items.size)
        assertEquals(false, result.items.first().name.contains("الصنف"))
    }

    @Test
    fun keepsStructuredRowWhenLineTotalOcrIsMissing() {
        val result = parser.parse(
            buildList {
                add(positioned("الصنف", 310f, 180f, 390f))
                add(positioned("السعر", 500f, 180f, 570f))
                add(positioned("الوزن", 610f, 180f, 680f))
                add(positioned("القيمة", 710f, 180f, 790f))
                add(positioned("تفاح أصفر", 310f, 220f, 450f))
                add(positioned("100.00", 500f, 220f, 575f))
                add(positioned("0.810", 610f, 222f, 680f))
                add(positioned("الإجمالي", 390f, 300f, 520f))
                add(positioned("81.00", 710f, 300f, 790f))
            }
        )

        assertEquals(listOf("تفاح أصفر"), result.items.map(ParsedReceiptItem::name))
        assertEquals(listOf(8_100L), result.items.map(ParsedReceiptItem::amountMinor))
        assertEquals(PriceSource.ARITHMETIC_RECOVERY, result.items.single().priceSource)
        assertEquals(true, result.items.single().requiresReview)
        assertEquals(ReceiptTotalConsistency.MATCH, result.totalConsistency)
        assertEquals(
            setOf(NumericCellRole.UNIT_PRICE, NumericCellRole.WEIGHT, NumericCellRole.LINE_TOTAL),
            ReceiptNumericRecovery().selectTargets(result).map(PriceRecoveryTarget::role).toSet()
        )
        assertEquals(true, result.tableDebugDetails.contains("headerAnchored=true"))
    }

    @Test
    fun parsesSupportedReceiptDateSeparatorsAndOrders() {
        listOf(
            "2026.09.16" to LocalDate.of(2026, 9, 16),
            "2026-09-16" to LocalDate.of(2026, 9, 16),
            "16/09/2026" to LocalDate.of(2026, 9, 16),
            "16-09-2026" to LocalDate.of(2026, 9, 16)
        ).forEach { (value, expected) ->
            val parsed = parser.parse(listOf(line(value)))
            assertEquals(
                value,
                expected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                parsed.transactionDate
            )
        }
    }

    @Test
    fun removesWeightJoinedToProductNameWithoutRemovingProductDosageNumbers() {
        val result = parser.parse(
            buildList {
                add(positioned("برقوق 0.840", 310f, 100f, 470f))
                add(positioned("130.00", 500f, 100f, 575f))
                add(positioned("0.840", 610f, 100f, 680f))
                add(positioned("109.20", 710f, 100f, 790f))
                addAll(produceRow("Folic Acid 600 MCG", "40.00", "1.000", "40.00", 145f))
                add(positioned("TOTAL", 310f, 210f, 430f))
                add(positioned("149.20", 710f, 210f, 790f))
            }
        )

        assertEquals(listOf("برقوق", "Folic Acid 600 MCG"), result.items.map(ParsedReceiptItem::name))
    }

    @Test
    fun returnsEmptySuggestionsForUnusableText() {
        val result = parser.parse(listOf(line("123456789"), line("13:45")))

        assertNull(result.merchant)
        assertEquals(emptyList<ParsedReceiptItem>(), result.items)
        assertNull(result.detectedTotalMinor)
    }

    private fun line(text: String) = OcrLine(text, confidence = 0.95f, boundingBox = null)

    private fun positioned(text: String, left: Float, top: Float, right: Float, confidence: Float = 0.95f) =
        OcrLine(text, confidence = confidence, boundingBox = box(left, top, right, top + 20f))

    private fun produceRow(name: String, unitPrice: String, weight: String, total: String, top: Float) = listOf(
        positioned(name, 310f, top, 450f),
        positioned(unitPrice, 500f, top, 575f),
        positioned(weight, 610f, top, 680f),
        positioned(total, 710f, top, 790f)
    )

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = OcrBoundingBox(
        listOf(OcrPoint(left, top), OcrPoint(right, top), OcrPoint(right, bottom), OcrPoint(left, bottom))
    )
}
