package com.example.spendwise.data

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentRecurrenceTest {
    @Test fun previewShowsExpectedSchedulesAndOneTimeIgnoresEndDate() {
        val first = LocalDate.parse("2026-09-22")
        assertEquals(listOf("2026-09-22"), CommitmentRecurrence.preview(CommitmentFrequency.ONE_TIME, first, first.minusDays(1)).map(LocalDate::toString))
        assertEquals(listOf("2026-09-22", "2026-09-29", "2026-10-06"), CommitmentRecurrence.preview(CommitmentFrequency.WEEKLY, first).map(LocalDate::toString))
        assertEquals(listOf("2026-09-22", "2026-10-22", "2026-11-22"), CommitmentRecurrence.preview(CommitmentFrequency.MONTHLY, first).map(LocalDate::toString))
        assertEquals(listOf("2026-09-22", "2026-12-22", "2027-03-22"), CommitmentRecurrence.preview(CommitmentFrequency.QUARTERLY, first).map(LocalDate::toString))
    }

    @Test fun previewReactsToDateFrequencyAndEndDateWithoutChangingAnchor() {
        val first = LocalDate.parse("2026-10-05")
        assertEquals(first, CommitmentRecurrence.preview(CommitmentFrequency.WEEKLY, first).first())
        assertEquals(first, CommitmentRecurrence.preview(CommitmentFrequency.MONTHLY, first).first())
        assertEquals(listOf("2026-10-05", "2026-11-05"), CommitmentRecurrence.preview(CommitmentFrequency.MONTHLY, first, LocalDate.parse("2026-11-05")).map(LocalDate::toString))
        assertTrue(CommitmentRecurrence.preview(CommitmentFrequency.MONTHLY, first, first.minusDays(1)).isEmpty())
    }

    @Test fun oneTimeCommitmentOccursOnce() {
        val dates = generate(CommitmentFrequency.ONE_TIME, "2026-10-05", "2027-01-01")
        assertEquals(listOf(LocalDate.parse("2026-10-05")), dates)
    }

    @Test fun monthlyRecurrenceUsesAnchorDay() {
        assertEquals(
            listOf("2026-01-05", "2026-02-05", "2026-03-05"),
            generate(CommitmentFrequency.MONTHLY, "2026-01-05", "2026-04-01").map(LocalDate::toString)
        )
    }

    @Test fun quarterlyAndAnnualRecurrences() {
        assertEquals(listOf("2026-01-15", "2026-04-15", "2026-07-15", "2026-10-15"), generate(CommitmentFrequency.QUARTERLY, "2026-01-15", "2027-01-01").map(LocalDate::toString))
        assertEquals(listOf("2024-02-29", "2025-02-28", "2026-02-28"), generate(CommitmentFrequency.ANNUAL, "2024-02-29", "2027-01-01").map(LocalDate::toString))
    }

    @Test fun endOfMonthClampsThenRestoresOriginalDay() {
        assertEquals(
            listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"),
            generate(CommitmentFrequency.MONTHLY, "2026-01-31", "2026-05-01").map(LocalDate::toString)
        )
        assertEquals(LocalDate.parse("2024-02-29"), generate(CommitmentFrequency.MONTHLY, "2024-01-31", "2024-03-01")[1])
    }

    @Test fun endDateIsInclusiveAndInactiveProducesNothing() {
        val commitment = commitment(frequency = CommitmentFrequency.MONTHLY, anchor = "2026-01-05", end = "2026-03-05")
        assertEquals(3, CommitmentRecurrence.generate(commitment, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-05-01")).size)
        assertTrue(CommitmentRecurrence.generate(commitment(isActive = false), LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01")).isEmpty())
    }

    @Test fun occurrenceOverridesApplyToOnlyTheirDueDate() {
        val definition = commitment(anchor = "2026-09-05")
        val overrides = mapOf(
            LocalDate.parse("2026-09-05").toEpochDay() to override("2026-09-05", CommitmentOccurrenceStatus.PAID),
            LocalDate.parse("2026-10-05").toEpochDay() to override("2026-10-05", CommitmentOccurrenceStatus.SKIPPED)
        )
        val result = CommitmentRecurrence.generate(definition, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-12-01"), overrides)
        assertEquals(listOf(CommitmentOccurrenceStatus.PAID, CommitmentOccurrenceStatus.SKIPPED, CommitmentOccurrenceStatus.UNPAID), result.map { it.status })
    }

    @Test fun overdueIsDerivedWithoutChangingStatus() {
        val today = LocalDate.parse("2026-09-20")
        val all = CommitmentCalculator.occurrences(listOf(commitment(anchor = "2026-09-05")), emptyList(), LocalDate.parse("2026-09-01"), LocalDate.parse("2026-11-01"))
        assertEquals(listOf(LocalDate.parse("2026-09-05")), all.filter { it.dueDate < today && it.status == CommitmentOccurrenceStatus.UNPAID }.map { it.dueDate })
    }

    @Test fun forecastAndSummaryExcludeSkippedWithoutDoubleCountingPaid() {
        val today = LocalDate.parse("2026-09-20")
        val definition = commitment(amount = 500_000, anchor = "2026-09-25")
        val skipped = override("2026-10-25", CommitmentOccurrenceStatus.SKIPPED)
        val paid = override("2026-09-25", CommitmentOccurrenceStatus.PAID, paidTransactionId = 10)
        val occurrences = CommitmentCalculator.occurrences(listOf(definition), listOf(skipped, paid), today, LocalDate.parse("2027-10-01"))
        val forecast = CommitmentCalculator.forecast(occurrences, YearMonth.of(2026, 9))
        val summary = CommitmentCalculator.summary(occurrences, today)
        assertEquals(500_000, summary.committedThisMonthMinor)
        assertEquals(500_000, summary.paidThisMonthMinor)
        assertEquals(0, summary.remainingThisMonthMinor)
        assertEquals(0, summary.next30DaysMinor)
        assertEquals(5_500_000, forecast.sumOf { it.amountMinor })
        assertEquals(5_500_000, summary.next12MonthsMinor)
    }

    @Test fun next30DaysIncludesBoundaryAndFutureGenerationIsBounded() {
        val today = LocalDate.parse("2026-01-01")
        val occurrences = CommitmentCalculator.occurrences(listOf(commitment(frequency = CommitmentFrequency.WEEKLY, anchor = "2026-01-01", amount = 100)), emptyList(), today, LocalDate.parse("2026-02-15"))
        assertEquals(5, occurrences.count { it.dueDate in today..today.plusDays(29) })
        assertEquals(5, CommitmentCalculator.summary(occurrences, today).next30DaysMinor / 100)
        assertTrue(occurrences.all { it.dueDate < LocalDate.parse("2026-02-15") })
    }

    @Test fun linkedExpenseIsCreatedAtMostOnce() {
        assertTrue(shouldCreateLinkedCommitmentExpense(CommitmentOccurrenceStatus.PAID, true, null))
        val linked = override("2026-09-05", CommitmentOccurrenceStatus.PAID, paidTransactionId = 42)
        assertFalse(shouldCreateLinkedCommitmentExpense(CommitmentOccurrenceStatus.PAID, true, linked))
        assertFalse(shouldCreateLinkedCommitmentExpense(CommitmentOccurrenceStatus.PAID, false, null))
        assertFalse(shouldCreateLinkedCommitmentExpense(CommitmentOccurrenceStatus.SKIPPED, true, null))
    }

    private fun generate(frequency: CommitmentFrequency, anchor: String, endExclusive: String) =
        CommitmentRecurrence.generate(commitment(frequency = frequency, anchor = anchor), LocalDate.parse(anchor), LocalDate.parse(endExclusive)).map { it.dueDate }

    private fun commitment(
        frequency: CommitmentFrequency = CommitmentFrequency.MONTHLY,
        anchor: String = "2026-01-05",
        end: String? = null,
        amount: Long = 500_000,
        isActive: Boolean = true
    ): CommitmentWithMerchant = CommitmentWithMerchant(
        FinancialCommitmentEntity(1, "Car installment", amount, CommitmentType.INSTALLMENT, frequency,
            LocalDate.parse(anchor).toEpochDay(), end?.let { LocalDate.parse(it).toEpochDay() },
            LocalDate.parse(anchor).toEpochDay(), isActive, null, null, 1, 1), null
    )

    private fun override(date: String, status: CommitmentOccurrenceStatus, paidTransactionId: Long? = null) =
        CommitmentOccurrenceOverrideEntity(0, 1, LocalDate.parse(date).toEpochDay(), status, paidTransactionId, null)
}
