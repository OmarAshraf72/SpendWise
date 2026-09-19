package com.example.spendwise.ocr

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ParsedReceiptItem(val name: String, val amountMinor: Long)

enum class ReceiptTotalConsistency { NOT_AVAILABLE, MATCH, MISMATCH }

data class ParsedReceipt(
    val merchant: String?,
    val transactionDate: Long?,
    val items: List<ParsedReceiptItem>,
    val detectedTotalMinor: Long?,
    val totalConsistency: ReceiptTotalConsistency
)

class ReceiptParser {
    fun parse(lines: List<OcrLine>): ParsedReceipt {
        val fragments = deduplicateFragments(
            lines.mapNotNull(::toFragment)
                .sortedWith(compareBy<Fragment> { it.box?.centerY ?: Float.MAX_VALUE }
                    .thenBy { it.box?.left ?: Float.MAX_VALUE })
        )
        val rows = reconstructRows(fragments)
        val detectedTotal = detectReceiptTotal(rows.filter(::isSummaryRow))
        val candidateRows = rows.filter(::canBeProductRow)
        val receiptWidth = fragments.mapNotNull { it.box?.right }.maxOrNull()
            ?.minus(fragments.mapNotNull { it.box?.left }.minOrNull() ?: 0f)
            ?.coerceAtLeast(1f)
        val columns = inferNumericColumns(candidateRows, receiptWidth)
        val lineTotalColumn = chooseLineTotalColumn(columns, candidateRows, detectedTotal)
        val rowItems = candidateRows.mapNotNull { row -> parseProductRow(row, columns, lineTotalColumn) }
        val items = deduplicateItems(attachWrappedNames(rowItems, rows))
            .map { ParsedReceiptItem(it.name, it.amountMinor) }
        val itemTotal = items.fold(0L) { total, item -> total + item.amountMinor }
        val consistency = when {
            detectedTotal == null -> ReceiptTotalConsistency.NOT_AVAILABLE
            abs(itemTotal - detectedTotal) <= TOTAL_TOLERANCE_MINOR -> ReceiptTotalConsistency.MATCH
            else -> ReceiptTotalConsistency.MISMATCH
        }
        val rowTexts = rows.flatMap(LogicalRow::textVariants)
        val date = rowTexts.asSequence().mapNotNull(::parseDate).firstOrNull()
        val merchant = rows.asSequence().take(6).map(LogicalRow::preferredText)
            .firstOrNull(::isMerchantCandidate)

        return ParsedReceipt(
            merchant = merchant,
            transactionDate = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            items = items,
            detectedTotalMinor = detectedTotal,
            totalConsistency = consistency
        )
    }

    private fun toFragment(line: OcrLine): Fragment? {
        if (line.confidence != null && line.confidence < MIN_CONFIDENCE) return null
        val text = normalizeDigits(line.text).replace(WHITESPACE_REGEX, " ").trim()
        return text.takeIf(String::isNotBlank)?.let { Fragment(it, line.boundingBox, line.confidence) }
    }

    private fun deduplicateFragments(fragments: List<Fragment>): List<Fragment> {
        val kept = mutableListOf<Fragment>()
        fragments.forEach { candidate ->
            val duplicateIndex = kept.indexOfFirst { existing -> areDuplicateFragments(existing, candidate) }
            if (duplicateIndex < 0) kept += candidate
            else if ((candidate.confidence ?: 0f) > (kept[duplicateIndex].confidence ?: 0f)) kept[duplicateIndex] = candidate
        }
        return kept
    }

    private fun areDuplicateFragments(first: Fragment, second: Fragment): Boolean {
        val firstBox = first.box ?: return false
        val secondBox = second.box ?: return false
        val similarity = textSimilarity(normalizeProductName(first.text), normalizeProductName(second.text))
        if (similarity < DUPLICATE_TEXT_SIMILARITY) return false
        val verticalOverlap = overlapRatio(firstBox.top, firstBox.bottom, secondBox.top, secondBox.bottom)
        val horizontalOverlap = overlapRatio(firstBox.left, firstBox.right, secondBox.left, secondBox.right)
        return verticalOverlap >= 0.55f && horizontalOverlap >= 0.45f
    }

