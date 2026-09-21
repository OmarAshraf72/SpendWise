package com.example.spendwise.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class CommitmentOccurrence(
    val commitment: CommitmentWithMerchant,
    val dueDate: LocalDate,
    val status: CommitmentOccurrenceStatus,
    val paidTransactionId: Long? = null,
    val paidAt: Long? = null
) {
    val amountMinor get() = commitment.commitment.amountMinor
}

object CommitmentRecurrence {
    fun preview(
        frequency: CommitmentFrequency,
        firstPayment: LocalDate,
        endDate: LocalDate? = null,
        count: Int = 3
    ): List<LocalDate> {
        if (count <= 0) return emptyList()
        if (frequency == CommitmentFrequency.ONE_TIME) return listOf(firstPayment)
        if (endDate?.isBefore(firstPayment) == true) return emptyList()
        return generateSequence(0L) { it + 1L }
            .map { occurrenceDate(firstPayment, frequency, it) }
            .takeWhile { it != LocalDate.MAX && (endDate == null || it <= endDate) }
            .take(count)
            .toList()
    }

    fun generate(
        commitment: CommitmentWithMerchant,
        fromInclusive: LocalDate,
        toExclusive: LocalDate,
        overrides: Map<Long, CommitmentOccurrenceOverrideEntity> = emptyMap()
    ): List<CommitmentOccurrence> {
        val definition = commitment.commitment
        if (!definition.isActive || fromInclusive >= toExclusive) return emptyList()
        val anchor = LocalDate.ofEpochDay(definition.nextDueDateEpochDay)
        val end = definition.endDateEpochDay?.let(LocalDate::ofEpochDay)
        return generateSequence(0L) { it + 1L }
            .map { index -> occurrenceDate(anchor, definition.frequency, index) }
            .takeWhile { due -> due < toExclusive && (end == null || due <= end) }
            .filter { due -> due >= fromInclusive }
            .map { due ->
                val override = overrides[due.toEpochDay()]
                CommitmentOccurrence(
                    commitment, due, override?.status ?: CommitmentOccurrenceStatus.UNPAID,
                    override?.paidTransactionId, override?.paidAt
                )
            }.toList()
    }

    private fun occurrenceDate(anchor: LocalDate, frequency: CommitmentFrequency, index: Long): LocalDate = when (frequency) {
        CommitmentFrequency.ONE_TIME -> if (index == 0L) anchor else LocalDate.MAX
        CommitmentFrequency.WEEKLY -> anchor.plusWeeks(index)
        CommitmentFrequency.MONTHLY -> anchoredMonth(anchor, index)
        CommitmentFrequency.QUARTERLY -> anchoredMonth(anchor, index * 3)
        CommitmentFrequency.SEMI_ANNUAL -> anchoredMonth(anchor, index * 6)
        CommitmentFrequency.ANNUAL -> anchoredMonth(anchor, index * 12)
        CommitmentFrequency.CUSTOM -> anchor.plus(index, ChronoUnit.MONTHS)
    }

    private fun anchoredMonth(anchor: LocalDate, months: Long): LocalDate {
        val target = YearMonth.from(anchor).plusMonths(months)
        return target.atDay(anchor.dayOfMonth.coerceAtMost(target.lengthOfMonth()))
    }
}

data class CommitmentMonthTotal(val month: YearMonth, val amountMinor: Long)
data class CommitmentSummary(
    val committedThisMonthMinor: Long,
    val paidThisMonthMinor: Long,
    val remainingThisMonthMinor: Long,
    val next30DaysMinor: Long,
    val next12MonthsMinor: Long,
    val highestMonth: CommitmentMonthTotal?,
    val nextOccurrence: CommitmentOccurrence?
)

object CommitmentCalculator {
    fun occurrences(
        commitments: List<CommitmentWithMerchant>, overrides: List<CommitmentOccurrenceOverrideEntity>,
        from: LocalDate, to: LocalDate
    ): List<CommitmentOccurrence> {
        val byCommitment = overrides.groupBy(CommitmentOccurrenceOverrideEntity::commitmentId)
        return commitments.flatMap { commitment ->
            CommitmentRecurrence.generate(
                commitment, from, to,
                byCommitment[commitment.commitment.id].orEmpty().associateBy(CommitmentOccurrenceOverrideEntity::dueDateEpochDay)
            )
        }.sortedBy(CommitmentOccurrence::dueDate)
    }

    fun forecast(occurrences: List<CommitmentOccurrence>, startMonth: YearMonth, count: Int = 12): List<CommitmentMonthTotal> =
        (0 until count).map { offset ->
            val month = startMonth.plusMonths(offset.toLong())
            CommitmentMonthTotal(
                month,
                occurrences.filter { YearMonth.from(it.dueDate) == month && it.status != CommitmentOccurrenceStatus.SKIPPED }
                    .sumOf(CommitmentOccurrence::amountMinor)
            )
        }

    fun summary(occurrences: List<CommitmentOccurrence>, today: LocalDate): CommitmentSummary {
        val currentMonth = YearMonth.from(today)
        val thisMonth = occurrences.filter { YearMonth.from(it.dueDate) == currentMonth && it.status != CommitmentOccurrenceStatus.SKIPPED }
        val forecast = forecast(occurrences, currentMonth)
        return CommitmentSummary(
            committedThisMonthMinor = thisMonth.sumOf(CommitmentOccurrence::amountMinor),
            paidThisMonthMinor = thisMonth.filter { it.status == CommitmentOccurrenceStatus.PAID }.sumOf(CommitmentOccurrence::amountMinor),
            remainingThisMonthMinor = thisMonth.filter { it.status == CommitmentOccurrenceStatus.UNPAID }.sumOf(CommitmentOccurrence::amountMinor),
            next30DaysMinor = occurrences.filter {
                it.dueDate in today..today.plusDays(29) && it.status == CommitmentOccurrenceStatus.UNPAID
            }.sumOf(CommitmentOccurrence::amountMinor),
            next12MonthsMinor = forecast.sumOf(CommitmentMonthTotal::amountMinor),
            highestMonth = forecast.maxByOrNull(CommitmentMonthTotal::amountMinor),
            nextOccurrence = occurrences.firstOrNull { it.dueDate >= today && it.status == CommitmentOccurrenceStatus.UNPAID }
        )
    }
}
