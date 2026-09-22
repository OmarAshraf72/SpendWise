package com.example.spendwise.data

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class DebtDefinitionLogicTest {
    private val start = LocalDate.parse("2026-09-23")

    @Test fun switchingFixedToOpenEndedClearsScheduleAndForecast() {
        val open = canonicalDebtDefinition(definition(RepaymentMode.OPEN_ENDED, 30_000_00))
        assertEquals(RepaymentMode.OPEN_ENDED, open.repaymentMode)
        assertEquals(0L, open.commitment.amountMinor)
        assertEquals(CommitmentFrequency.ONE_TIME, open.commitment.frequency)
        assertEquals(start.toEpochDay(), open.commitment.nextDueDateEpochDay)
        assertNull(open.commitment.endDateEpochDay)
        val profile = profile(open.repaymentMode)
        assertFalse(DebtCalculator.shouldGenerateOccurrences(profile))
        assertTrue(CommitmentCalculator.forecast(emptyList(), YearMonth.from(start)).all { it.amountMinor == 0L })
        assertEquals(0L, CommitmentPeriodLogic.summaryWithPayments(emptyList()).committedMinor)
        assertEquals(24_000_00L, DebtCalculator.balance(profile, listOf(payment())).outstandingPrincipalMinor)
    }

    @Test fun switchingOpenEndedToFixedRequiresValidInstallmentAmount() {
        assertThrows(IllegalArgumentException::class.java) {
            canonicalDebtDefinition(definition(RepaymentMode.FIXED_INSTALLMENTS, 0))
        }
        assertEquals(10_000_00L, canonicalDebtDefinition(definition(RepaymentMode.FIXED_INSTALLMENTS, 10_000_00)).commitment.amountMinor)
    }

    @Test fun savingExistingOpenEndedDefinitionPreservesModeAndExpectedEnd() {
        val original = definition(RepaymentMode.OPEN_ENDED, 0)
            .copy(expectedEndDate = LocalDate.parse("2028-09-23"))
        val edited = canonicalDebtDefinition(original.copy(commitment = original.commitment.copy(id = 42, title = "Edited debt")))
        assertEquals(42L, edited.commitment.id)
        assertEquals(RepaymentMode.OPEN_ENDED, edited.repaymentMode)
        assertEquals(LocalDate.parse("2028-09-23"), edited.expectedEndDate)
    }

    private fun definition(mode: RepaymentMode, amount: Long) = DebtDefinitionInput(
        commitment = FinancialCommitmentEntity(0, "Debt", amount, CommitmentType.DEBT_PAYMENT, CommitmentFrequency.MONTHLY,
            start.toEpochDay(), null, start.toEpochDay(), true, null, null, 1, 1),
        creditorName = "Creditor", originalPrincipalMinor = 30_000_00, debtStartDate = start,
        expectedEndDate = null, repaymentMode = mode, debtNotes = null, financingTerms = null, lateRule = null
    )

    private fun profile(mode: RepaymentMode) = DebtProfileEntity(1, 1, null, 30_000_00, start.toEpochDay(), null, mode, null, false, 1, 1)
    private fun payment() = DebtPaymentEntity(1, 1, null, start.toEpochDay(), 6_000_00, 6_000_00, 0, 0, null, null, "payment-1", 1)
}
