package com.example.spendwise.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.spendwise.data.*
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DebtDetailUiState(
    val profile: DebtProfileEntity? = null,
    val commitment: CommitmentWithMerchant? = null,
    val payments: List<DebtPaymentEntity> = emptyList(),
    val financing: FinancingTermsEntity? = null,
    val lateRule: LatePaymentRuleEntity? = null,
    val balance: DebtBalance? = null,
    val nextOccurrence: CommitmentOccurrence? = null,
    val nextOccurrencePaidMinor: Long = 0,
    val estimatedLateCharge: LateChargeResult? = null
)

class DebtDetailViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val debtRepository = DebtRepository(database)
    private val commitmentRepository = CommitmentRepository(database)
    private val profileId: Long = checkNotNull(savedStateHandle["debtProfileId"])
    private val today = LocalDate.now()

    val uiState = combine(debtRepository.data, commitmentRepository.data) { debtData, commitmentData ->
        val profile = debtData.profiles.firstOrNull { it.id == profileId }
        val commitment = profile?.let { p -> commitmentData.commitments.firstOrNull { it.commitment.id == p.commitmentId } }
        val payments = DebtCalculator.history(debtData.payments.filter { it.debtProfileId == profileId })
        val financing = debtData.financingTerms.firstOrNull { it.debtProfileId == profileId }
        val rule = debtData.lateRules.firstOrNull { it.debtProfileId == profileId }
        val occurrences = if (profile?.repaymentMode == RepaymentMode.FIXED_INSTALLMENTS && commitment != null) {
            CommitmentCalculator.occurrences(
                listOf(commitment), commitmentData.overrides,
                LocalDate.ofEpochDay(commitment.commitment.nextDueDateEpochDay).coerceAtMost(today),
                today.plusYears(10)
            )
        } else emptyList()
        val next = occurrences.firstOrNull { it.status == CommitmentOccurrenceStatus.UNPAID }
        val allocated = next?.let { occurrence ->
            payments.filter { it.occurrenceDueDateEpochDay == occurrence.dueDate.toEpochDay() }
                .sumOf { it.principalPaidMinor + it.financingCostPaidMinor }
        } ?: 0
        val balance = profile?.let { DebtCalculator.balance(it, payments) }
        DebtDetailUiState(
            profile, commitment, payments, financing, rule, balance, next, allocated,
            if (next != null && balance != null) LateChargeCalculator.calculate(
                (next.amountMinor - allocated).coerceAtLeast(0), balance.outstandingPrincipalMinor, next.dueDate, today, rule
            ) else null
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebtDetailUiState())

    fun recordPayment(input: DebtPaymentInput, onSaved: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                debtRepository.recordPayment(input)
                onSaved()
            } catch (_: Exception) {
                onError("Payment could not be saved. Check the amount breakdown.")
            }
        }
    }

    fun archive(onArchived: () -> Unit) {
        viewModelScope.launch { debtRepository.archive(profileId); onArchived() }
    }
}
