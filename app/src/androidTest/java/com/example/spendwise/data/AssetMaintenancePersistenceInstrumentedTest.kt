package com.example.spendwise.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetMaintenancePersistenceInstrumentedTest {
    @Test fun completionKeepsHistoryLinksDocumentsAndCreatesAtMostOneExpense() { runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "asset-maintenance-persistence-${UUID.randomUUID()}"
        val db = Room.databaseBuilder(context, SpendWiseDatabase::class.java, databaseName).build()
        val storage = AssetDocumentStorage(context)
        val draftId = UUID.randomUUID().toString()
        try { withContext(Dispatchers.IO) {
            val repo = AssetRepository(db)
            val assetId = repo.create(NewAssetInput(AssetEntity(name = "Car", type = AssetType.VEHICLE,
                brand = null, model = null, purchaseDateEpochDay = null, purchasePriceMinor = null,
                sellerMerchantId = null, currentMileageKm = 90_000, notes = null, createdAt = 1, updatedAt = 1)))
            db.categoryDao().insert(CategoryEntity(name = "Transport", type = CategoryType.DEFAULT,
                iconName = "bus", createdAt = 1))
            val categoryId = db.categoryDao().getActiveCategories().single().id
            val ruleId = repo.saveRule(AssetMaintenanceRuleEntity(assetId = assetId, title = "Engine oil",
                triggerType = MaintenanceTriggerType.MILEAGE, intervalMonths = null, intervalKm = 10_000,
                baselineDateEpochDay = null, baselineMileageKm = 80_000, warningDays = 30, warningKm = 1_000,
                notes = null, createdAt = 1, updatedAt = 1))
            val performed = LocalDate.of(2026, 9, 25)
            val first = repo.completeMaintenance(MaintenanceEventInput(assetId, ruleId, "Oil service", performed,
                91_250, 150_000, null, "Service Center", null, false, "Completed", "no-expense"))
            assertTrue(first > 0)
            assertEquals(0, db.transactionDao().observeTransactions().first().size)
            assertEquals(91_250L, db.assetDao().getAsset(assetId)!!.currentMileageKm)
            val oldEvent = db.assetDao().observeMaintenanceEvents().first().single()
            assertEquals(150_000L, oldEvent.costMinor)
            val oldRule = db.assetDao().getRule(ruleId)!!
            assertEquals(101_250L, MaintenanceDueCalculator.calculate(oldRule, performed,
                91_250, oldEvent).nextDueMileageKm)

            val fileName = UUID.randomUUID().toString()
            val draftPath = "assets/document-drafts/$draftId/$fileName"
            File(context.cacheDir, draftPath).apply { parentFile!!.mkdirs(); writeText("service receipt") }
            val pending = PendingAssetDocument(draftPath, AssetDocumentType.SERVICE_RECEIPT,
                "Oil receipt", "receipt.pdf", "application/pdf", 15)
            val input = MaintenanceEventInput(assetId, null, "Brake repair", performed.plusDays(1),
                92_000, 250_000, null, "Service Center", categoryId, true, "Parts and labor", "with-expense")
            val second = repo.completeMaintenance(input, listOf(pending), storage)
            assertEquals(second, repo.completeMaintenance(input, listOf(pending), storage))
            val events = db.assetDao().observeMaintenanceEvents().first()
            assertEquals(2, events.size)
            val saved = events.single { it.id == second }
            assertEquals(250_000L, saved.costMinor)
            assertEquals("Service Center", saved.providerNameSnapshot)
            assertNotNull(saved.linkedTransactionId)
            assertEquals(1, db.transactionDao().observeTransactions().first().size)
            assertEquals(250_000L, db.transactionDao().observeTransactions().first().single().transaction.amountMinor)
            val links = db.assetDao().observeMaintenanceDocumentLinks().first()
            assertEquals(1, links.size)
            assertEquals(second, links.single().maintenanceEventId)
            val document = db.assetDao().observeDocuments().first().single()
            assertEquals(document.id, links.single().assetDocumentId)
            assertTrue(storage.file(document.storedRelativePath)!!.isFile)
            assertTrue(runCatching { repo.deleteDocument(document.id, storage) }.isFailure)
            assertTrue(storage.file(document.storedRelativePath)!!.isFile)
            assertEquals(92_000L, db.assetDao().getAsset(assetId)!!.currentMileageKm)

            repo.saveRule(oldRule.copy(title = "Oil and filter", updatedAt = 2))
            repo.archiveRule(ruleId, assetId)
            assertEquals("Oil service", db.assetDao().observeMaintenanceEvents().first().single { it.id == first }.title)
            assertFalse(db.assetDao().getRule(ruleId)!!.isActive)
            assertTrue(runCatching { repo.completeMaintenance(input.copy(idempotencyKey = "backwards", mileageKm = 89_000)) }.isFailure)
            assertEquals(2, db.assetDao().observeMaintenanceEvents().first().size)
            db.close()
            val reopened = Room.databaseBuilder(context, SpendWiseDatabase::class.java, databaseName).build()
            try {
                assertEquals(2, reopened.assetDao().observeMaintenanceEvents().first().size)
                assertEquals(1, reopened.transactionDao().observeTransactions().first().size)
                assertEquals(second, reopened.assetDao().observeMaintenanceDocumentLinks().first().single().maintenanceEventId)
                assertEquals(document.id, reopened.assetDao().observeDocuments().first().single().id)
            } finally { reopened.close() }
            storage.delete(document.storedRelativePath)
        } } finally { storage.clearDraft(draftId); db.close(); context.deleteDatabase(databaseName) }
    } }
}
