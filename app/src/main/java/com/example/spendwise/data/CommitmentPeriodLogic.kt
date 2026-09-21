package com.example.spendwise.data

import java.time.LocalDate
import java.time.YearMonth

enum class CommitmentViewMode { MONTH, NEXT_30_DAYS, ALL_UPCOMING }

data class CommitmentPeriodSummary(
    val committedMinor: Long,
    val paidMinor: Long,
    val remainingMinor: Long
)

object CommitmentPeriodLogic {
    fun defaultMonth(today: LocalDate): YearMonth = YearMonth.from(today)
    fun forMonth(occurrences: List<CommitmentOccurrence>, month: YearMonth): List<CommitmentOccurrence> =
        occurrences.filter { YearMonth.from(it.dueDate) == month }.sortedBy { it.dueDate }

    fun next30Days(occurrences: List<CommitmentOccurrence>, today: LocalDate): List<CommitmentOccurrence> =
        occurrences.filter { it.dueDate in today..today.plusDays(30) }.sortedBy { it.dueDate }

    fun allUpcoming(occurrences: List<CommitmentOccurrence>, today: LocalDate): List<CommitmentOccurrence> =
        occurrences.filter { it.dueDate >= today }.sortedBy { it.dueDate }

    fun groupByMonth(occurrences: List<CommitmentOccurrence>): Map<YearMonth, List<CommitmentOccurrence>> =
        occurrences.groupBy { YearMonth.from(it.dueDate) }.toSortedMap()

    fun summary(occurrences: List<CommitmentOccurrence>): CommitmentPeriodSummary {
        val counted = occurrences.filter { it.status != CommitmentOccurrenceStatus.SKIPPED }
        val paid = counted.filter { it.status == CommitmentOccurrenceStatus.PAID }.sumOf { it.amountMinor }
        return CommitmentPeriodSummary(
            committedMinor = counted.sumOf { it.amountMinor },
            paidMinor = paid,
            remainingMinor = counted.filter { it.status == CommitmentOccurrenceStatus.UNPAID }.sumOf { it.amountMinor }
        )
    }
}
