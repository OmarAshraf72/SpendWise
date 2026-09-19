package com.example.spendwise.ocr

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneOffset

data class ParsedReceiptItem(val name: String, val amountMinor: Long)

data class ParsedReceipt(
    val merchant: String?,
    val transactionDate: Long?,
    val items: List<ParsedReceiptItem>,
    val detectedTotalMinor: Long?
)

class ReceiptParser {
    fun parse(lines: List<OcrLine>): ParsedReceipt {
        val ordered = lines
            .filter { it.text.isNotBlank() && (it.confidence == null || it.confidence >= MIN_CONFIDENCE) }
            .sortedWith(compareBy<OcrLine> { it.boundingBox?.centerY ?: Float.MAX_VALUE }
                .thenBy { it.boundingBox?.left ?: Float.MAX_VALUE })
        val originalTexts = ordered.map { normalizeDigits(it.text).trim() }.filter(String::isNotBlank)
        val mergedRows = mergeSpatialRows(ordered).filter { it.isNotBlank() && it !in originalTexts }.distinct()
        val texts = originalTexts + mergedRows
        val total = texts.asSequence()
            .filter { containsTotalKeyword(it) && !containsExcludedTotalKeyword(it) }
            .mapNotNull(::lastMoneyValue)
            .lastOrNull()
        val date = texts.asSequence().mapNotNull(::parseDate).firstOrNull()
        val merchant = texts.asSequence()
            .take(6)
            .firstOrNull(::isMerchantCandidate)
        val items = texts.mapNotNull { parseItem(it, total) }

        return ParsedReceipt(
            merchant = merchant,
            transactionDate = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            items = items,
            detectedTotalMinor = total
        )
    }

    private fun mergeSpatialRows(lines: List<OcrLine>): List<String> {
        val positioned = lines.filter { it.boundingBox != null }
        val rows = mutableListOf<MutableList<OcrLine>>()
        positioned.forEach { line ->
            val box = checkNotNull(line.boundingBox)
            val matchingRow = rows.firstOrNull { row ->
                val rowBox = checkNotNull(row.first().boundingBox)
                val tolerance = maxOf(rowBox.bottom - rowBox.top, box.bottom - box.top) * 0.65f
                kotlin.math.abs(rowBox.centerY - box.centerY) <= tolerance
            }
            if (matchingRow == null) rows += mutableListOf(line) else matchingRow += line
        }
        return rows.filter { it.size > 1 }.flatMap { row ->
            val parts = row.sortedBy { it.boundingBox?.left }.map { normalizeDigits(it.text).trim() }
            listOf(parts.joinToString(" "), parts.asReversed().joinToString(" "))
        }
    }

    private fun parseItem(text: String, detectedTotal: Long?): ParsedReceiptItem? {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.length < 3 || containsExcludedKeyword(normalized) || containsTotalKeyword(normalized)) return null
        if (DATE_REGEX.containsMatchIn(normalized) || TIME_REGEX.containsMatchIn(normalized)) return null
        if (LONG_NUMBER_REGEX.containsMatchIn(normalized) || QUANTITY_REGEX.containsMatchIn(normalized)) return null

        val match = TRAILING_MONEY_REGEX.find(normalized) ?: return null
        val amount = parseMoney(match.groupValues[1]) ?: return null
        if (amount <= 0L || amount == detectedTotal) return null
        val name = normalized.substring(0, match.range.first)
            .trim(' ', '-', ':', '.', '\u2022')
            .replace(Regex("\\s+"), " ")
        if (name.length < 2 || !LETTER_REGEX.containsMatchIn(name)) return null
        if (name.count(Char::isDigit) > name.length / 2) return null
        return ParsedReceiptItem(name, amount)
    }

    private fun isMerchantCandidate(text: String): Boolean {
        if (text.length !in 2..60 || !LETTER_REGEX.containsMatchIn(text)) return false
        if (containsExcludedKeyword(text) || containsTotalKeyword(text)) return false
        if (DATE_REGEX.containsMatchIn(text) || TIME_REGEX.containsMatchIn(text)) return false
        if (TRAILING_MONEY_REGEX.containsMatchIn(text)) return false
        return text.count(Char::isDigit) <= text.length / 3
    }

    private fun parseDate(text: String): LocalDate? {
        val match = DATE_REGEX.find(text) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val third = match.groupValues[3].toIntOrNull() ?: return null
        val (year, day) = if (match.groupValues[1].length == 4) first to third else third to first
        val normalizedYear = if (year in 0..99) 2000 + year else year
        if (normalizedYear !in 2000..2100) return null
        return try {
            LocalDate.of(normalizedYear, month, day)
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun lastMoneyValue(text: String): Long? = MONEY_TOKEN_REGEX.findAll(text)
        .mapNotNull { parseMoney(it.groupValues[1]) }
        .lastOrNull()

    private fun parseMoney(raw: String): Long? = runCatching {
        BigDecimal(raw.replace(",", ""))
            .setScale(2, RoundingMode.UNNECESSARY)
            .movePointRight(2)
            .longValueExact()
    }.getOrNull()

    private fun containsTotalKeyword(text: String): Boolean = TOTAL_KEYWORDS.any {
        text.contains(it, ignoreCase = true)
    }

    private fun containsExcludedTotalKeyword(text: String): Boolean = EXCLUDED_TOTAL_KEYWORDS.any {
        text.contains(it, ignoreCase = true)
    }

    private fun containsExcludedKeyword(text: String): Boolean = EXCLUDED_KEYWORDS.any {
        text.contains(it, ignoreCase = true)
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

    private companion object {
        const val MIN_CONFIDENCE = 0.35f
        val LETTER_REGEX = Regex("[A-Za-z\\p{IsArabic}]")
        val DATE_REGEX = Regex("\\b(\\d{4}|\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})\\b")
        val TIME_REGEX = Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b")
        val LONG_NUMBER_REGEX = Regex("(?<!\\d)\\d{7,}(?!\\d)")
        val QUANTITY_REGEX = Regex("(?i)(?:\\bqty\\b|\\bquantity\\b|\\d+\\s*[xX×]\\s*\\d+|\\bعدد\\b|\\bكمية\\b)")
        val MONEY_TOKEN_REGEX = Regex("(?<!\\d)(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)(?![\\d:])")
        val TRAILING_MONEY_REGEX = Regex("(?i)(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)\\s*(?:EGP|L\\.?E\\.?|ج(?:نيه)?|جم)?\\s*$")
        val TOTAL_KEYWORDS = listOf("grand total", "amount due", "total", "الإجمالي", "الاجمالي", "اجمالي", "المجموع")
        val EXCLUDED_TOTAL_KEYWORDS = listOf("subtotal", "before total", "المجموع الفرعي")
        val EXCLUDED_KEYWORDS = listOf(
            "subtotal", "tax", "vat", "invoice", "receipt", "phone", "tel", "cash", "change",
            "discount", "date", "time", "ضريبة", "ضريبه", "فاتورة", "إيصال", "ايصال", "هاتف",
            "تليفون", "تلفون", "نقد", "الباقي", "خصم", "تاريخ", "وقت"
        )
    }
}
