package com.example.spendwise.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QuickExpenseLogicTest {
    @Test fun exactMoneyParsingUsesMinorUnits() {
        assertEquals(25_000L, parseEgpToMinor("250"))
        assertEquals(25_050L, parseEgpToMinor("250.5"))
        assertEquals(25_050L, parseEgpToMinor("250.50"))
        assertNull(parseEgpToMinor("250.555"))
    }

    @Test fun dateDefaultsToInjectedTodayAndRecognizesChanges() {
        val clock = Clock.fixed(Instant.parse("2026-09-21T13:00:00Z"), ZoneOffset.UTC)
        val provider = ExpenseDateProvider(clock)
        assertEquals(Instant.parse("2026-09-21T00:00:00Z").toEpochMilli(), provider.todayStartMillis())
        assertTrue(provider.isToday(provider.todayStartMillis()))
        assertFalse(provider.isToday(Instant.parse("2026-09-20T00:00:00Z").toEpochMilli()))
    }

    @Test fun merchantNormalizationHandlesCaseSpacingAndCommonArabicForms() {
        assertEquals("carrefour", normalizeMerchantName("  CARREFOUR  "))
        assertEquals("carrefour", normalizeMerchantName("Carrefour"))
        assertEquals("اسلام وميدو", normalizeMerchantName("إسلام   وميدو"))
    }

    @Test fun autocompletePrioritizesExactPrefixThenUserHistory() {
        val merchants = listOf(
            merchant(1, "Carrefour", 3, 100),
            merchant(2, "Car Care", 10, 300),
            merchant(3, "Cairo Market", 20, 500)
        )
        assertEquals(listOf("Car Care", "Carrefour"), MerchantRanker.rank("car", merchants).map { it.merchant.displayName })
        assertEquals("Cairo Market", MerchantRanker.rank("", merchants).first().merchant.displayName)
        assertEquals("Carrefour", MerchantRanker.rank("carrefor", merchants).first().merchant.displayName)
    }

    @Test fun addMerchantActionRequiresThreeCharactersAndNoExactMatch() {
        val suggestions = MerchantRanker.rank("car", listOf(merchant(1, "Carrefour", 3, 100)))
        assertFalse(shouldOfferNewMerchant("c", emptyList()))
        assertFalse(shouldOfferNewMerchant("ca", emptyList()))
        assertTrue(shouldOfferNewMerchant("car", emptyList()))
        assertTrue(shouldOfferNewMerchant("car", suggestions))
        val exact = MerchantRanker.rank("Carrefour", listOf(merchant(1, "Carrefour", 3, 100)))
        assertFalse(shouldOfferNewMerchant(" CARREFOUR ", exact))
    }

    @Test fun selectingSuggestionUpdatesNameAndUsesOnlyAnActiveSuggestedCategory() {
        val suggestion = MerchantRanker.rank("car", listOf(
            merchant(1, "Carrefour", 3, 100).copy(defaultCategoryId = 4)
        )).single()
        assertEquals(MerchantSelection("Carrefour", 4), resolveMerchantSelection(suggestion, setOf(4, 5), 5))
        assertEquals(MerchantSelection("Carrefour", 5), resolveMerchantSelection(suggestion, setOf(5), 5))
    }

    @Test fun egyptMerchantDirectorySeedsAllMissingMerchants() = runBlocking {
        val dao = FakeMerchantDao()
        MerchantSeeder(dao).seed(seedCategories(), now = 1234)

        assertEquals(32, egyptMerchantSeeds.size)
        assertEquals(32, dao.rows.size)
        assertTrue(dao.rows.all {
            it.source == MerchantSource.SEEDED && it.usageCount == 0 && it.lastUsedAt == MerchantSeeder.NEVER_USED_AT
        })
        assertEquals("Carrefour", MerchantRanker.rank("car", dao.rows).first().merchant.displayName)
    }

    @Test fun seedDoesNotDuplicateOrOverwriteExistingUserMerchant() = runBlocking {
        val dao = FakeMerchantDao()
        val user = MerchantEntity(
            displayName = "My Carrefour", normalizedName = normalizeMerchantName("Carrefour"),
            usageCount = 7, lastUsedAt = 999, createdAt = 10,
            defaultCategoryId = 42, source = MerchantSource.USER
        )
        dao.insert(user)
        MerchantSeeder(dao).seed(seedCategories(), now = 1234)

        assertEquals(32, dao.rows.size)
        val preserved = dao.rows.single { it.normalizedName == "carrefour" }
        assertEquals("My Carrefour", preserved.displayName)
        assertEquals(7, preserved.usageCount)
        assertEquals(42L, preserved.defaultCategoryId)
        assertEquals(MerchantSource.USER, preserved.source)
    }

    @Test fun seededDefaultsAreConservativeAndUserUsageOutranksUnusedSeed() = runBlocking {
        val dao = FakeMerchantDao()
        MerchantSeeder(dao).seed(seedCategories(), now = 1234)
        val byName = dao.rows.associateBy(MerchantEntity::displayName)
        assertNull(byName.getValue("Carrefour").defaultCategoryId)
        assertNull(byName.getValue("Amazon Egypt").defaultCategoryId)
        assertNull(byName.getValue("Shell").defaultCategoryId)
        assertEquals(6L, byName.getValue("El Ezaby Pharmacy").defaultCategoryId)
        assertEquals(3L, byName.getValue("Talabat").defaultCategoryId)
        assertEquals(4L, byName.getValue("Uber").defaultCategoryId)

        dao.insert(merchant(100, "Car Care", 8, 500))
        assertEquals("Car Care", MerchantRanker.rank("car", dao.rows).first().merchant.displayName)
    }

    @Test fun newMerchantIsSavedAndRepeatedUseUpdatesUsageAndCategory() = runBlocking {
        val dao = FakeMerchantDao()
        val repository = MerchantRepository(dao)
        val first = repository.recordUse(" Carrefour ", 2, 1000)!!
        assertEquals(1, first.usageCount)
        assertEquals(2L, first.defaultCategoryId)
        val second = repository.recordUse("CARREFOUR", 5, 2000)!!
        assertEquals(first.id, second.id)
        assertEquals(2, second.usageCount)
        assertEquals(5L, second.defaultCategoryId)
        assertEquals(1, dao.rows.size)
    }

    @Test fun splitValidationCoversExactUnderOverAndOptionalNames() {
        val exact = validateSplitAllocation(125_000, listOf(
            SplitExpenseInput(80_000, 1, null),
            SplitExpenseInput(25_000, 2, "Cleaning"),
            SplitExpenseInput(20_000, 3, "")
        ))
        assertTrue(exact.isExact)
        assertEquals(0L, exact.remainingMinor)
        assertEquals(5_000L, validateSplitAllocation(125_000, listOf(SplitExpenseInput(120_000, 1))).remainingMinor)
        assertEquals(-5_000L, validateSplitAllocation(125_000, listOf(SplitExpenseInput(130_000, 1))).remainingMinor)
    }

    @Test fun archivedCategoriesAreRejectedForNewExpenses() {
        val parts = listOf(ManualExpensePart(1_000, 1), ManualExpensePart(2_000, 9))
        assertFalse(categoriesAreActive(parts, setOf(1, 2, 3)))
        assertTrue(categoriesAreActive(parts.take(1), setOf(1, 2, 3)))
    }

    @Test fun groupedSplitSavesOnlyPartsAndUnsplitSavesOneRowAndIncomeStillWorks() = runBlocking {
        val dao = FakeTransactionDao()
        val repository = TransactionRepository(dao)
        repository.addManualPurchase(
            parts = listOf(ManualExpensePart(80_000, 1), ManualExpensePart(25_000, 2), ManualExpensePart(20_000, 3)),
            merchantId = 9, merchantName = "Carrefour", transactionDate = 10, purchaseGroupId = "group"
        )
        assertEquals(3, dao.rows.size)
        assertEquals(125_000L, dao.rows.sumOf { it.amountMinor })
        assertTrue(dao.rows.all { it.purchaseGroupId == "group" && it.source == TransactionSource.MANUAL })
        repository.addManualPurchase(listOf(ManualExpensePart(5_000, 1)), 9, "Carrefour", 11, null)
        assertNull(dao.rows.last().purchaseGroupId)
        repository.addManualIncome(10_000, "Salary", null, 12)
        assertEquals(TransactionType.INCOME, dao.rows.last().type)
    }

    private fun merchant(id: Long, name: String, uses: Int, last: Long) = MerchantEntity(
        id, name, normalizeMerchantName(name), uses, last, 1, null, MerchantSource.USER
    )

    private fun seedCategories() = listOf(
        CategoryEntity(3, "Restaurants", CategoryType.DEFAULT, "restaurant", 1),
        CategoryEntity(4, "Transport", CategoryType.DEFAULT, "directions_bus", 1),
        CategoryEntity(5, "Shopping", CategoryType.DEFAULT, "shopping_bag", 1),
        CategoryEntity(6, "Medicine", CategoryType.DEFAULT, "local_hospital", 1),
        CategoryEntity(7, "Bills", CategoryType.DEFAULT, "receipt_long", 1)
    )
}

