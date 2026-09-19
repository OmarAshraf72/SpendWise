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
import java.time.ZoneOffset
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class CategorySpendingUi(
    val categoryId: Long?,
    val name: String,
    val amountMinor: Long,
    val percentageTenths: Int
)

data class HomeUiState(
    val monthLabel: String,
    val totalSpentMinor: Long = 0,
    val totalIncomeMinor: Long = 0,
    val remainingMinor: Long = 0,
    val incomeSpentPercentageTenths: Int? = null,
    val transactionCount: Int = 0,
    val categorySpending: List<CategorySpendingUi> = emptyList(),
    val insight: String = "No spending recorded this month yet."
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TransactionRepository(
        SpendWiseDatabase.getInstance(application).transactionDao()
    )
    private val currentMonth = YearMonth.now()
    private val monthLabel = currentMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    private val startInclusive = currentMonth.atDay(1)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
    private val endExclusive = currentMonth.plusMonths(1).atDay(1)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

    val uiState = repository.observeTransactionsBetween(startInclusive, endExclusive)
        .map(::buildUiState)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(monthLabel = monthLabel)
        )

    private fun buildUiState(transactions: List<TransactionWithCategory>): HomeUiState {
        val expenses = transactions.filter { it.transaction.type == TransactionType.EXPENSE }
        val income = transactions.filter { it.transaction.type == TransactionType.INCOME }
        val totalSpent = expenses.fold(0L) { total, item ->
            total + item.transaction.amountMinor
        }
        val totalIncome = income.fold(0L) { total, item ->
            total + item.transaction.amountMinor
        }
        val remaining = totalIncome - totalSpent
        val grouped = expenses.groupBy { item ->
            CategoryKey(item.category?.id, item.category?.name ?: "Uncategorized")
        }
        val categorySpending = grouped.map { (category, transactions) ->
            val categoryTotal = transactions.fold(0L) { total, item ->
                total + item.transaction.amountMinor
            }
            CategorySpendingUi(
                categoryId = category.id,
                name = category.name,
                amountMinor = categoryTotal,
                percentageTenths = percentageTenths(categoryTotal, totalSpent)
            )
        }.sortedWith(compareByDescending<CategorySpendingUi> { it.amountMinor }.thenBy { it.name })

        val topCategory = categorySpending.firstOrNull()
        val spendingInsight = if (topCategory == null) {
            "No spending recorded this month yet."
        } else {
            "Your highest spending category this month is ${topCategory.name} at ${formatEgp(topCategory.amountMinor)}."
        }
        val insight = if (totalIncome > 0L && totalSpent > totalIncome) {
            "$spendingInsight You have spent ${formatEgp(totalSpent - totalIncome)} more than your recorded income this month."
        } else {
            spendingInsight
        }

        return HomeUiState(
            monthLabel = monthLabel,
            totalSpentMinor = totalSpent,
            totalIncomeMinor = totalIncome,
            remainingMinor = remaining,
            incomeSpentPercentageTenths = if (totalIncome > 0L) {
                percentageTenths(totalSpent, totalIncome)
            } else null,
            transactionCount = expenses.size,
            categorySpending = categorySpending,
            insight = insight
        )
    }

    private fun percentageTenths(amountMinor: Long, totalMinor: Long): Int {
        if (totalMinor <= 0L) return 0
        val amount = BigInteger.valueOf(amountMinor)
        val total = BigInteger.valueOf(totalMinor)
        return amount.multiply(BigInteger.valueOf(1_000))
            .add(total.divide(BigInteger.valueOf(2)))
            .divide(total)
            .toInt()
    }

    private data class CategoryKey(val id: Long?, val name: String)
}
