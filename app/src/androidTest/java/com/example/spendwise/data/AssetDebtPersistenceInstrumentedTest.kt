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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetDebtPersistenceInstrumentedTest {
    private lateinit var database: SpendWiseDatabase

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SpendWiseDatabase::class.java
        ).build()
    }

    @After fun closeDatabase() = database.close()

    @Test fun openEndedDebtSurvivesCreateReloadAndEditWithoutSchedule() = runBlocking {
        withContext(Dispatchers.IO) {
            val repository = DebtRepository(database)
            val start = LocalDate.parse("2026-09-23")
            val base = FinancialCommitmentEntity(
                title = "Debt", amountMinor = 3_000_000, type = CommitmentType.DEBT_PAYMENT,
                frequency = CommitmentFrequency.ONE_TIME, startDateEpochDay = start.toEpochDay(),
                endDateEpochDay = null, nextDueDateEpochDay = start.toEpochDay(),
                merchantId = null, notes = null, createdAt = 1, updatedAt = 1
            )
            val input = DebtDefinitionInput(base, "Creditor", 3_000_000, start, null,
                RepaymentMode.OPEN_ENDED, null, null, null)
            val profileId = repository.saveDebt(input)
            val created = requireNotNull(repository.getProfile(profileId))
            val stored = requireNotNull(database.commitmentDao().getCommitmentWithMerchant(created.commitmentId)).commitment
            assertEquals(RepaymentMode.OPEN_ENDED, created.repaymentMode)
            assertFalse(DebtCalculator.shouldGenerateOccurrences(created))
            assertEquals(0L, stored.amountMinor)
            assertEquals(CommitmentFrequency.ONE_TIME, stored.frequency)

            repository.saveDebt(input.copy(commitment = stored.copy(title = "Edited debt", updatedAt = 2)))
            val edited = requireNotNull(repository.getProfileForCommitment(stored.id))
            assertEquals(RepaymentMode.OPEN_ENDED, edited.repaymentMode)
            assertFalse(DebtCalculator.shouldGenerateOccurrences(edited))
            assertEquals(0L, requireNotNull(database.commitmentDao().getCommitmentWithMerchant(stored.id)).commitment.amountMinor)
        }
    }

    @Test fun assetIdentifiersSurviveSaveReloadEditAndRemoval() = runBlocking {
        withContext(Dispatchers.IO) {
            val repository = AssetRepository(database)
            val asset = AssetEntity(name = "Phone", type = AssetType.PHONE, brand = null, model = null,
                purchaseDateEpochDay = null, purchasePriceMinor = null, sellerMerchantId = null,
                currentMileageKm = null, notes = null, createdAt = 1, updatedAt = 1)
            val first = AssetIdentifierEntity(assetId = 0, type = AssetIdentifierType.IMEI,
                label = null, value = "12345")
            val second = first.copy(value = "67890")
            val assetId = repository.save(NewAssetInput(asset, listOf(first, second)))
            val loaded = requireNotNull(repository.loadForEdit(assetId))
            assertEquals(listOf("12345", "67890"), loaded.identifiers.map { it.value })

            repository.save(NewAssetInput(loaded.asset.copy(name = "Edited phone", updatedAt = 2),
                listOf(second.copy(assetId = assetId))))
            val reopened = requireNotNull(repository.loadForEdit(assetId))
            assertEquals("Edited phone", reopened.asset.name)
            assertEquals(listOf("67890"), reopened.identifiers.map { it.value })
        }
    }

    @Test fun draftDocumentsPersistTogetherAndFailedSaveKeepsDraft() = runBlocking {
        withContext(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val storage = AssetDocumentStorage(context)
            val repository = AssetRepository(database)
            val draftId = UUID.randomUUID().toString()
            val draftDirectory = File(context.cacheDir, "assets/document-drafts/$draftId").apply { mkdirs() }
            try {
                val documents = listOf(
                    AssetDocumentType.INVOICE, AssetDocumentType.WARRANTY_CARD,
                    AssetDocumentType.PURCHASE_CONTRACT
                ).map { type ->
                    val file = File(draftDirectory, UUID.randomUUID().toString()).apply { writeText("document") }
                    PendingAssetDocument("assets/document-drafts/$draftId/${file.name}", type,
                        type.displayTitle(), "${type.name.lowercase()}.pdf", "application/pdf", file.length())
                }
                val invalid = AssetEntity(name = "", type = AssetType.OTHER, brand = null, model = null,
                    purchaseDateEpochDay = null, purchasePriceMinor = null, sellerMerchantId = null,
                    currentMileageKm = null, notes = null, createdAt = 1, updatedAt = 1)
                runCatching { repository.save(NewAssetInput(invalid), documents, storage) }
                    .onSuccess { error("Invalid asset saved") }
                assertTrue(documents.all { File(context.cacheDir, it.draftRelativePath).isFile })
                assertTrue(database.assetDao().observeDocuments().first().isEmpty())

                val assetId = repository.save(NewAssetInput(invalid.copy(name = "Car")), documents, storage)
                val persisted = database.assetDao().observeDocuments().first().filter { it.assetId == assetId }
                assertEquals(3, persisted.size)
                assertEquals(documents.map { it.documentType }, persisted.map { it.documentType })
                assertTrue(persisted.all { storage.file(it.storedRelativePath)?.isFile == true })
                assertTrue(persisted.all { it.storedRelativePath.startsWith("assets/documents/") })
                persisted.forEach { storage.delete(it.storedRelativePath) }

                assertTrue(storage.deleteDraft(documents.first()))
                assertTrue(documents.drop(1).all { File(context.cacheDir, it.draftRelativePath).isFile })
                storage.clearDraft(draftId)
                assertTrue(!draftDirectory.exists())
            } finally {
                storage.clearDraft(draftId)
            }
        }
    }
}
