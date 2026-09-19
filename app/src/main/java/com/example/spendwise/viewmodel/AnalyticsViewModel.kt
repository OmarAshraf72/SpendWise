package com.example.spendwise.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spendwise.data.SpendWiseDatabase
import com.example.spendwise.data.TransactionRepository
import com.example.spendwise.data.TransactionType
import com.example.spendwise.data.TransactionWithCategory
import com.example.spendwise.data.formatEgp
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi

enum class AnalyticsPeriod(val label: String, val monthCount: Long, val usesDailyTrend: Boolean) {
    THIS_MONTH("This Month", 1, true),
    LAST_MONTH("Last Month", 1, true),
    LAST_3_MONTHS("Last 3 Months", 3, false),
    LAST_6_MONTHS("Last 6 Months", 6, false)
}

data class AnalyticsCategoryUi(
    val categoryId: Long?,
    val name: String,
    val amountMinor: Long,
    val percentageTenths: Int
)

data class TrendPointUi(val label: String, val amountMinor: Long)

data class AnalyticsUiState(
    val selectedPeriod: AnalyticsPeriod = AnalyticsPeriod.THIS_MONTH,
    val totalSpentMinor: Long = 0,
    val totalIncomeMinor: Long = 0,
    val remainingMinor: Long = 0,
    val expenseCount: Int = 0,
    val averageExpenseMinor: Long = 0,
    val categories: List<AnalyticsCategoryUi> = emptyList(),
    val trend: List<TrendPointUi> = emptyList(),
    val insights: List<String> = listOf("No spending recorded for this period.")
)

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TransactionRepository(
        SpendWiseDatabase.getInstance(application).transactionDao()
    )
    private val selectedPeriod = MutableStateFlow(AnalyticsPeriod.THIS_MONTH)

    val uiState = selectedPeriod.flatMapLatest { period ->
        val range = period.dateRange(YearMonth.now())
        combine(
            repository.observeTransactionsBetween(range.startMillis, range.endMillis),
            repository.observeTransactionsBetween(range.previousStartMillis, range.startMillis)
        ) { current, previous ->
            buildUiState(period, range, current, previous)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AnalyticsUiState()
    )

    fun selectPeriod(period: AnalyticsPeriod) {
        selectedPeriod.value = period
    }

    private fun buildUiState(
        period: AnalyticsPeriod,
        range: PeriodRange,
        transactions: List<TransactionWithCategory>,
        previousTransactions: List<TransactionWithCategory>
    ): AnalyticsUiState {
        val expenses = transactions.filter { it.transaction.type == TransactionType.EXPENSE }
        val income = transactions.filter { it.transaction.type == TransactionType.INCOME }
        val previousSpent = previousTransactions
            .filter { it.transaction.type == TransactionType.EXPENSE }
            .sumMinor()
        val totalSpent = expenses.sumMinor()
        val totalIncome = income.sumMinor()
        val categories = expenses.groupBy {
            CategoryKey(it.category?.id, it.category?.name ?: "Uncategorized")
        }.map { (category, items) ->
            val amount = items.sumMinor()
            AnalyticsCategoryUi(
                categoryId = category.id,
                name = category.name,
                amountMinor = amount,
                percentageTenths = percentageTenths(amount, totalSpent)
            )
        }.sortedWith(compareByDescending<AnalyticsCategoryUi> { it.amountMinor }.thenBy { it.name })

        return AnalyticsUiState(
            selectedPeriod = period,
            totalSpentMinor = totalSpent,
            totalIncomeMinor = totalIncome,
            remainingMinor = totalIncome - totalSpent,
            expenseCount = expenses.size,
            averageExpenseMinor = averageMinor(totalSpent, expenses.size),
            categories = categories,
            trend = buildTrend(period, range, expenses),
            insights = buildInsights(categories, totalSpent, previousSpent)
        )
    }

    private fun buildTrend(
        period: AnalyticsPeriod,
        range: PeriodRange,
        expenses: List<TransactionWithCategory>
    ): List<TrendPointUi> {
        return if (period.usesDailyTrend) {
            val totals = expenses.groupBy { it.transaction.localDate() }
                .mapValues { (_, items) -> items.sumMinor() }
            generateSequence(range.startDate) { date -> date.plusDays(1).takeIf { it < range.endDate } }
                .map { date -> TrendPointUi(date.dayOfMonth.toString(), totals[date] ?: 0L) }
                .toList()
        } else {
            val totals = expenses.groupBy { YearMonth.from(it.transaction.localDate()) }
                .mapValues { (_, items) -> items.sumMinor() }
            generateSequence(YearMonth.from(range.startDate)) { month ->
                month.plusMonths(1).takeIf { it < YearMonth.from(range.endDate) }
            }.map { month ->
                TrendPointUi(month.format(DateTimeFormatter.ofPattern("MMM")), totals[month] ?: 0L)
            }.toList()
        }
    }

    private fun buildInsights(
        categories: List<AnalyticsCategoryUi>,
        totalSpent: Long,
        previousSpent: Long
    ): List<String> {
        val top = categories.firstOrNull() ?: return listOf("No spending recorded for this period.")
        return buildList {
            add("${top.name} is your highest spending category at ${formatEgp(top.amountMinor)}.")
            add("${top.name} represents ${formatPercentage(top.percentageTenths)} of your spending.")
            comparisonInsight(totalSpent, previousSpent)?.let(::add)
        }
    }

    private fun comparisonInsight(current: Long, previous: Long): String? {
        if (previous <= 0L || current == previous) return null
        val difference = BigInteger.valueOf(current).subtract(BigInteger.valueOf(previous)).abs()
        val percent = difference.multiply(BigInteger.valueOf(100))
            .add(BigInteger.valueOf(previous).divide(BigInteger.valueOf(2)))
            .divide(BigInteger.valueOf(previous))
            .min(BigInteger.valueOf(Int.MAX_VALUE.toLong()))
            .toInt()
        val direction = if (current > previous) "more" else "less"
        return "You spent $percent% $direction than the previous comparable period."
    }

    private fun percentageTenths(amount: Long, total: Long): Int {
        if (total <= 0L) return 0
        val totalValue = BigInteger.valueOf(total)
        return BigInteger.valueOf(amount).multiply(BigInteger.valueOf(1_000))
            .add(totalValue.divide(BigInteger.valueOf(2)))
            .divide(totalValue)
            .toInt()
    }

    private fun averageMinor(total: Long, count: Int): Long {
        if (count == 0) return 0
        return BigInteger.valueOf(total)
            .add(BigInteger.valueOf(count.toLong()).divide(BigInteger.valueOf(2)))
            .divide(BigInteger.valueOf(count.toLong()))
            .longValueExact()
    }

    private fun List<TransactionWithCategory>.sumMinor(): Long =
        fold(0L) { total, item -> total + item.transaction.amountMinor }

    private fun com.example.spendwise.data.TransactionEntity.localDate(): LocalDate =
        Instant.ofEpochMilli(transactionDate).atZone(ZoneOffset.UTC).toLocalDate()

    private fun formatPercentage(tenths: Int): String =
        if (tenths % 10 == 0) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"

    private data class CategoryKey(val id: Long?, val name: String)
}

private data class PeriodRange(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val previousStartDate: LocalDate
) {
    val startMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val endMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val previousStartMillis = previousStartDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

private fun AnalyticsPeriod.dateRange(currentMonth: YearMonth): PeriodRange {
    val endMonth = if (this == AnalyticsPeriod.LAST_MONTH) currentMonth else currentMonth.plusMonths(1)
    val startMonth = endMonth.minusMonths(monthCount)
    return PeriodRange(
        startDate = startMonth.atDay(1),
        endDate = endMonth.atDay(1),
        previousStartDate = startMonth.minusMonths(monthCount).atDay(1)
    )
}