    private fun reconstructRows(fragments: List<Fragment>): List<LogicalRow> {
        val groups = mutableListOf<MutableList<Fragment>>()
        fragments.forEach { fragment ->
            val box = fragment.box
            if (box == null) {
                groups += mutableListOf(fragment)
            } else {
                val group = groups.filter { it.any { part -> part.box != null } }
                    .minByOrNull { parts -> abs(checkNotNull(rowBoxOrNull(parts)).centerY - box.centerY) }
                    ?.takeIf { parts -> belongsToSameRow(checkNotNull(rowBoxOrNull(parts)), box) }
                if (group == null) groups += mutableListOf(fragment) else group += fragment
            }
        }
        return groups.mapIndexed { index, parts ->
            LogicalRow(index, parts.sortedBy { it.box?.left ?: Float.MAX_VALUE }, rowBoxOrNull(parts))
        }.sortedWith(compareBy<LogicalRow> { it.box?.top ?: Float.MAX_VALUE }.thenBy(LogicalRow::index))
            .mapIndexed { index, row -> row.copy(index = index) }
    }

    private fun belongsToSameRow(row: OcrBoundingBox, candidate: OcrBoundingBox): Boolean {
        val overlap = overlapRatio(row.top, row.bottom, candidate.top, candidate.bottom)
        val centerDistance = abs(row.centerY - candidate.centerY)
        return overlap >= ROW_VERTICAL_OVERLAP || centerDistance <= max(row.height, candidate.height) * ROW_CENTER_TOLERANCE
    }

    private fun detectReceiptTotal(summaryRows: List<LogicalRow>): Long? = summaryRows.mapNotNull { row ->
        val priority = totalPriority(row) ?: return@mapNotNull null
        val amount = row.numericCells().lastOrNull()?.amountMinor ?: return@mapNotNull null
        TotalCandidate(priority, row.box?.centerY ?: row.index.toFloat(), amount)
    }.maxWithOrNull(compareBy<TotalCandidate> { it.priority }.thenBy(TotalCandidate::y))?.amountMinor

    private fun totalPriority(row: LogicalRow): Int? {
        val normalized = row.textVariants.joinToString(" ") { normalizeForKeywords(it) }
        return when {
            REQUIRED_TOTAL_KEYWORDS.any(normalized::contains) -> 5
            PRIMARY_TOTAL_KEYWORDS.any(normalized::contains) -> 4
            containsEnglishWord(normalized, "net") -> 3
            else -> null
        }
    }

    private fun inferNumericColumns(rows: List<LogicalRow>, receiptWidth: Float?): List<NumericColumn> {
        if (receiptWidth == null) return emptyList()
        val tolerance = max(MIN_COLUMN_TOLERANCE, receiptWidth * COLUMN_TOLERANCE_RATIO)
        val clusters = mutableListOf<MutableList<NumericCell>>()
        rows.flatMap { row -> row.numericCells().filter { it.isStrongMoney && it.x != null } }
            .sortedBy(NumericCell::x)
            .forEach { cell ->
                val cellX = checkNotNull(cell.x)
                val cluster = clusters.minByOrNull { cells -> abs(cells.mapNotNull(NumericCell::x).average().toFloat() - cellX) }
                    ?.takeIf { cells -> abs(cells.mapNotNull(NumericCell::x).average().toFloat() - cellX) <= tolerance }
                if (cluster == null) clusters += mutableListOf(cell) else cluster += cell
            }
        return clusters.map { cells ->
            NumericColumn(
                centerX = cells.mapNotNull(NumericCell::x).average().toFloat(),
                cells = cells,
                support = cells.map(NumericCell::rowIndex).distinct().size
            )
        }.filter { it.support >= MIN_COLUMN_SUPPORT }
    }

    private fun chooseLineTotalColumn(
        columns: List<NumericColumn>,
        rows: List<LogicalRow>,
        detectedTotal: Long?
    ): NumericColumn? {
        if (columns.isEmpty()) return null
        val averageLetterX = rows.flatMap(LogicalRow::fragments)
            .filter { LETTER_REGEX.containsMatchIn(it.text) }
            .mapNotNull { fragment -> fragment.box?.let { box -> (box.left + box.right) / 2f } }
            .average().takeUnless(Double::isNaN)?.toFloat()
        return columns.maxByOrNull { column ->
            val values = rows.mapNotNull { row -> column.cellFor(row.numericCells()) }
            val sum = values.fold(0L) { total, cell -> total + cell.amountMinor }
            var score = column.support * 200.0
            if (detectedTotal != null && detectedTotal > 0L) {
                val difference = abs(sum - detectedTotal)
                score += if (difference <= TOTAL_TOLERANCE_MINOR) 20_000.0
                else max(0.0, 8_000.0 * (1.0 - difference.toDouble() / detectedTotal))
            }
            val likelyQuantity = values.isNotEmpty() && values.count { it.amountMinor in 1L..1_000L } >= (values.size + 1) / 2
            if (likelyQuantity) score -= 1_500.0
            if (averageLetterX != null) score += abs(column.centerX - averageLetterX) * 0.05
            score
        }
    }

