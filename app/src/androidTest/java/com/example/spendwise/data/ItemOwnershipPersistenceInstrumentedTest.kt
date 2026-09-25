package com.example.spendwise.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ItemOwnershipPersistenceInstrumentedTest {
    @Test fun customTypesCategoryAndPurchaseLinkPersistWithoutAnotherExpense() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ownership-persistence-${UUID.randomUUID()}"
        val database = Room.databaseBuilder(context, SpendWiseDatabase::class.java, name).build()
        try { withContext(Dispatchers.IO) {
            val repository = AssetRepository(database)
            val typeId = repository.createCustomType(" Shoes ")
            assertTrue(runCatching { repository.createCustomType("shoes") }.isFailure)
            database.categoryDao().insert(CategoryEntity(id = 7, name = "Lifestyle", type = CategoryType.CUSTOM,
                iconName = "menu_book", createdAt = 1))
            val date = LocalDate.of(2026, 9, 10)
            val transactionId = database.transactionDao().insert(TransactionEntity(type = TransactionType.EXPENSE,
                amountMinor = 350_000, categoryId = 7, merchant = "Nike Store", note = null,
                transactionDate = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                source = TransactionSource.MANUAL, createdAt = 1))
            val prefill = repository.purchasePrefill(transactionId)!!
            assertEquals(350_000L, prefill.amountMinor)
            assertEquals(7L, prefill.categoryId)
            val assetId = repository.save(NewAssetInput(AssetEntity(name = "Nike Air Max", type = AssetType.OTHER,
                brand = null, model = null, purchaseDateEpochDay = prefill.purchaseDateEpochDay,
                purchasePriceMinor = prefill.amountMinor, sellerMerchantId = prefill.merchantId,
                currentMileageKm = null, notes = null, categoryId = prefill.categoryId,
                customTypeId = typeId, createdAt = 1, updatedAt = 1), purchaseTransactionId = transactionId,
                sellerName = prefill.merchantName))
            assertEquals(1, database.transactionDao().observeTransactions().first().size)
            assertEquals(1, database.assetDao().observeTransactionLinks().first().size)
            val entries = buildCategoryExplorerEntries(database.categoryDao().getActiveCategories(),
                database.transactionDao().observeTransactions().first(), database.assetDao().observeActiveAssets().first(),
                LocalDate.of(2026, 9, 25))
            val entry = entries.single()
            assertEquals(350_000L, entry.monthSpentMinor)
            assertEquals(assetId, entry.ownedItems.single().id)
            assertEquals(transactionId, entry.recentExpenses.single().transaction.id)
            repository.archiveCustomType(typeId)
            assertTrue(database.assetDao().getCustomType(typeId)!!.isArchived)
            assertEquals("Shoes", itemTypeLabel(AssetType.OTHER, typeId,
                database.assetDao().observeAllCustomTypes().first()))
            assertEquals(typeId, repository.createCustomType("SHOES"))
            repository.setOwnershipStatus(assetId, OwnershipStatus.SOLD, LocalDate.of(2027, 9, 25), "Sold to Ahmed")
            assertTrue(buildCategoryExplorerEntries(database.categoryDao().getActiveCategories(),
                database.transactionDao().observeTransactions().first(), database.assetDao().observeActiveAssets().first(),
                LocalDate.of(2026, 9, 25)).single().ownedItems.isEmpty())
            database.categoryDao().archiveCustom(7)
            assertTrue(runCatching { repository.save(NewAssetInput(AssetEntity(name = "New shoes", type = AssetType.OTHER,
                brand = null, model = null, purchaseDateEpochDay = null, purchasePriceMinor = null,
                sellerMerchantId = null, currentMileageKm = null, notes = null,
                categoryId = 7, customTypeId = typeId, createdAt = 1, updatedAt = 1))) }.isFailure)
            database.close()
            val reopened = Room.databaseBuilder(context, SpendWiseDatabase::class.java, name).build()
            try {
                assertEquals("Shoes", reopened.assetDao().getCustomType(typeId)!!.name)
                val saved = reopened.assetDao().getAsset(assetId)!!
                assertEquals(typeId, saved.customTypeId)
                assertEquals(7L, saved.categoryId)
                assertEquals(OwnershipStatus.SOLD, saved.ownershipStatus)
                assertNotNull(saved.sellerMerchantId)
                assertEquals(transactionId, reopened.assetDao().observeTransactionLinks().first().single().transactionId)
                assertEquals(1, reopened.transactionDao().observeTransactions().first().size)
            } finally { reopened.close() }
        } } finally { database.close(); context.deleteDatabase(name) }
    }
}
