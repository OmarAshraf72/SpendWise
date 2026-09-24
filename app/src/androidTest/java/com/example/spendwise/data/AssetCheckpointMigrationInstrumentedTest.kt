package com.example.spendwise.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetCheckpointMigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), SpendWiseDatabase::class.java
    )

    @Test fun migration9To10PreservesAssetsAndWarrantiesAndCreatesReminderTables() {
        val name = "asset-checkpoint-migration"
        helper.createDatabase(name, 9).apply {
            execSQL("""INSERT INTO assets (id, name, type, brand, model, purchaseDateEpochDay,
                purchasePriceMinor, sellerMerchantId, currentMileageKm, notes, isArchived, createdAt, updatedAt)
                VALUES (1, 'Lancer', 'VEHICLE', NULL, NULL, NULL, NULL, NULL, 89000, NULL, 0, 1, 1)""".trimIndent())
            execSQL("""INSERT INTO asset_warranties (id, assetId, name, type, providerName,
                startDateEpochDay, endDateEpochDay, phone, website, notes, createdAt, updatedAt)
                VALUES (1, 1, 'Warranty', 'MANUFACTURER', NULL, 20000, 21000, NULL, NULL, NULL, 1, 1)""".trimIndent())
            close()
        }
        helper.runMigrationsAndValidate(name, 10, true, SpendWiseDatabase.MIGRATION_9_10).apply {
            query("SELECT name, currentMileageKm, mileageUpdatedAt FROM assets WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Lancer", cursor.getString(0))
                assertEquals(89_000L, cursor.getLong(1))
                assertTrue(cursor.isNull(2))
            }
            query("SELECT name FROM asset_warranties WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Warranty", cursor.getString(0))
            }
            query("SELECT count(*) FROM asset_checkpoints").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            close()
        }
    }
}
