package com.example.spendwise.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ItemOwnershipMigrationInstrumentedTest {
    @Test fun migration12To13PreservesExistingItemsTransactionsDocumentsAndLinks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "ownership-migration-${System.nanoTime()}"
        val schema = JSONObject(instrumentation.context.assets
            .open("com.example.spendwise.data.SpendWiseDatabase/12.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (item in 0 until indices.length())
                    raw.execSQL(indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) raw.execSQL(setup.getString(index))
            raw.execSQL("INSERT INTO categories (id, name, type, iconName, createdAt, isArchived) VALUES (7, 'Lifestyle', 'CUSTOM', 'menu_book', 1, 0)")
            raw.execSQL("""INSERT INTO transactions (id, type, amountMinor, categoryId, merchant, note,
                transactionDate, source, createdAt, receiptGroupId, merchantId, purchaseGroupId)
                VALUES (5, 'EXPENSE', 350000, 7, 'Nike Store', NULL, 1788998400000, 'MANUAL', 1, NULL, NULL, NULL)""".trimIndent())
            raw.execSQL("""INSERT INTO assets (id, name, type, brand, model, purchaseDateEpochDay,
                purchasePriceMinor, sellerMerchantId, currentMileageKm, notes, isArchived, createdAt, updatedAt, mileageUpdatedAt)
                VALUES (3, 'Old phone', 'PHONE', NULL, NULL, NULL, 350000, NULL, NULL, NULL, 0, 1, 1, NULL)""".trimIndent())
            raw.execSQL("INSERT INTO asset_transaction_links (assetId, transactionId, relationType) VALUES (3, 5, 'PURCHASE')")
            raw.execSQL("""INSERT INTO asset_warranties (id, assetId, name, type, providerName,
                startDateEpochDay, endDateEpochDay, phone, website, notes, createdAt, updatedAt)
                VALUES (2, 3, 'Phone warranty', 'MANUFACTURER', NULL, 20700, 21065, NULL, NULL, NULL, 1, 1)""".trimIndent())
            raw.execSQL("""INSERT INTO asset_maintenance_rules (id, assetId, title, triggerType, intervalMonths,
                intervalKm, baselineDateEpochDay, baselineMileageKm, warningDays, warningKm, isActive, notes, createdAt, updatedAt, kind)
                VALUES (6, 3, 'Check', 'TIME', 12, NULL, 20700, NULL, 30, 1000, 1, NULL, 1, 1, 'MAINTENANCE_ITEM')""".trimIndent())
            raw.execSQL("""INSERT INTO asset_maintenance_events (id, assetId, maintenanceRuleId, title,
                performedDateEpochDay, mileageKm, costMinor, serviceMerchantId, linkedTransactionId, notes,
                idempotencyKey, createdAt, providerNameSnapshot)
                VALUES (8, 3, 6, 'Check', 20720, NULL, 10000, NULL, NULL, NULL, 'old-check', 1, NULL)""".trimIndent())
            raw.execSQL("""INSERT INTO asset_documents (id, assetId, documentType, title, storedRelativePath,
                mimeType, originalFileName, fileSizeBytes, createdAt) VALUES
                (4, 3, 'INVOICE', 'Receipt', 'assets/documents/old.pdf', 'application/pdf', 'old.pdf', 42, 1)""".trimIndent())
            raw.version = 12
        } finally { raw.close() }

        val database = Room.databaseBuilder(context, SpendWiseDatabase::class.java, name)
            .addMigrations(SpendWiseDatabase.MIGRATION_12_13).build()
        try {
            val db = database.openHelper.readableDatabase
            db.query("SELECT type, purchasePriceMinor, customTypeId, categoryId, ownershipStatus FROM assets WHERE id = 3").use {
                assertTrue(it.moveToFirst())
                assertEquals("PHONE", it.getString(0))
                assertEquals(350_000L, it.getLong(1))
                assertTrue(it.isNull(2))
                assertTrue(it.isNull(3))
                assertEquals("OWNED", it.getString(4))
            }
            db.query("SELECT amountMinor FROM transactions WHERE id = 5").use {
                assertTrue(it.moveToFirst()); assertEquals(350_000L, it.getLong(0))
            }
            db.query("SELECT transactionId FROM asset_transaction_links WHERE assetId = 3").use {
                assertTrue(it.moveToFirst()); assertEquals(5L, it.getLong(0))
            }
            db.query("SELECT title FROM asset_documents WHERE id = 4").use {
                assertTrue(it.moveToFirst()); assertEquals("Receipt", it.getString(0))
            }
            db.query("SELECT name FROM asset_warranties WHERE id = 2").use {
                assertTrue(it.moveToFirst()); assertEquals("Phone warranty", it.getString(0))
            }
            db.query("SELECT kind FROM asset_maintenance_rules WHERE id = 6").use {
                assertTrue(it.moveToFirst()); assertEquals("MAINTENANCE_ITEM", it.getString(0))
            }
            db.query("SELECT costMinor FROM asset_maintenance_events WHERE id = 8").use {
                assertTrue(it.moveToFirst()); assertEquals(10_000L, it.getLong(0))
            }
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
