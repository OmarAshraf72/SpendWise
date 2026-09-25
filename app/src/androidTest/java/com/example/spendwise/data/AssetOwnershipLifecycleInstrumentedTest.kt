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
class AssetOwnershipLifecycleInstrumentedTest {
    @Test fun everyLifecycleTransitionPreservesPurchaseDocumentsAndFinancialTotals() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ownership-lifecycle-${UUID.randomUUID()}"
        val database = Room.databaseBuilder(context, SpendWiseDatabase::class.java, name).build()
        try { withContext(Dispatchers.IO) {
            val repository = AssetRepository(database)
            val category = CategoryEntity(id = 7, name = "Lifestyle", type = CategoryType.CUSTOM,
                iconName = "menu_book", createdAt = 1)
            database.categoryDao().insert(category)
            val date = LocalDate.of(2026, 9, 10)
            val transactionId = database.transactionDao().insert(TransactionEntity(type = TransactionType.EXPENSE,
                amountMinor = 350_000, categoryId = 7, merchant = "Nike Store", note = null,
                transactionDate = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                source = TransactionSource.MANUAL, createdAt = 1))
            val assetId = repository.save(NewAssetInput(AssetEntity(name = "Nike Air Max", type = AssetType.OTHER,
                brand = "Nike", model = "Air Max", purchaseDateEpochDay = date.toEpochDay(),
                purchasePriceMinor = 350_000, sellerMerchantId = null, currentMileageKm = null,
                notes = "Original note", categoryId = 7, createdAt = 1, updatedAt = 1),
                purchaseTransactionId = transactionId, sellerName = "Nike Store"))
            database.assetDao().insertDocument(AssetDocumentEntity(assetId = assetId,
                documentType = AssetDocumentType.RECEIPT, title = "Receipt",
                storedRelativePath = "assets/documents/test.pdf", mimeType = "application/pdf",
                originalFileName = "test.pdf", fileSizeBytes = 10, createdAt = 1))
            assertEquals(OwnershipStatus.OWNED, database.assetDao().getAsset(assetId)!!.ownershipStatus)
            val changeDate = LocalDate.of(2027, 9, 25)
            for (status in listOf(OwnershipStatus.SOLD, OwnershipStatus.GIVEN_AWAY,
                    OwnershipStatus.LOST, OwnershipStatus.DISPOSED)) {
                repository.setOwnershipStatus(assetId, status, changeDate, "Changed to ${status.displayLabel()}")
                val asset = database.assetDao().getAsset(assetId)!!
                assertEquals(status, asset.ownershipStatus)
                assertEquals(350_000L, asset.purchasePriceMinor)
                assertEquals("Original note", asset.notes)
                val event = database.assetDao().latestOwnershipEvent(assetId)!!
                assertEquals(status, event.status)
                assertEquals(changeDate.toEpochDay(), event.effectiveDateEpochDay)
                assertEquals("Changed to ${status.displayLabel()}", event.note)
                repository.save(NewAssetInput(asset.copy(ownershipStatus = OwnershipStatus.OWNED)))
                assertEquals(status, database.assetDao().getAsset(assetId)!!.ownershipStatus)
                val entries = buildCategoryExplorerEntries(listOf(category),
                    database.transactionDao().observeTransactions().first(),
                    database.assetDao().observeActiveAssets().first(), LocalDate.of(2026, 9, 25))
                assertTrue(entries.single().ownedItems.isEmpty())
                assertEquals(350_000L, entries.single().monthSpentMinor)
                assertEquals(1, database.transactionDao().observeTransactions().first().size)
                assertEquals(transactionId, database.assetDao().observeTransactionLinks().first().single().transactionId)
                assertEquals("Receipt", database.assetDao().observeDocuments().first().single().title)
                repository.setOwnershipStatus(assetId, OwnershipStatus.OWNED, changeDate.plusDays(1), null)
                assertEquals(assetId, buildCategoryExplorerEntries(listOf(category),
                    database.transactionDao().observeTransactions().first(),
                    database.assetDao().observeActiveAssets().first(), LocalDate.of(2026, 9, 25))
                    .single().ownedItems.single().id)
            }
            val eventCount = database.assetDao().observeOwnershipEvents().first().size
            repository.setOwnershipStatus(assetId, OwnershipStatus.OWNED, changeDate.plusDays(2), null)
            assertEquals(eventCount, database.assetDao().observeOwnershipEvents().first().size)
            database.close()
            val reopened = Room.databaseBuilder(context, SpendWiseDatabase::class.java, name).build()
            try {
                assertEquals(OwnershipStatus.OWNED, reopened.assetDao().getAsset(assetId)!!.ownershipStatus)
                assertEquals(eventCount, reopened.assetDao().observeOwnershipEvents().first().size)
                assertEquals(1, reopened.transactionDao().observeTransactions().first().size)
                assertEquals(1, reopened.assetDao().observeDocuments().first().size)
            } finally { reopened.close() }
        } } finally { database.close(); context.deleteDatabase(name) }
    }
}
