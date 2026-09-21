package com.example.spendwise.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class DebtBalance(
    val originalPrincipalMinor: Long,
    val principalPaidMinor: Long,
    val totalPaidMinor: Long,
    val outstandingPrincipalMinor: Long
)

enum class DebtOccurrencePaymentState { UNPAID, PARTIALLY_PAID, PAID, SKIPPED }

object DebtCalculator {
    fun shouldGenerateOccurrences(profile: DebtProfileEntity?): Boolean =
        profile?.repaymentMode != RepaymentMode.OPEN_ENDED

    fun balance(profile: DebtProfileEntity, payments: List<DebtPaymentEntity>): DebtBalance {
        val principalPaid = payments.sumOf { it.principalPaidMinor }
        return DebtBalance(
            profile.originalPrincipalMinor,
            principalPaid,
            payments.sumOf { it.totalPaidMinor },
            (profile.originalPrincipalMinor - principalPaid).coerceAtLeast(0)
        )
    }

    fun occurrenceState(scheduledMinor: Long, paidMinor: Long, skipped: Boolean = false): DebtOccurrencePaymentState = when {
        skipped -> DebtOccurrencePaymentState.SKIPPED
        paidMinor <= 0 -> DebtOccurrencePaymentState.UNPAID
        paidMinor < scheduledMinor -> DebtOccurrencePaymentState.PARTIALLY_PAID
        else -> DebtOccurrencePaymentState.PAID
    }

    fun financingCostMinor(profile: DebtProfileEntity, terms: FinancingTermsEntity?): Long? = when (terms?.financingType) {
        FinancingType.FIXED_TOTAL -> terms.totalRepayableMinor?.let { (it - profile.originalPrincipalMinor).coerceAtLeast(0) }
        FinancingType.USER_PROVIDED_RATE, FinancingType.UNKNOWN_DETAILS, null -> null
    }

    fun history(payments: List<DebtPaymentEntity>): List<DebtPaymentEntity> =
        payments.sortedWith(compareByDescending<DebtPaymentEntity> { it.paymentDateEpochDay }.thenByDescending { it.createdAt })
}

data class LateChargeResult(
    val daysOverdue: Long,
    val isInGracePeriod: Boolean,
    val chargeablePeriods: Long,
    val estimatedChargeMinor: Long
)

object LateChargeCalculator {
    /**
     * A charge begins only when more than [LatePaymentRuleEntity.gracePeriodDays] complete
     * days have elapsed after the due date. Periodic charges count completed periods only.
     * Percentage results round to the nearest minor unit using HALF_UP.
     */
    fun calculate(
        scheduledAmountMinor: Long,
        outstandingPrincipalMinor: Long,
        dueDate: LocalDate,
        asOfDate: LocalDate,
        rule: LatePaymentRuleEntity?
    ): LateChargeResult {
        val daysOverdue = ChronoUnit.DAYS.between(dueDate, asOfDate).coerceAtLeast(0)
        if (rule == null) return LateChargeResult(daysOverdue, false, 0, 0)
        if (daysOverdue <= rule.gracePeriodDays) {
            return LateChargeResult(daysOverdue, daysOverdue > 0, 0, 0)
        }
        val chargeableDays = daysOverdue - rule.gracePeriodDays
        val periods = when (rule.chargeType) {
            LateChargeType.FIXED_ONCE, LateChargeType.PERCENT_ONCE -> 1
            LateChargeType.FIXED_PERIODIC, LateChargeType.PERCENT_PERIODIC -> when (rule.chargeInterval) {
                LateChargeInterval.ONCE -> 1
                LateChargeInterval.DAILY -> chargeableDays
                LateChargeInterval.WEEKLY -> chargeableDays / 7
                LateChargeInterval.MONTHLY -> ChronoUnit.MONTHS.between(dueDate.plusDays(rule.gracePeriodDays.toLong()), asOfDate)
            }
        }.coerceAtLeast(0)
        if (periods == 0L) return LateChargeResult(daysOverdue, false, 0, 0)

        val basisMinor = when (rule.chargeBasis) {
            LateChargeBasis.OVERDUE_INSTALLMENT -> scheduledAmountMinor
            LateChargeBasis.OUTSTANDING_PRINCIPAL -> outstandingPrincipalMinor
        }.coerceAtLeast(0)
        val raw = when (rule.chargeType) {
            LateChargeType.FIXED_ONCE -> BigDecimal.valueOf(rule.fixedChargeMinor ?: 0)
            LateChargeType.FIXED_PERIODIC -> BigDecimal.valueOf(rule.fixedChargeMinor ?: 0).multiply(BigDecimal.valueOf(periods))
            LateChargeType.PERCENT_ONCE -> percentage(basisMinor, rule.rateBasisPoints ?: 0)
            LateChargeType.PERCENT_PERIODIC -> {
                val rate = BigDecimal.valueOf(rule.rateBasisPoints ?: 0).movePointLeft(4)
                if (rule.isCompounding) {
                    BigDecimal.valueOf(basisMinor).multiply(BigDecimal.ONE.add(rate).pow(periods.toInt()).subtract(BigDecimal.ONE))
                } else percentage(basisMinor, rule.rateBasisPoints ?: 0).multiply(BigDecimal.valueOf(periods))
            }
        }
        var rounded = raw.setScale(0, RoundingMode.HALF_UP).longValueExact()
        rule.minimumChargeMinor?.let { rounded = rounded.coerceAtLeast(it) }
        rule.maximumChargeMinor?.let { rounded = rounded.coerceAtMost(it) }
        return LateChargeResult(daysOverdue, false, periods, rounded.coerceAtLeast(0))
    }

    private fun percentage(basisMinor: Long, basisPoints: Long): BigDecimal =
        BigDecimal.valueOf(basisMinor).multiply(BigDecimal.valueOf(basisPoints)).movePointLeft(4)
}

fun validateDebtPayment(
    totalMinor: Long,
    principalMinor: Long,
    financingMinor: Long,
    lateChargeMinor: Long
): Boolean = totalMinor > 0 && listOf(principalMinor, financingMinor, lateChargeMinor).all { it >= 0 } &&
    totalMinor == principalMinor + financingMinor + lateChargeMinor

data class DebtPaymentWritePlan(val existingPaymentId: Long?, val createPayment: Boolean, val createTransaction: Boolean)

fun planDebtPaymentWrite(existing: DebtPaymentEntity?, requestedExpense: Boolean): DebtPaymentWritePlan =
    if (existing != null) DebtPaymentWritePlan(existing.id, false, false)
    else DebtPaymentWritePlan(null, true, requestedExpense)
