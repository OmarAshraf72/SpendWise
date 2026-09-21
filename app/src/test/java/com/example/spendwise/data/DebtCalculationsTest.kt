package com.example.spendwise.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DebtCalculationsTest {
    @Test fun openEndedDebtHasNoOccurrencesWhileFixedDebtUsesSchedule() {
        assertFalse(DebtCalculator.shouldGenerateOccurrences(profile(RepaymentMode.OPEN_ENDED)))
        assertTrue(DebtCalculator.shouldGenerateOccurrences(profile(RepaymentMode.FIXED_INSTALLMENTS)))
        assertTrue(DebtCalculator.shouldGenerateOccurrences(null)) // Existing V1 commitment.
    }

    @Test fun principalPaymentsDetermineRemainingAndDoNotDoubleCountLinkedExpense() {
        val payments = listOf(
            payment(1, 5_000_00, 5_000_00, linkedTransactionId = 10),
            payment(2, 2_500_00, 2_000_00, finance = 500_00, linkedTransactionId = 11)
        )
        val balance = DebtCalculator.balance(profile(principal = 30_000_00), payments)
        assertEquals(7_000_00, balance.principalPaidMinor)
        assertEquals(7_500_00, balance.totalPaidMinor)
        assertEquals(23_000_00, balance.outstandingPrincipalMinor)
    }

    @Test fun partialAndFullOccurrencePaymentStatesAreDerivedFromAllocatedTotals() {
        assertEquals(DebtOccurrencePaymentState.UNPAID, DebtCalculator.occurrenceState(10_000_00, 0))
        assertEquals(DebtOccurrencePaymentState.PARTIALLY_PAID, DebtCalculator.occurrenceState(10_000_00, 6_000_00))
        assertEquals(DebtOccurrencePaymentState.PAID, DebtCalculator.occurrenceState(10_000_00, 10_000_00))
        assertEquals(DebtOccurrencePaymentState.SKIPPED, DebtCalculator.occurrenceState(10_000_00, 0, true))
    }

    @Test fun multiplePaymentsAndOverpaymentNeverCreateNegativePrincipalBalance() {
        val result = DebtCalculator.balance(profile(principal = 10_000), listOf(payment(1, 6_000, 6_000), payment(2, 6_000, 6_000)))
        assertEquals(12_000, result.principalPaidMinor)
        assertEquals(0, result.outstandingPrincipalMinor)
    }

    @Test fun paymentBreakdownMustExactlyEqualTotal() {
        assertTrue(validateDebtPayment(12_500, 10_000, 2_000, 500))
        assertFalse(validateDebtPayment(12_500, 10_000, 2_000, 0))
        assertFalse(validateDebtPayment(0, 0, 0, 0))
    }

    @Test fun repeatedIdempotencyKeyCreatesNeitherDuplicatePaymentNorTransaction() {
        val first = planDebtPaymentWrite(null, requestedExpense = true)
        assertTrue(first.createPayment)
        assertTrue(first.createTransaction)
        val existing = payment(99, 1_000, 1_000, linkedTransactionId = 77)
        val repeated = planDebtPaymentWrite(existing, requestedExpense = true)
        assertEquals(99L, repeated.existingPaymentId)
        assertFalse(repeated.createPayment)
        assertFalse(repeated.createTransaction)
    }

    @Test fun historyIsReverseChronologicalAndStableByCreationTime() {
        val history = DebtCalculator.history(listOf(
            payment(1, 100, 100, date = "2026-08-05", created = 1),
            payment(2, 100, 100, date = "2026-09-05", created = 2),
            payment(3, 100, 100, date = "2026-09-05", created = 3)
        ))
        assertEquals(listOf(3L, 2L, 1L), history.map { it.id })
    }

    @Test fun fixedTotalFinancingCostIsSeparateFromPrincipal() {
        val profile = profile(principal = 500_000_00)
        val terms = financing(FinancingType.FIXED_TOTAL, total = 620_000_00)
        assertEquals(120_000_00L, DebtCalculator.financingCostMinor(profile, terms))
        assertNull(DebtCalculator.financingCostMinor(profile, financing(FinancingType.USER_PROVIDED_RATE, rate = 1_250)))
        assertNull(DebtCalculator.financingCostMinor(profile, null))
    }

    @Test fun noRuleNotOverdueAndGraceBoundaryProduceNoCharge() {
        val due = LocalDate.parse("2026-10-01")
        assertEquals(0, LateChargeCalculator.calculate(10_000_00, 50_000_00, due, due.plusDays(20), null).estimatedChargeMinor)
        val rule = rule(grace = 5, type = LateChargeType.PERCENT_ONCE, rate = 200)
        assertEquals(0, LateChargeCalculator.calculate(10_000_00, 50_000_00, due, LocalDate.parse("2026-10-05"), rule).estimatedChargeMinor)
        assertEquals(0, LateChargeCalculator.calculate(10_000_00, 50_000_00, due, LocalDate.parse("2026-10-06"), rule).estimatedChargeMinor)
        assertEquals(200_00, LateChargeCalculator.calculate(10_000_00, 50_000_00, due, LocalDate.parse("2026-10-07"), rule).estimatedChargeMinor)
        assertEquals(0, LateChargeCalculator.calculate(10_000_00, 50_000_00, due, due.minusDays(1), rule).estimatedChargeMinor)
    }

    @Test fun fixedOnceAndFixedDailyUseCompletedChargeableDays() {
        val due = LocalDate.parse("2026-01-01")
        assertEquals(100_00, LateChargeCalculator.calculate(10_000_00, 0, due, due.plusDays(1), rule(type = LateChargeType.FIXED_ONCE, fixed = 100_00)).estimatedChargeMinor)
        assertEquals(300_00, LateChargeCalculator.calculate(10_000_00, 0, due, due.plusDays(3), rule(type = LateChargeType.FIXED_PERIODIC, fixed = 100_00, interval = LateChargeInterval.DAILY)).estimatedChargeMinor)
    }

    @Test fun percentageWeeklyCountsOnlyCompletedWeeks() {
        val due = LocalDate.parse("2026-01-01")
        val weekly = rule(type = LateChargeType.PERCENT_PERIODIC, rate = 100, interval = LateChargeInterval.WEEKLY)
        assertEquals(0, LateChargeCalculator.calculate(10_000_00, 0, due, due.plusDays(6), weekly).estimatedChargeMinor)
        assertEquals(100_00, LateChargeCalculator.calculate(10_000_00, 0, due, due.plusDays(7), weekly).estimatedChargeMinor)
        assertEquals(200_00, LateChargeCalculator.calculate(10_000_00, 0, due, due.plusDays(14), weekly).estimatedChargeMinor)
    }

    @Test fun percentageMonthlyAndOutstandingPrincipalBasisAreExplicit() {
        val due = LocalDate.parse("2026-01-01")
        val monthly = rule(type = LateChargeType.PERCENT_PERIODIC, rate = 100, interval = LateChargeInterval.MONTHLY, basis = LateChargeBasis.OUTSTANDING_PRINCIPAL)
        assertEquals(0, LateChargeCalculator.calculate(10_000_00, 50_000_00, due, LocalDate.parse("2026-01-31"), monthly).estimatedChargeMinor)
        assertEquals(500_00, LateChargeCalculator.calculate(10_000_00, 50_000_00, due, LocalDate.parse("2026-02-01"), monthly).estimatedChargeMinor)
    }

    @Test fun minMaxNonCompoundingCompoundingAndRoundingAreDeterministic() {
        val due = LocalDate.parse("2026-01-01")
        val minimum = rule(type = LateChargeType.PERCENT_ONCE, rate = 100, min = 150_00)
        assertEquals(150_00, LateChargeCalculator.calculate(10_000_00, 0, due, due.plusDays(1), minimum).estimatedChargeMinor)
        val maximum = rule(type = LateChargeType.PERCENT_ONCE, rate = 500, max = 200_00)
        assertEquals(200_00, LateChargeCalculator.calculate(10_000_00, 0, due, due.plusDays(1), maximum).estimatedChargeMinor)
        val simple = rule(type = LateChargeType.PERCENT_PERIODIC, rate = 1_000, interval = LateChargeInterval.DAILY)
        val compound = simple.copy(isCompounding = true)
        assertEquals(200_00, LateChargeCalculator.calculate(1_000_00, 0, due, due.plusDays(2), simple).estimatedChargeMinor)
        assertEquals(210_00, LateChargeCalculator.calculate(1_000_00, 0, due, due.plusDays(2), compound).estimatedChargeMinor)
        assertEquals(100, LateChargeCalculator.calculate(10_001, 0, due, due.plusDays(1), rule(type = LateChargeType.PERCENT_ONCE, rate = 100)).estimatedChargeMinor)
    }

    private fun profile(mode: RepaymentMode = RepaymentMode.OPEN_ENDED, principal: Long = 30_000_00) = DebtProfileEntity(
        1, 1, "Creditor", principal, LocalDate.parse("2026-01-01").toEpochDay(), null, mode, null, false, 1, 1
    )

    private fun payment(id: Long, total: Long, principal: Long, finance: Long = 0, late: Long = 0, linkedTransactionId: Long? = null, date: String = "2026-09-05", created: Long = id) = DebtPaymentEntity(
        id, 1, null, LocalDate.parse(date).toEpochDay(), total, principal, finance, late, linkedTransactionId, null, "key-$id", created
    )

    private fun financing(type: FinancingType, total: Long? = null, rate: Long? = null) = FinancingTermsEntity(
        1, 1, type, total, rate, RatePeriod.ANNUAL, RateBasis.ORIGINAL_PRINCIPAL, FinancingCalculationMethod.CONTRACT_DEFINED, 1, 1
    )

    private fun rule(
        grace: Int = 0,
        type: LateChargeType,
        fixed: Long? = null,
        rate: Long? = null,
        interval: LateChargeInterval = LateChargeInterval.ONCE,
        basis: LateChargeBasis = LateChargeBasis.OVERDUE_INSTALLMENT,
        min: Long? = null,
        max: Long? = null
    ) = LatePaymentRuleEntity(1, 1, grace, type, fixed, rate, interval, basis, min, max, false, 1, 1)
}
