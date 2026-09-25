package com.example.spendwise.data

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryExplorerPeriodTest {

    private val referenceToday = LocalDate.of(2026, 9, 25)

    private fun dateToMillis(dateStr: String): Long {
        return LocalDate.parse(dateStr).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    private fun createCategory(id: Long, name: String) = CategoryEntity(
        id = id,
        name = name,
        type = CategoryType.DEFAULT,
        iconName = "shopping_bag",
        createdAt = 0,
        isArchived = false
    )

    private fun createExpenseTransaction(id: Long, categoryId: Long, amountMinor: Long, dateStr: String) = TransactionWithCategory(
        transaction = TransactionEntity(
            id = id,
            type = TransactionType.EXPENSE,
            amountMinor = amountMinor,
            categoryId = categoryId,
            merchant = "Store",
            note = null,
            transactionDate = dateToMillis(dateStr),
            source = TransactionSource.MANUAL,
            createdAt = 0
        ),
        category = null
    )

    private fun createIncomeTransaction(id: Long, categoryId: Long, amountMinor: Long, dateStr: String) = TransactionWithCategory(
        transaction = TransactionEntity(
            id = id,
            type = TransactionType.INCOME,
            amountMinor = amountMinor,
            categoryId = categoryId,
            merchant = "Employer",
            note = null,
            transactionDate = dateToMillis(dateStr),
            source = TransactionSource.MANUAL,
            createdAt = 0
        ),
        category = null
    )

    private fun createAsset(id: Long, categoryId: Long, name: String, priceMinor: Long) = AssetEntity(
        id = id,
        name = name,
        type = AssetType.OTHER,
        brand = null,
        model = null,
        purchaseDateEpochDay = LocalDate.parse("2025-09-25").toEpochDay(),
        purchasePriceMinor = priceMinor,
        sellerMerchantId = null,
        currentMileageKm = null,
        notes = null,
        createdAt = 0,
        updatedAt = 0,
        categoryId = categoryId,
        ownershipStatus = OwnershipStatus.OWNED
    )

    @Test
    fun testThisMonthIncludesCurrentMonthAndExcludesPreviousMonth() {
        val category = createCategory(1, "Lifestyle")
        val currentMonthExpense = createExpenseTransaction(101, 1, 350000L, "2026-09-15") // Sep 15, 2026
        val previousMonthExpense = createExpenseTransaction(102, 1, 200000L, "2026-08-20") // Aug 20, 2026

        val txs = listOf(currentMonthExpense, previousMonthExpense)
        val assets = emptyList<AssetEntity>()

        val entries = buildCategoryExplorerEntries(listOf(category), txs, assets, referenceToday, SpendingPeriod.THIS_MONTH)
        assertEquals(350000L, entries.first().monthSpentMinor)
        assertEquals(1, entries.first().recentExpenses.size)
        assertEquals(101L, entries.first().recentExpenses.first().transaction.id)
    }

    @Test
    fun testThreeMonthsIncludesRecentAndExcludesOutsideThreeMonths() {
        val category = createCategory(1, "Lifestyle")
        val currentMonth = createExpenseTransaction(101, 1, 100000L, "2026-09-15")
        val twoMonthsAgo = createExpenseTransaction(102, 1, 200000L, "2026-07-10") // within 3 months
        val fourMonthsAgo = createExpenseTransaction(103, 1, 500000L, "2026-05-10") // outside 3 months

        val txs = listOf(currentMonth, twoMonthsAgo, fourMonthsAgo)
        val entries = buildCategoryExplorerEntries(listOf(category), txs, emptyList(), referenceToday, SpendingPeriod.THREE_MONTHS)

        assertEquals(300000L, entries.first().monthSpentMinor) // 100,000 + 200,000
        assertEquals(2, entries.first().recentExpenses.size)
    }

    @Test
    fun testOneYearIncludesWithinTwelveMonthsAndExcludesOlder() {
        val category = createCategory(1, "Lifestyle")
        val recentTx = createExpenseTransaction(101, 1, 100000L, "2026-09-15")
        val tenMonthsAgo = createExpenseTransaction(102, 1, 250000L, "2025-11-15") // within 12 months
        val fourteenMonthsAgo = createExpenseTransaction(103, 1, 900000L, "2025-07-15") // > 12 months ago

        val txs = listOf(recentTx, tenMonthsAgo, fourteenMonthsAgo)
        val entries = buildCategoryExplorerEntries(listOf(category), txs, emptyList(), referenceToday, SpendingPeriod.ONE_YEAR)

        assertEquals(350000L, entries.first().monthSpentMinor) // 100,000 + 250,000
        assertEquals(2, entries.first().recentExpenses.size)
    }

    @Test
    fun testTotalIncludesAllExpenseHistory() {
        val category = createCategory(1, "Lifestyle")
        val recentTx = createExpenseTransaction(101, 1, 100000L, "2026-09-15")
        val oldTx = createExpenseTransaction(102, 1, 400000L, "2024-01-10")

        val txs = listOf(recentTx, oldTx)
        val entries = buildCategoryExplorerEntries(listOf(category), txs, emptyList(), referenceToday, SpendingPeriod.TOTAL)

        assertEquals(500000L, entries.first().monthSpentMinor)
        assertEquals(2, entries.first().recentExpenses.size)
    }

    @Test
    fun testIncomeIsExcludedFromCategorySpending() {
        val category = createCategory(1, "Lifestyle")
        val expense = createExpenseTransaction(101, 1, 350000L, "2026-09-15")
        val income = createIncomeTransaction(102, 1, 1000000L, "2026-09-20")

        val txs = listOf(expense, income)
        val entries = buildCategoryExplorerEntries(listOf(category), txs, emptyList(), referenceToday, SpendingPeriod.THIS_MONTH)

        assertEquals(350000L, entries.first().monthSpentMinor)
    }

    @Test
    fun testNikeRealScenarioNeverDoubleCountsItemPrice() {
        val category = createCategory(1, "Lifestyle")
        val nikeTransaction = createExpenseTransaction(101, 1, 350000L, "2026-09-25") // 3,500 EGP
        val nikeAirMaxItem = createAsset(501, 1, "Nike Air Max", 350000L) // 3,500 EGP asset linked to category

        val txs = listOf(nikeTransaction)
        val assets = listOf(nikeAirMaxItem)

        val entries = buildCategoryExplorerEntries(listOf(category), txs, assets, referenceToday, SpendingPeriod.THIS_MONTH)
        assertEquals(350000L, entries.first().monthSpentMinor) // 3,500 EGP, NOT 7,000 EGP!
        assertEquals(1, entries.first().ownedItems.size)
    }

    @Test
    fun testOwnedItemCountIsIdenticalAcrossAllPeriods() {
        val category = createCategory(1, "Lifestyle")
        val item1 = createAsset(501, 1, "Nike Air Max", 350000L)
        val item2 = createAsset(502, 1, "Backpack", 120000L)

        val txs = listOf(createExpenseTransaction(101, 1, 100000L, "2026-09-15"))
        val assets = listOf(item1, item2)

        val thisMonth = buildCategoryExplorerEntries(listOf(category), txs, assets, referenceToday, SpendingPeriod.THIS_MONTH)
        val threeMonths = buildCategoryExplorerEntries(listOf(category), txs, assets, referenceToday, SpendingPeriod.THREE_MONTHS)
        val oneYear = buildCategoryExplorerEntries(listOf(category), txs, assets, referenceToday, SpendingPeriod.ONE_YEAR)
        val total = buildCategoryExplorerEntries(listOf(category), txs, assets, referenceToday, SpendingPeriod.TOTAL)

        assertEquals(2, thisMonth.first().ownedItems.size)
        assertEquals(2, threeMonths.first().ownedItems.size)
        assertEquals(2, oneYear.first().ownedItems.size)
        assertEquals(2, total.first().ownedItems.size)
    }

    @Test
    fun testDeterministicPeriodTotalsAndRecentExpensesMatchUserScenario() {
        val category = createCategory(1, "Lifestyle")
        val todayTx = createExpenseTransaction(1, 1, 10000L, "2026-09-25") // 100 EGP
        val twoMonthsAgoTx = createExpenseTransaction(2, 1, 20000L, "2026-07-25") // 200 EGP
        val eightMonthsAgoTx = createExpenseTransaction(3, 1, 30000L, "2026-01-25") // 300 EGP
        val fourteenMonthsAgoTx = createExpenseTransaction(4, 1, 40000L, "2025-07-25") // 400 EGP

        val txs = listOf(todayTx, twoMonthsAgoTx, eightMonthsAgoTx, fourteenMonthsAgoTx)
        val assets = listOf(createAsset(1, 1, "Nike Air Max", 35000L))

        val thisMonth = buildCategoryExplorerEntries(listOf(category), txs, assets, referenceToday, SpendingPeriod.THIS_MONTH).first()
        val threeMonths = buildCategoryExplorerEntries(listOf(category), txs, assets, referenceToday, SpendingPeriod.THREE_MONTHS).first()
        val oneYear = buildCategoryExplorerEntries(listOf(category), txs, assets, referenceToday, SpendingPeriod.ONE_YEAR).first()
        val total = buildCategoryExplorerEntries(listOf(category), txs, assets, referenceToday, SpendingPeriod.TOTAL).first()

        assertEquals(10000L, thisMonth.monthSpentMinor)
        assertEquals(1, thisMonth.recentExpenses.size)

        assertEquals(30000L, threeMonths.monthSpentMinor)
        assertEquals(2, threeMonths.recentExpenses.size)

        assertEquals(60000L, oneYear.monthSpentMinor)
        assertEquals(3, oneYear.recentExpenses.size)

        assertEquals(100000L, total.monthSpentMinor)
        assertEquals(4, total.recentExpenses.size)

        // Verify owned items count is invariant across all periods
        assertEquals(1, thisMonth.ownedItems.size)
        assertEquals(1, threeMonths.ownedItems.size)
        assertEquals(1, oneYear.ownedItems.size)
        assertEquals(1, total.ownedItems.size)
    }
}