    private fun parseProductRow(row: LogicalRow, columns: List<NumericColumn>, lineTotalColumn: NumericColumn?): RowItem? {
        if (!canBeProductRow(row)) return null
        val cells = row.numericCells()
        val selected = lineTotalColumn?.cellFor(cells) ?: chooseFallbackPrice(row, cells) ?: return null
        if (selected.amountMinor <= 0L) return null
        val learnedCells = if (columns.isEmpty()) listOf(selected) else cells.filter { cell ->
            cell.x != null && columns.any { column -> abs(column.centerX - cell.x) <= column.matchTolerance }
        }.ifEmpty { listOf(selected) }
        val remove = (learnedCells + selected).distinctBy { Triple(it.fragmentIndex, it.range.first, it.range.last) }
        val name = buildProductName(row, remove)
        if (!isValidProductName(name)) return null
        return RowItem(name, selected.amountMinor, row.index, row.box)
    }

    private fun chooseFallbackPrice(row: LogicalRow, cells: List<NumericCell>): NumericCell? {
        val strong = cells.filter(NumericCell::isStrongMoney)
        if (strong.size == 1) return strong.single()
        if (strong.isNotEmpty()) {
            val letterCenter = row.fragments.filter { LETTER_REGEX.containsMatchIn(it.text) }
                .mapNotNull { fragment -> fragment.box?.let { box -> (box.left + box.right) / 2f } }
                .average().takeUnless(Double::isNaN)?.toFloat()
            if (letterCenter != null) return strong.maxByOrNull { abs((it.x ?: letterCenter) - letterCenter) }
            return strong.lastOrNull()
        }
        if (cells.size != 1) return null
        return cells.lastOrNull { cell ->
            val fragment = row.fragments[cell.fragmentIndex]
            if (row.box != null && LETTER_REGEX.containsMatchIn(fragment.text)) return@lastOrNull false
            cell.range.last >= fragment.text.lastIndex - 1 && fragment.text.count(Char::isDigit) <= fragment.text.length / 2
        }
    }

