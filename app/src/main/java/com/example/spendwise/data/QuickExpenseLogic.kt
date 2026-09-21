package com.example.spendwise.data

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ExpenseDateProvider(private val clock: Clock = Clock.systemDefaultZone()) {
    fun todayStartMillis(): Long = LocalDate.now(clock).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun isToday(value: Long): Boolean = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate() == LocalDate.now(clock)
}

data class SplitExpenseInput(val amountMinor: Long?, val categoryId: Long?, val note: String? = null)
data class SplitAllocation(val assignedMinor: Long, val remainingMinor: Long, val isExact: Boolean, val message: String?)

fun validateSplitAllocation(totalMinor: Long, splits: List<SplitExpenseInput>): SplitAllocation {
    val assigned = splits.sumOf { it.amountMinor ?: 0L }
    val remaining = totalMinor - assigned
    val rowsValid = splits.isNotEmpty() && splits.all { (it.amountMinor ?: 0L) > 0L && it.categoryId != null }
    val message = when {
        !rowsValid -> "Every split needs a positive amount and category."
        remaining > 0L -> "Assign the remaining amount before saving."
        remaining < 0L -> "Split amounts exceed the purchase total."
        else -> null
    }
    return SplitAllocation(assigned, remaining, rowsValid && remaining == 0L, message)
}

fun categoriesAreActive(parts: List<ManualExpensePart>, activeCategoryIds: Set<Long>): Boolean =
    parts.all { it.categoryId in activeCategoryIds }