private class FakeMerchantDao : MerchantDao {
    val rows = mutableListOf<MerchantEntity>()
    private var nextId = 1L
    override fun observeMerchants(): Flow<List<MerchantEntity>> = flowOf(rows)
    override suspend fun findByNormalizedName(normalizedName: String) = rows.firstOrNull { it.normalizedName == normalizedName }
    override suspend fun insert(merchant: MerchantEntity): Long {
        if (rows.any { it.normalizedName == merchant.normalizedName }) return -1
        val id = nextId++
        rows += merchant.copy(id = id)
        return id
    }
    override suspend fun insertAll(merchants: List<MerchantEntity>): List<Long> = merchants.map { insert(it) }
    override suspend fun recordUse(id: Long, displayName: String, categoryId: Long, usedAt: Long) {
        val index = rows.indexOfFirst { it.id == id }
        rows[index] = rows[index].copy(
            displayName = displayName, usageCount = rows[index].usageCount + 1,
            lastUsedAt = usedAt, defaultCategoryId = categoryId, source = MerchantSource.USER
        )
    }
}

private class FakeTransactionDao : TransactionDao {
    val rows = mutableListOf<TransactionEntity>()
    override fun observeTransactions(): Flow<List<TransactionWithCategory>> = flowOf(emptyList())
    override fun observeExpensesBetween(startInclusive: Long, endExclusive: Long): Flow<List<TransactionWithCategory>> = flowOf(emptyList())
    override fun observeTransactionsBetween(startInclusive: Long, endExclusive: Long): Flow<List<TransactionWithCategory>> = flowOf(emptyList())
    override suspend fun insert(transaction: TransactionEntity): Long { rows += transaction; return rows.size.toLong() }
    override suspend fun insertAll(transactions: List<TransactionEntity>): List<Long> {
        rows += transactions
        return transactions.indices.map { (rows.size - transactions.size + it + 1).toLong() }
    }
}