    private fun buildProductName(row: LogicalRow, cellsToRemove: List<NumericCell>): String {
        val removalsByFragment = cellsToRemove.groupBy(NumericCell::fragmentIndex)
        return row.fragments.mapIndexedNotNull { index, fragment ->
            val chars = fragment.text.toCharArray()
            removalsByFragment[index].orEmpty().forEach { cell ->
                cell.range.forEach { position -> if (position in chars.indices) chars[position] = ' ' }
            }
            String(chars).takeUnless { text -> text.trim().matches(CURRENCY_ONLY_REGEX) }
        }.joinToString(" ")
            .replace(QUANTITY_LABEL_REGEX, " ")
            .replace(CURRENCY_REGEX, " ")
            .replace(SEPARATOR_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun attachWrappedNames(items: List<RowItem>, rows: List<LogicalRow>): List<RowItem> {
        if (items.isEmpty()) return items
        val byRow = items.associateBy(RowItem::rowIndex)
        val claimed = mutableSetOf<Int>()
        return items.map { item ->
            val prefixes = mutableListOf<String>()
            var previousIndex = item.rowIndex - 1
            var nextBox = item.box
            while (previousIndex >= 0 && prefixes.size < MAX_WRAPPED_LINES) {
                if (byRow.containsKey(previousIndex) || previousIndex in claimed) break
                val row = rows[previousIndex]
                if (!looksLikeContinuation(row) || !areVerticallyAdjacent(row.box, nextBox)) break
                prefixes += row.preferredText
                claimed += previousIndex
                nextBox = row.box
                previousIndex--
            }
            item.copy(name = (prefixes.asReversed() + item.name).joinToString(" ").replace(WHITESPACE_REGEX, " ").trim())
        }
    }

    private fun deduplicateItems(items: List<RowItem>): List<RowItem> {
        val kept = mutableListOf<RowItem>()
        items.forEach { candidate ->
            val duplicate = kept.any { existing ->
                if (existing.amountMinor != candidate.amountMinor) return@any false
                val similarity = textSimilarity(normalizeProductName(existing.name), normalizeProductName(candidate.name))
                if (similarity < DUPLICATE_TEXT_SIMILARITY) return@any false
                val firstBox = existing.box
                val secondBox = candidate.box
                firstBox != null && secondBox != null && (
                    overlapRatio(firstBox.top, firstBox.bottom, secondBox.top, secondBox.bottom) >= 0.35f ||
                        abs(firstBox.centerY - secondBox.centerY) <= max(firstBox.height, secondBox.height)
                    )
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private fun canBeProductRow(row: LogicalRow): Boolean {
        val text = row.textVariants.joinToString(" ")
        if (!LETTER_REGEX.containsMatchIn(text) || isSummaryRow(row)) return false
        if (QUANTITY_LABEL_REGEX.containsMatchIn(text) &&
            !LETTER_REGEX.containsMatchIn(text.replace(QUANTITY_ONLY_CONTENT_REGEX, " "))
        ) return false
        if (DATE_REGEX.containsMatchIn(text) || TIME_REGEX.containsMatchIn(text)) return false
        if (LONG_NUMBER_REGEX.containsMatchIn(text) || NOISE_KEYWORDS.any { containsKeyword(text, it) }) return false
        return true
    }

    private fun looksLikeContinuation(row: LogicalRow): Boolean {
        val text = row.preferredText
        return canBeProductRow(row) && row.numericCells().none(NumericCell::isStrongMoney) && text.length in 2..MAX_PRODUCT_NAME_LENGTH
    }

    private fun areVerticallyAdjacent(first: OcrBoundingBox?, second: OcrBoundingBox?): Boolean {
        if (first == null || second == null) return false
        val gap = max(0f, second.top - first.bottom)
        return gap <= max(first.height, second.height) * WRAPPED_LINE_GAP_RATIO
    }

    private fun isSummaryRow(row: LogicalRow): Boolean = row.textVariants.any(::containsSummaryKeyword)

    private fun containsSummaryKeyword(text: String): Boolean {
        val normalized = normalizeForKeywords(text)
        return SUMMARY_ENGLISH_REGEX.containsMatchIn(normalized) || SUMMARY_ARABIC_KEYWORDS.any(normalized::contains)
    }

    private fun isMerchantCandidate(text: String): Boolean {
        if (text.length !in 2..60 || !LETTER_REGEX.containsMatchIn(text)) return false
        if (containsSummaryKeyword(text) || NOISE_KEYWORDS.any { containsKeyword(text, it) }) return false
        if (DATE_REGEX.containsMatchIn(text) || TIME_REGEX.containsMatchIn(text)) return false
        if (moneyMatches(text).any(NumericMatch::isStrongMoney)) return false
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
        return try { LocalDate.of(normalizedYear, month, day) } catch (_: DateTimeException) { null }
    }

    private fun LogicalRow.numericCells(): List<NumericCell> = fragments.flatMapIndexed { fragmentIndex, fragment ->
        moneyMatches(fragment.text).map { match ->
            val box = fragment.box
            val centerRatio = (match.range.first + match.range.last + 1f) / (2f * fragment.text.length.coerceAtLeast(1))
            NumericCell(
                amountMinor = match.amountMinor,
                raw = match.raw,
                x = box?.let { it.left + it.width * centerRatio },
                rowIndex = index,
                fragmentIndex = fragmentIndex,
                range = match.range,
                isStrongMoney = match.isStrongMoney
            )
        }
    }

    private fun moneyMatches(text: String): List<NumericMatch> = MONEY_TOKEN_REGEX.findAll(text).mapNotNull { match ->
        val amount = parseMoney(match.groupValues[1]) ?: return@mapNotNull null
        if (isPartOfDateOrTime(text, match.range)) return@mapNotNull null
        val suffix = text.substring(match.range.last + 1).take(8)
        NumericMatch(
            amountMinor = amount,
            raw = match.groupValues[1],
            range = match.range,
            isStrongMoney = match.groupValues[1].contains('.') || match.groupValues[1].contains(',') || CURRENCY_REGEX.containsMatchIn(suffix)
        )
    }.toList()

    private fun isPartOfDateOrTime(text: String, range: IntRange): Boolean =
        (DATE_REGEX.findAll(text).toList() + TIME_REGEX.findAll(text).toList()).any { match -> rangesOverlap(range, match.range) }

    private fun parseMoney(raw: String): Long? = runCatching {
        BigDecimal(raw.replace(",", "")).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
    }.getOrNull()

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

    private fun normalizeForKeywords(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(ARABIC_DIACRITICS_REGEX, "")
        .replace(Regex("[أإآٱ]"), "ا")
        .replace('ى', 'ي')
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    private fun normalizeProductName(value: String): String = normalizeForKeywords(value)
        .replace(Regex("[^a-z0-9\\p{IsArabic}]"), "")

    private fun textSimilarity(first: String, second: String): Double {
        if (first.isEmpty() || second.isEmpty()) return 0.0
        if (first == second) return 1.0
        val previous = IntArray(second.length + 1) { it }
        val current = IntArray(second.length + 1)
        first.forEachIndexed { firstIndex, firstChar ->
            current[0] = firstIndex + 1
            second.forEachIndexed { secondIndex, secondChar ->
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    previous[secondIndex] + if (firstChar == secondChar) 0 else 1
                )
            }
            current.copyInto(previous)
        }
        return 1.0 - previous[second.length].toDouble() / max(first.length, second.length)
    }

    private fun containsKeyword(text: String, keyword: String): Boolean {
        val normalized = normalizeForKeywords(text)
        return if (keyword.all { it.code < 128 }) containsEnglishWord(normalized, keyword)
        else normalized.contains(normalizeForKeywords(keyword))
    }

    private fun containsEnglishWord(text: String, word: String): Boolean =
        Regex("(?i)(?:^|[^a-z])${Regex.escape(word)}(?:$|[^a-z])").containsMatchIn(text)

    private fun overlapRatio(firstStart: Float, firstEnd: Float, secondStart: Float, secondEnd: Float): Float {
        val overlap = (min(firstEnd, secondEnd) - max(firstStart, secondStart)).coerceAtLeast(0f)
        val smallest = min(firstEnd - firstStart, secondEnd - secondStart).coerceAtLeast(1f)
        return overlap / smallest
    }

    private fun rowBoxOrNull(parts: List<Fragment>): OcrBoundingBox? {
        val boxes = parts.mapNotNull(Fragment::box)
        if (boxes.isEmpty()) return null
        return rectangle(
            boxes.minOf(OcrBoundingBox::left), boxes.minOf(OcrBoundingBox::top),
            boxes.maxOf(OcrBoundingBox::right), boxes.maxOf(OcrBoundingBox::bottom)
        )
    }

    private fun rectangle(left: Float, top: Float, right: Float, bottom: Float) = OcrBoundingBox(
        listOf(OcrPoint(left, top), OcrPoint(right, top), OcrPoint(right, bottom), OcrPoint(left, bottom))
    )

    private fun rangesOverlap(first: IntRange, second: IntRange): Boolean = first.first <= second.last && second.first <= first.last

    private fun isValidProductName(name: String): Boolean = name.length in 2..MAX_PRODUCT_NAME_LENGTH &&
        LETTER_REGEX.containsMatchIn(name) && name.count(Char::isDigit) <= name.length / 2 && !containsSummaryKeyword(name)

    private data class Fragment(val text: String, val box: OcrBoundingBox?, val confidence: Float?)

    private data class LogicalRow(val index: Int, val fragments: List<Fragment>, val box: OcrBoundingBox?) {
        val preferredText = fragments.joinToString(" ") { it.text }.replace(WHITESPACE_REGEX, " ").trim()
        val textVariants = if (fragments.size <= 1) listOf(preferredText)
        else listOf(preferredText, fragments.asReversed().joinToString(" ") { it.text }).distinct()
    }

    private data class NumericMatch(val amountMinor: Long, val raw: String, val range: IntRange, val isStrongMoney: Boolean)

    private data class NumericCell(
        val amountMinor: Long,
        val raw: String,
        val x: Float?,
        val rowIndex: Int,
        val fragmentIndex: Int,
        val range: IntRange,
        val isStrongMoney: Boolean
    )

    private data class NumericColumn(val centerX: Float, val cells: List<NumericCell>, val support: Int) {
        val matchTolerance = max(MIN_COLUMN_TOLERANCE, cells.mapNotNull(NumericCell::x).let { positions ->
            if (positions.isEmpty()) 0f else (positions.max() - positions.min()) + MIN_COLUMN_TOLERANCE
        })

        fun cellFor(cells: List<NumericCell>): NumericCell? = cells
            .filter { it.x != null && it.isStrongMoney }
            .minByOrNull { abs(checkNotNull(it.x) - centerX) }
            ?.takeIf { abs(checkNotNull(it.x) - centerX) <= matchTolerance }
    }

    private data class RowItem(val name: String, val amountMinor: Long, val rowIndex: Int, val box: OcrBoundingBox?)
    private data class TotalCandidate(val priority: Int, val y: Float, val amountMinor: Long)

    private companion object {
        const val MIN_CONFIDENCE = 0.35f
        const val ROW_VERTICAL_OVERLAP = 0.4f
        const val ROW_CENTER_TOLERANCE = 0.55f
        const val COLUMN_TOLERANCE_RATIO = 0.045f
        const val MIN_COLUMN_TOLERANCE = 14f
        const val MIN_COLUMN_SUPPORT = 2
        const val DUPLICATE_TEXT_SIMILARITY = 0.86
        const val WRAPPED_LINE_GAP_RATIO = 0.8f
        const val MAX_WRAPPED_LINES = 2
        const val MAX_PRODUCT_NAME_LENGTH = 120
        const val TOTAL_TOLERANCE_MINOR = 2L
        val OcrBoundingBox.height get() = (bottom - top).coerceAtLeast(1f)
        val OcrBoundingBox.width get() = (right - left).coerceAtLeast(1f)
        val LETTER_REGEX = Regex("[A-Za-z\\p{IsArabic}]")
        val WHITESPACE_REGEX = Regex("\\s+")
        val DATE_REGEX = Regex("\\b(\\d{4}|\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})\\b")
        val TIME_REGEX = Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b")
        val LONG_NUMBER_REGEX = Regex("(?<!\\d)\\d{7,}(?!\\d)")
        val MONEY_TOKEN_REGEX = Regex("(?<![\\d.,])(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)(?![\\d.,])")
        val SUMMARY_ENGLISH_REGEX = Regex(
            "(?i)(?:^|[^a-z])(?:grand\\s+total|sub\\s*total|net(?:\\s+(?:total|required))?|amount\\s+due|total\\s+due|cash|change|tax|vat|discount|total)(?:$|[^a-z])"
        )
        val SUMMARY_ARABIC_KEYWORDS = listOf(
            "الاجمالي", "اجمالي", "الصافي", "الصافي المطلوب", "المطلوب", "اجمالي المطلوب",
            "الخصم", "الضريبة", "الضريبه", "القيمة المضافة", "القيمه المضافه", "نقدي", "الباقي"
        )
        val REQUIRED_TOTAL_KEYWORDS = listOf("grand total", "amount due", "total due", "net required", "الصافي المطلوب", "اجمالي المطلوب", "المطلوب")
        val PRIMARY_TOTAL_KEYWORDS = listOf("net total", "total", "الاجمالي", "اجمالي", "الصافي")
        val NOISE_KEYWORDS = listOf(
            "invoice", "receipt", "phone", "telephone", "tel", "date", "time", "tax id", "vat no",
            "فاتوره", "ايصال", "هاتف", "تليفون", "تلفون", "تاريخ", "وقت", "رقم ضريبي"
        )
        val QUANTITY_LABEL_REGEX = Regex("(?i)(?:\\bqty\\b|\\bquantity\\b|\\bعدد\\b|\\bكميه\\b)")
        val QUANTITY_ONLY_CONTENT_REGEX = Regex("(?i)(?:\\bqty\\b|\\bquantity\\b|\\bعدد\\b|\\bكميه\\b|\\d+(?:[.,]\\d+)?|x|[|*×@:=+-])")
        val CURRENCY_REGEX = Regex("(?i)(?:EGP|L\\.?E\\.?|ج(?:نيه)?|جم)")
        val CURRENCY_ONLY_REGEX = Regex("(?i)\\s*(?:EGP|L\\.?E\\.?|ج(?:نيه)?|جم)?\\s*")
        val SEPARATOR_REGEX = Regex("[|•]{1,}")
        val ARABIC_DIACRITICS_REGEX = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    }
}
