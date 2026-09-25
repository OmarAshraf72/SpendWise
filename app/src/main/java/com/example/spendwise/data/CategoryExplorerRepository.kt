package com.example.spendwise.data

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

enum class SpendingPeriod(val label: String) {
    THIS_MONTH("This month"),
    THREE_MONTHS("3 months"),
    ONE_YEAR("1 year"),
    TOTAL("Total")
}

data class CategoryExplorerEntry(
    val category: CategoryEntity,
    val monthSpentMinor: Long,
    val recentExpenses: List<TransactionWithCategory>,
    val ownedItems: List<AssetEntity>
)

/**
 * Calculates start (inclusive, nullable) and end (exclusive) epoch-millis range for [SpendingPeriod].
 * THIS_MONTH: 1st of calendar month through today inclusive.
 * THREE_MONTHS: rolling 3 months before today through today inclusive.
 * ONE_YEAR: rolling 12 months before today through today inclusive.
 * TOTAL: all expense transaction history.
 */
fun spendingPeriodDateRange(period: SpendingPeriod, today: LocalDate): Pair<Long?, Long> {
    val start = when (period) {
        SpendingPeriod.THIS_MONTH -> today.withDayOfMonth(1)
        SpendingPeriod.THREE_MONTHS -> today.minusMonths(3)
        SpendingPeriod.ONE_YEAR -> today.minusYears(1)
        SpendingPeriod.TOTAL -> null
    }
    val startMillis = start?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    val endMillis = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    return Pair(startMillis, endMillis)
}

/** Finance comes exclusively from expense rows. Item purchase values are presentation only. */
fun buildCategoryExplorerEntries(
    categories: List<CategoryEntity>,
    transactions: List<TransactionWithCategory>,
    assets: List<AssetEntity>,
    today: LocalDate,
    period: SpendingPeriod = SpendingPeriod.THIS_MONTH
): List<CategoryExplorerEntry> {
    val (startMillis, endMillis) = spendingPeriodDateRange(period, today)
    val expenses = transactions.filter { it.transaction.type == TransactionType.EXPENSE }
    return categories.map { category ->
        val categoryExpenses = expenses.filter {
            it.transaction.categoryId == category.id &&
            (startMillis == null || it.transaction.transactionDate >= startMillis) &&
            it.transaction.transactionDate < endMillis
        }.sortedByDescending { it.transaction.transactionDate }
        CategoryExplorerEntry(
            category = category,
            monthSpentMinor = categoryExpenses.sumOf { it.transaction.amountMinor },
            recentExpenses = categoryExpenses.take(10),
            ownedItems = assets.filter { it.categoryId == category.id && it.ownershipStatus == OwnershipStatus.OWNED }
        )
    }
}

class CategoryExplorerRepository(private val database: SpendWiseDatabase) {
    private val monthClock = flow {
        while (true) {
            emit(LocalDate.now())
            val now = ZonedDateTime.now()
            delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone))
                .toMillis().coerceAtLeast(1_000L))
        }
    }

    val entries: Flow<List<CategoryExplorerEntry>> = combine(
        database.categoryDao().observeCategories(),
        database.transactionDao().observeTransactions(),
        database.assetDao().observeActiveAssets(),
        monthClock
    ) { categories, transactions, assets, today ->
        buildCategoryExplorerEntries(categories, transactions, assets, today, SpendingPeriod.THIS_MONTH)
    }

    fun entriesForPeriod(periodFlow: Flow<SpendingPeriod>): Flow<List<CategoryExplorerEntry>> = combine(
        database.categoryDao().observeCategories(),
        database.transactionDao().observeTransactions(),
        database.assetDao().observeActiveAssets(),
        periodFlow,
        monthClock
    ) { categories, transactions, assets, period, today ->
        buildCategoryExplorerEntries(categories, transactions, assets, today, period)
    }
}
