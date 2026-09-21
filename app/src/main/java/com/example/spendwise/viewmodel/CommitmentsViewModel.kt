package com.example.spendwise.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spendwise.data.CommitmentCalculator
import com.example.spendwise.data.CommitmentFrequency
import com.example.spendwise.data.CommitmentMonthTotal
import com.example.spendwise.data.CommitmentOccurrence
import com.example.spendwise.data.CommitmentOccurrenceStatus
import com.example.spendwise.data.CommitmentRepository
import com.example.spendwise.data.CommitmentSummary
import com.example.spendwise.data.CommitmentType
import com.example.spendwise.data.CommitmentViewMode
import com.example.spendwise.data.CommitmentPeriodLogic
import com.example.spendwise.data.CommitmentPeriodSummary
import com.example.spendwise.data.FinancialCommitmentEntity
import com.example.spendwise.data.MerchantEntity
import com.example.spendwise.data.MerchantRepository
import com.example.spendwise.data.SpendWiseDatabase
import com.example.spendwise.data.TransactionRepository
import com.example.spendwise.data.TransactionType
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val selectedType: CommitmentType? = null
)

class CommitmentsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SpendWiseDatabase.getInstance(application)
    private val repository = CommitmentRepository(database)
    private val transactionRepository = TransactionRepository(database.transactionDao())
    private val merchantRepository = MerchantRepository(database.merchantDao())
    private val today = LocalDate.now()
    private val selectedType = MutableStateFlow<CommitmentType?>(null)
    private val selectedMonth = MutableStateFlow(CommitmentPeriodLogic.defaultMonth(today))
    private val viewMode = MutableStateFlow(CommitmentViewMode.MONTH)
    private val monthStart = YearMonth.from(today).atDay(1)
    private val monthEnd = monthStart.plusMonths(1)

    val merchants = merchantRepository.merchants.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    val uiState = combine(
        repository.data,
        transactionRepository.observeTransactionsBetween(monthStart.toMillis(), monthEnd.toMillis()),
        selectedType,
        selectedMonth,
        viewMode
    ) { data, transactions, filter, month, mode ->
        val commitments = data.commitments.filter { filter == null || it.commitment.type == filter }
        val earliest = commitments.minOfOrNull { it.commitment.nextDueDateEpochDay }
            ?.let(LocalDate::ofEpochDay)?.coerceAtMost(today) ?: today
        val horizonMonth = maxOf(YearMonth.from(today).plusMonths(12), month.plusMonths(12))
        val horizon = horizonMonth.atEndOfMonth().plusDays(1)
        val occurrences = CommitmentCalculator.occurrences(commitments, data.overrides, earliest, horizon)
        val summary = CommitmentCalculator.summary(occurrences, today)
        val displayed = when (mode) {
            CommitmentViewMode.MONTH -> CommitmentPeriodLogic.forMonth(occurrences, month)
            CommitmentViewMode.NEXT_30_DAYS -> CommitmentPeriodLogic.next30Days(occurrences, today)
            CommitmentViewMode.ALL_UPCOMING -> CommitmentPeriodLogic.allUpcoming(occurrences, today)
        }
        CommitmentsUiState(
            today = today,
            summary = summary,
            selectedMonth = month,
            viewMode = mode,
            periodSummary = CommitmentPeriodLogic.summary(displayed),
            displayedOccurrences = displayed,
            upcomingByMonth = if (mode == CommitmentViewMode.ALL_UPCOMING) CommitmentPeriodLogic.groupByMonth(displayed) else emptyMap(),
            overdue = occurrences.filter {
                it.dueDate < today && it.status == CommitmentOccurrenceStatus.UNPAID
            },
            forecast = CommitmentCalculator.forecast(occurrences, month),
            currentMonthIncomeMinor = transactions.filter { it.transaction.type == TransactionType.INCOME }
                .sumOf { it.transaction.amountMinor },
            selectedType = filter
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommitmentsUiState(today = today))

    fun setTypeFilter(type: CommitmentType?) { selectedType.value = type }
    fun setViewMode(mode: CommitmentViewMode) { viewMode.value = mode }
    fun previousMonth() { selectedMonth.value = selectedMonth.value.minusMonths(1) }
    fun nextMonth() { selectedMonth.value = selectedMonth.value.plusMonths(1) }
    fun selectMonth(month: YearMonth) { selectedMonth.value = month }

    fun createMerchant(name: String, onCreated: (MerchantEntity?) -> Unit) {
        viewModelScope.launch {
            onCreated(merchantRepository.createIfMissing(name, System.currentTimeMillis()))
        }
    }

    fun setStatus(occurrence: CommitmentOccurrence, status: CommitmentOccurrenceStatus, createExpense: Boolean = false) {
        viewModelScope.launch {
            repository.setOccurrenceStatus(
                occurrence.commitment.commitment.id,
                occurrence.dueDate.toEpochDay(),
                status,
                createExpense,
                System.currentTimeMillis()
            )
        }
    }

    suspend fun getCommitment(id: Long): FinancialCommitmentEntity? = repository.get(id)

    fun saveCommitment(
        id: Long,
        title: String,
        amountMinor: Long,
        type: CommitmentType,
        frequency: CommitmentFrequency,
        dueDate: LocalDate,
        endDate: LocalDate?,
        merchantId: Long?,
        notes: String?,
        createdAt: Long?,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repository.save(
                FinancialCommitmentEntity(
                    id = id,
                    title = title.trim(),
                    amountMinor = amountMinor,
                    type = type,
                    frequency = frequency,
                    startDateEpochDay = dueDate.toEpochDay(),
                    endDateEpochDay = endDate?.toEpochDay(),
                    nextDueDateEpochDay = dueDate.toEpochDay(),
                    isActive = true,
                    merchantId = merchantId,
                    notes = notes?.trim()?.takeIf(String::isNotBlank),
                    createdAt = createdAt ?: now,
                    updatedAt = now
                )
            )
            onSaved()
        }
    }
}

private fun LocalDate.toMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
