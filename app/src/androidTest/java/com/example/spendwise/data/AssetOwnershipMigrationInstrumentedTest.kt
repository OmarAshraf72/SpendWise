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
class AssetOwnershipMigrationInstrumentedTest {
    @Test fun migration13To14PreservesExistingItemAndPurchaseLink() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "ownership-lifecycle-migration-${System.nanoTime()}"
        val schema = JSONObject(instrumentation.context.assets
            .open("com.example.spendwise.data.SpendWiseDatabase/13.json")
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
                purchasePriceMinor, sellerMerchantId, currentMileageKm, notes, isArchived,
                createdAt, updatedAt, mileageUpdatedAt, customTypeId, categoryId, ownershipStatus)
                VALUES (3, 'Nike Air Max', 'OTHER', 'Nike', NULL, 20706, 350000, NULL,
                NULL, 'Kept', 0, 1, 1, NULL, NULL, 7, 'SOLD')""".trimIndent())
            raw.execSQL("INSERT INTO asset_transaction_links (assetId, transactionId, relationType) VALUES (3, 5, 'PURCHASE')")
            raw.version = 13
        } finally { raw.close() }

        val database = Room.databaseBuilder(context, SpendWiseDatabase::class.java, name)
            .addMigrations(SpendWiseDatabase.MIGRATION_13_14).build()
        try {
            val db = database.openHelper.readableDatabase
            db.query("SELECT ownershipStatus, categoryId, purchasePriceMinor, notes FROM assets WHERE id = 3").use {
                assertTrue(it.moveToFirst())
                assertEquals("SOLD", it.getString(0))
                assertEquals(7L, it.getLong(1))
                assertEquals(350_000L, it.getLong(2))
                assertEquals("Kept", it.getString(3))
            }
            db.query("SELECT transactionId FROM asset_transaction_links WHERE assetId = 3").use {
                assertTrue(it.moveToFirst()); assertEquals(5L, it.getLong(0))
            }
            db.query("SELECT COUNT(*) FROM asset_ownership_events").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
