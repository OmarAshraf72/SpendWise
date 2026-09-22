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

    @Test fun scheduledSummaryIncludesPartialDebtAllocationsByOccurrenceMonth() {
        val due = LocalDate.parse("2026-09-23")
        val debtOccurrence = occurrence(due.toString(), CommitmentOccurrenceStatus.UNPAID, 10_000_00, 10)
        val profile = debtProfile(10)
        val first = payment(1, profile.id, due, "2026-10-02", 6_000_00, 6_000_00)
        val partial = DebtCalculator.occurrenceProgress(debtOccurrence, profile, listOf(first))
        val summary = CommitmentPeriodLogic.summaryWithPayments(listOf(partial))
        assertEquals(10_000_00, summary.committedMinor)
        assertEquals(6_000_00, summary.paidMinor)
        assertEquals(4_000_00, summary.remainingMinor)
        assertEquals(DebtOccurrencePaymentState.PARTIALLY_PAID, partial.state)

        val completed = DebtCalculator.occurrenceProgress(debtOccurrence, profile, listOf(
            first,
            payment(2, profile.id, due, "2026-10-03", 4_000_00, 4_000_00)
        ))
        assertEquals(CommitmentPeriodSummary(10_000_00, 10_000_00, 0), CommitmentPeriodLogic.summaryWithPayments(listOf(completed)))
        assertEquals(DebtOccurrencePaymentState.PAID, completed.state)
    }

    @Test fun periodUsesDueDateAndIgnoresOpenEndedUnscheduledPayments() {
        val septemberDebt = occurrence("2026-09-30", CommitmentOccurrenceStatus.UNPAID, 10_000_00, 20)
        val octoberDebt = occurrence("2026-10-01", CommitmentOccurrenceStatus.UNPAID, 7_000_00, 20)
        val profile = debtProfile(20)
        val payment = payment(3, profile.id, septemberDebt.dueDate, "2026-10-05", 6_000_00, 6_000_00)
        val all = listOf(septemberDebt, octoberDebt).map { DebtCalculator.occurrenceProgress(it, profile, listOf(payment)) }
        val septemberProgress = CommitmentPeriodLogic.forMonth(all.map { it.occurrence }, YearMonth.of(2026, 9))
            .map { occurrence -> all.single { it.occurrence == occurrence } }
        assertEquals(CommitmentPeriodSummary(10_000_00, 6_000_00, 4_000_00), CommitmentPeriodLogic.summaryWithPayments(septemberProgress))
        assertFalse(DebtCalculator.shouldGenerateOccurrences(profile.copy(repaymentMode = RepaymentMode.OPEN_ENDED)))
        assertEquals(CommitmentPeriodSummary(0, 0, 0), CommitmentPeriodLogic.summaryWithPayments(emptyList()))
    }

    private fun occurrence(date: String, status: CommitmentOccurrenceStatus, amount: Long, id: Long): CommitmentOccurrence {
        val entity = FinancialCommitmentEntity(id, "Commitment $id", amount, CommitmentType.OTHER, CommitmentFrequency.MONTHLY,
            LocalDate.parse(date).toEpochDay(), null, LocalDate.parse(date).toEpochDay(), true, null, null, 1, 1)
        return CommitmentOccurrence(CommitmentWithMerchant(entity, null), LocalDate.parse(date), status)
    }


    private fun debtProfile(commitmentId: Long) = DebtProfileEntity(
        id = commitmentId, commitmentId = commitmentId, creditorName = null, originalPrincipalMinor = 20_000_00,
        startDateEpochDay = LocalDate.parse("2026-01-01").toEpochDay(), expectedEndDateEpochDay = null,
        repaymentMode = RepaymentMode.FIXED_INSTALLMENTS, notes = null, createdAt = 1, updatedAt = 1
    )

    private fun payment(id: Long, profileId: Long, occurrence: LocalDate, paidOn: String, total: Long, principal: Long) = DebtPaymentEntity(
        id = id, debtProfileId = profileId, occurrenceDueDateEpochDay = occurrence.toEpochDay(),
        paymentDateEpochDay = LocalDate.parse(paidOn).toEpochDay(), totalPaidMinor = total,
        principalPaidMinor = principal, financingCostPaidMinor = 0, lateChargePaidMinor = 0,
        linkedTransactionId = 500 + id, note = null, idempotencyKey = "period-$id", createdAt = id
    )
}
