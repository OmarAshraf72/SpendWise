package com.example.spendwise.data

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentPeriodLogicTest {
    private val september = occurrence("2026-09-22", CommitmentOccurrenceStatus.UNPAID, 20_000, 1)
    private val septemberPaid = occurrence("2026-09-25", CommitmentOccurrenceStatus.PAID, 400_000, 2)
    private val october = occurrence("2026-10-22", CommitmentOccurrenceStatus.UNPAID, 20_000, 1)

    @Test fun currentMonthIsDefaultAndSeptemberExcludesOctober() {
        assertEquals(YearMonth.of(2026, 9), CommitmentPeriodLogic.defaultMonth(LocalDate.parse("2026-09-21")))
        val result = CommitmentPeriodLogic.forMonth(listOf(september, october), YearMonth.of(2026, 9))
        assertEquals(listOf(september), result)
    }

    @Test fun previousNextAndDirectMonthYearNavigationUseYearMonthWithoutChangingDefinitions() {
        val september = YearMonth.of(2026, 9)
        assertEquals(YearMonth.of(2026, 8), september.minusMonths(1))
        assertEquals(YearMonth.of(2026, 10), september.plusMonths(1))
        assertEquals(YearMonth.of(2030, 3), YearMonth.of(2030, 3))
    }

    @Test fun monthlySummaryIncludesOnlySelectedPeriodAndRespectsPaidRemaining() {
        val displayed = CommitmentPeriodLogic.forMonth(listOf(september, septemberPaid, october), YearMonth.of(2026, 9))
        val summary = CommitmentPeriodLogic.summary(displayed)
        assertEquals(420_000, summary.committedMinor)
        assertEquals(400_000, summary.paidMinor)
        assertEquals(20_000, summary.remainingMinor)
    }

    @Test fun next30DaysUsesRollingRangeAndAllUpcomingGroupsByMonth() {
        val today = LocalDate.parse("2026-09-21")
        val boundary = occurrence("2026-10-21", CommitmentOccurrenceStatus.UNPAID, 1, 3)
        val outside = occurrence("2026-10-22", CommitmentOccurrenceStatus.UNPAID, 1, 4)
        assertTrue(boundary in CommitmentPeriodLogic.next30Days(listOf(boundary, outside), today))
        assertFalse(outside in CommitmentPeriodLogic.next30Days(listOf(boundary, outside), today))
        val grouped = CommitmentPeriodLogic.groupByMonth(CommitmentPeriodLogic.allUpcoming(listOf(september, october), today))
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10)), grouped.keys.toList())
    }

    @Test fun historicalOverdueIsNotMixedIntoUnrelatedSelectedMonth() {
        val overdue = occurrence("2026-08-10", CommitmentOccurrenceStatus.UNPAID, 100, 5)
        val septemberRows = CommitmentPeriodLogic.forMonth(listOf(overdue, september), YearMonth.of(2026, 9))
        assertEquals(listOf(september), septemberRows)
        assertTrue(overdue.dueDate < LocalDate.parse("2026-09-21"))
    }

    private fun occurrence(date: String, status: CommitmentOccurrenceStatus, amount: Long, id: Long): CommitmentOccurrence {
        val entity = FinancialCommitmentEntity(id, "Commitment $id", amount, CommitmentType.OTHER, CommitmentFrequency.MONTHLY,
            LocalDate.parse(date).toEpochDay(), null, LocalDate.parse(date).toEpochDay(), true, null, null, 1, 1)
        return CommitmentOccurrence(CommitmentWithMerchant(entity, null), LocalDate.parse(date), status)
    }
}
