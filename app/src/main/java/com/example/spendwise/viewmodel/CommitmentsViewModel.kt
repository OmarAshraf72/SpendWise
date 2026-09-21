package com.example.spendwise.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spendwise.data.*
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CommitmentGroupFilter { ALL, INSTALLMENTS, DEBTS, OTHER }

data class DebtSummaryUi(
    val profile: DebtProfileEntity,
    val commitment: CommitmentWithMerchant,
    val balance: DebtBalance,
    val nextOccurrence: CommitmentOccurrence?
)

data class DebtPortfolioSummary(val originalMinor: Long = 0, val principalPaidMinor: Long = 0, val remainingMinor: Long = 0)

data class CommitmentsUiState(
    val today: LocalDate = LocalDate.now(),
    val summary: CommitmentSummary = CommitmentSummary(0, 0, 0, 0, 0, null, null),
    val selectedMonth: YearMonth = YearMonth.now(),
    val viewMode: CommitmentViewMode = CommitmentViewMode.MONTH,
    val periodSummary: CommitmentPeriodSummary = CommitmentPeriodSummary(0, 0, 0),
    val displayedOccurrences: List<CommitmentOccurrence> = emptyList(),
    val upcomingByMonth: Map<YearMonth, List<CommitmentOccurrence>> = emptyMap(),
    val overdue: List<CommitmentOccurrence> = emptyList(),
    val forecast: List<CommitmentMonthTotal> = emptyList(),
    val currentMonthIncomeMinor: Long = 0,
    val selectedGroup: CommitmentGroupFilter = CommitmentGroupFilter.ALL,
    val activeDebts: List<DebtSummaryUi> = emptyList(),
    val debtPortfolio: DebtPortfolioSummary = DebtPortfolioSummary()
)

private data class CommitmentBaseData(val commitments: CommitmentData, val debts: DebtData)
private data class CommitmentControls(val group: CommitmentGroupFilter, val month: YearMonth, val mode: CommitmentViewMode)

class CommitmentsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val repository = CommitmentRepository(database)
    private val debtRepository = DebtRepository(database)
    private val transactionRepository = TransactionRepository(database.transactionDao())
    private val merchantRepository = MerchantRepository(database.merchantDao())
    private val today = LocalDate.now()
    private val selectedGroup = MutableStateFlow(CommitmentGroupFilter.ALL)
    private val selectedMonth = MutableStateFlow(CommitmentPeriodLogic.defaultMonth(today))
    private val viewMode = MutableStateFlow(CommitmentViewMode.MONTH)
    private val monthStart = YearMonth.from(today).atDay(1)
    private val monthEnd = monthStart.plusMonths(1)

    val merchants = merchantRepository.merchants.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val baseData = combine(repository.data, debtRepository.data, ::CommitmentBaseData)
    private val controls = combine(selectedGroup, selectedMonth, viewMode, ::CommitmentControls)

    val uiState = combine(
        baseData,
        transactionRepository.observeTransactionsBetween(monthStart.toMillis(), monthEnd.toMillis()),
        controls
    ) { base, transactions, control ->
        val allCommitments = base.commitments.commitments
        val profilesByCommitment = base.debts.profiles.associateBy(DebtProfileEntity::commitmentId)
        val filtered = allCommitments.filter { item ->
            when (control.group) {
                CommitmentGroupFilter.ALL -> true
                CommitmentGroupFilter.INSTALLMENTS -> item.commitment.type == CommitmentType.INSTALLMENT
                CommitmentGroupFilter.DEBTS -> item.commitment.type == CommitmentType.DEBT_PAYMENT
                CommitmentGroupFilter.OTHER -> item.commitment.type !in setOf(CommitmentType.INSTALLMENT, CommitmentType.DEBT_PAYMENT)
            }
        }
        val scheduled = filtered.filter { DebtCalculator.shouldGenerateOccurrences(profilesByCommitment[it.commitment.id]) }
        val earliest = scheduled.minOfOrNull { it.commitment.nextDueDateEpochDay }
            ?.let(LocalDate::ofEpochDay)?.coerceAtMost(today) ?: today
        val horizonMonth = maxOf(YearMonth.from(today).plusMonths(12), control.month.plusMonths(12))
        val occurrences = CommitmentCalculator.occurrences(scheduled, base.commitments.overrides, earliest, horizonMonth.atEndOfMonth().plusDays(1))
        val displayed = when (control.mode) {
            CommitmentViewMode.MONTH -> CommitmentPeriodLogic.forMonth(occurrences, control.month)
            CommitmentViewMode.NEXT_30_DAYS -> CommitmentPeriodLogic.next30Days(occurrences, today)
            CommitmentViewMode.ALL_UPCOMING -> CommitmentPeriodLogic.allUpcoming(occurrences, today)
        }
        val paymentsByProfile = base.debts.payments.groupBy(DebtPaymentEntity::debtProfileId)
        val occurrenceByCommitment = occurrences.groupBy { it.commitment.commitment.id }
        val activeDebts = base.debts.profiles.mapNotNull { profile ->
            val commitment = allCommitments.firstOrNull { it.commitment.id == profile.commitmentId } ?: return@mapNotNull null
            val include = when (control.group) {
                CommitmentGroupFilter.ALL -> true
                CommitmentGroupFilter.INSTALLMENTS -> commitment.commitment.type == CommitmentType.INSTALLMENT
                CommitmentGroupFilter.DEBTS -> commitment.commitment.type == CommitmentType.DEBT_PAYMENT
                CommitmentGroupFilter.OTHER -> false
            }
            if (!include) return@mapNotNull null
            DebtSummaryUi(
                profile,
                commitment,
                DebtCalculator.balance(profile, paymentsByProfile[profile.id].orEmpty()),
                occurrenceByCommitment[profile.commitmentId].orEmpty().firstOrNull { it.dueDate >= today && it.status == CommitmentOccurrenceStatus.UNPAID }
            )
        }
        CommitmentsUiState(
            today = today,
            summary = CommitmentCalculator.summary(occurrences, today),
            selectedMonth = control.month,
            viewMode = control.mode,
            periodSummary = CommitmentPeriodLogic.summary(displayed),
            displayedOccurrences = displayed,
            upcomingByMonth = if (control.mode == CommitmentViewMode.ALL_UPCOMING) CommitmentPeriodLogic.groupByMonth(displayed) else emptyMap(),
            overdue = occurrences.filter { it.dueDate < today && it.status == CommitmentOccurrenceStatus.UNPAID },
            forecast = CommitmentCalculator.forecast(occurrences, control.month),
            currentMonthIncomeMinor = transactions.filter { it.transaction.type == TransactionType.INCOME }.sumOf { it.transaction.amountMinor },
            selectedGroup = control.group,
            activeDebts = activeDebts,
            debtPortfolio = DebtPortfolioSummary(
                activeDebts.sumOf { it.balance.originalPrincipalMinor },
                activeDebts.sumOf { it.balance.principalPaidMinor },
                activeDebts.sumOf { it.balance.outstandingPrincipalMinor }
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommitmentsUiState(today = today))

    fun setGroupFilter(group: CommitmentGroupFilter) { selectedGroup.value = group }
    fun setViewMode(mode: CommitmentViewMode) { viewMode.value = mode }
    fun previousMonth() { selectedMonth.value = selectedMonth.value.minusMonths(1) }
    fun nextMonth() { selectedMonth.value = selectedMonth.value.plusMonths(1) }
    fun selectMonth(month: YearMonth) { selectedMonth.value = month }

    fun createMerchant(name: String, onCreated: (MerchantEntity?) -> Unit) {
        viewModelScope.launch { onCreated(merchantRepository.createIfMissing(name, System.currentTimeMillis())) }
    }

    fun setStatus(occurrence: CommitmentOccurrence, status: CommitmentOccurrenceStatus, createExpense: Boolean = false) {
        viewModelScope.launch {
            repository.setOccurrenceStatus(occurrence.commitment.commitment.id, occurrence.dueDate.toEpochDay(), status, createExpense, System.currentTimeMillis())
        }
    }

    suspend fun getCommitment(id: Long): FinancialCommitmentEntity? = repository.get(id)
    suspend fun getDebtForCommitment(id: Long): DebtProfileEntity? = debtRepository.getProfileForCommitment(id)
    suspend fun getFinancing(profileId: Long) = debtRepository.getFinancing(profileId)
    suspend fun getLateRule(profileId: Long) = debtRepository.getLateRule(profileId)

    fun saveDebt(input: DebtDefinitionInput, onSaved: () -> Unit) {
        viewModelScope.launch { debtRepository.saveDebt(input); onSaved() }
    }

    fun saveCommitment(
        id: Long, title: String, amountMinor: Long, type: CommitmentType, frequency: CommitmentFrequency,
        dueDate: LocalDate, endDate: LocalDate?, merchantId: Long?, notes: String?, createdAt: Long?, onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repository.save(
                FinancialCommitmentEntity(
                    id, title.trim(), amountMinor, type, frequency, dueDate.toEpochDay(), endDate?.toEpochDay(), dueDate.toEpochDay(),
                    true, merchantId, notes?.trim()?.takeIf(String::isNotBlank), createdAt ?: now, now
                )
            )
            onSaved()
        }
    }
}

private fun LocalDate.toMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
