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
class AssetMaintenanceKindMigrationInstrumentedTest {
    @Test fun migration11To12PreservesRulesAndEventsAsIndividualMaintenance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "maintenance-kind-migration-${System.nanoTime()}"
        val schema = JSONObject(instrumentation.context.assets.open("com.example.spendwise.data.SpendWiseDatabase/11.json")
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
            raw.execSQL("""INSERT INTO assets (id, name, type, brand, model, purchaseDateEpochDay,
                purchasePriceMinor, sellerMerchantId, currentMileageKm, notes, isArchived, createdAt, updatedAt, mileageUpdatedAt)
                VALUES (1, 'Car', 'VEHICLE', NULL, NULL, NULL, NULL, NULL, 90000, NULL, 0, 1, 1, NULL)""".trimIndent())
            raw.execSQL("""INSERT INTO asset_maintenance_rules (id, assetId, title, triggerType, intervalMonths,
                intervalKm, baselineDateEpochDay, baselineMileageKm, warningDays, warningKm, isActive, notes, createdAt, updatedAt)
                VALUES (1, 1, 'Engine oil', 'MILEAGE', NULL, 10000, NULL, 80000, 30, 1000, 1, NULL, 1, 1)""".trimIndent())
            raw.execSQL("""INSERT INTO asset_maintenance_events (id, assetId, maintenanceRuleId, title,
                performedDateEpochDay, mileageKm, costMinor, serviceMerchantId, linkedTransactionId, notes,
                idempotencyKey, createdAt, providerNameSnapshot)
                VALUES (1, 1, 1, 'Oil change', 20720, 91250, 150000, NULL, NULL, 'Done', 'old-event', 1, NULL)""".trimIndent())
            raw.version = 11
        } finally { raw.close() }
        val database = Room.databaseBuilder(context, SpendWiseDatabase::class.java, name)
            .addMigrations(SpendWiseDatabase.MIGRATION_11_12, SpendWiseDatabase.MIGRATION_12_13,
                SpendWiseDatabase.MIGRATION_13_14).build()
        try {
            database.openHelper.readableDatabase.query("SELECT kind FROM asset_maintenance_rules WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals("MAINTENANCE_ITEM", it.getString(0))
            }
            database.openHelper.readableDatabase.query("SELECT maintenanceRuleId, costMinor FROM asset_maintenance_events WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals(1L, it.getLong(0))
                assertEquals(150_000L, it.getLong(1))
            }
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
