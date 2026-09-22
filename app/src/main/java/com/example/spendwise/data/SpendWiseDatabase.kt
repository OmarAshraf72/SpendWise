package com.example.spendwise.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Database(
    entities = [
        CategoryEntity::class, TransactionEntity::class, ItemCategoryMappingEntity::class, MerchantEntity::class,
        FinancialCommitmentEntity::class, CommitmentOccurrenceOverrideEntity::class,
        DebtProfileEntity::class, DebtPaymentEntity::class, FinancingTermsEntity::class, LatePaymentRuleEntity::class
        , AssetEntity::class, AssetIdentifierEntity::class, AssetWarrantyEntity::class,
        AssetMaintenanceRuleEntity::class, AssetMaintenanceEventEntity::class, AssetDocumentEntity::class,
        AssetCommitmentLinkEntity::class, AssetTransactionLinkEntity::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(
    CategoryTypeConverter::class, TransactionConverters::class, MerchantSourceConverter::class,
    CommitmentConverters::class, DebtConverters::class, AssetConverters::class
)
abstract class SpendWiseDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun itemCategoryMappingDao(): ItemCategoryMappingDao
    abstract fun merchantDao(): MerchantDao
    abstract fun commitmentDao(): CommitmentDao
    abstract fun debtDao(): DebtDao
    abstract fun assetDao(): AssetDao

    companion object {
        @Volatile private var instance: SpendWiseDatabase? = null
        private val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun getInstance(context: Context): SpendWiseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SpendWiseDatabase::class.java,
                "spendwise.db"
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9
            ).addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    val createdAt = System.currentTimeMillis()
                    defaultCategories.forEach { (name, iconName) ->
                        db.execSQL(
                            "INSERT INTO categories (name, type, iconName, createdAt) VALUES (?, ?, ?, ?)",
                            arrayOf<Any>(name, CategoryType.DEFAULT.name, iconName, createdAt)
                        )
                    }
                }
            }).build().also { database ->
                instance = database
                seedScope.launch {
                    MerchantSeeder(database.merchantDao()).seed(database.categoryDao().getActiveCategories())
                }
            }
        }

        private val defaultCategories = listOf(
            "Groceries" to "shopping_cart",
            "Fruits & Vegetables" to "spa",
            "Restaurants" to "restaurant",
            "Transport" to "directions_bus",
            "Household" to "home",
            "Medicine" to "local_hospital",
            "Shopping" to "shopping_bag",
            "Bills" to "receipt_long",
            "Entertainment" to "movie",
            "Personal Care" to "face",
            "Other" to "category"
        )

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL,
                        amount REAL NOT NULL,
                        categoryId INTEGER,
                        merchant TEXT,
                        note TEXT,
                        transactionDate INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_categoryId ON transactions (categoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_transactionDate ON transactions (transactionDate)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE categories ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE transactions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL,
                        amountMinor INTEGER NOT NULL,
                        categoryId INTEGER,
                        merchant TEXT,
                        note TEXT,
                        transactionDate INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO transactions_new (
                        id, type, amountMinor, categoryId, merchant, note,
                        transactionDate, source, createdAt
                    )
                    SELECT
                        id, type, CAST(ROUND(amount * 100.0) AS INTEGER), categoryId,
                        merchant, note, transactionDate, source, createdAt
                    FROM transactions
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")
                db.execSQL("CREATE INDEX index_transactions_categoryId ON transactions (categoryId)")
                db.execSQL("CREATE INDEX index_transactions_transactionDate ON transactions (transactionDate)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN receiptGroupId TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_receiptGroupId " +
                        "ON transactions (receiptGroupId)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS item_category_mappings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        normalizedItemName TEXT NOT NULL,
                        normalizedMerchant TEXT,
                        categoryId INTEGER NOT NULL,
                        confirmationCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_item_category_mappings_normalizedItemName_normalizedMerchant
                    ON item_category_mappings (normalizedItemName, normalizedMerchant)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_item_category_mappings_categoryId
                    ON item_category_mappings (categoryId)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS merchants (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName TEXT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        usageCount INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        defaultCategoryId INTEGER,
                        source TEXT NOT NULL,
                        FOREIGN KEY(defaultCategoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchants_normalizedName ON merchants (normalizedName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_merchants_defaultCategoryId ON merchants (defaultCategoryId)")
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO merchants (
                        displayName, normalizedName, usageCount, lastUsedAt, createdAt, defaultCategoryId, source
                    )
                    SELECT TRIM(merchant), LOWER(TRIM(merchant)), COUNT(*), MAX(transactionDate), MIN(createdAt),
                           NULL, 'USER'
                    FROM transactions
                    WHERE type = 'EXPENSE' AND merchant IS NOT NULL AND TRIM(merchant) <> ''
                    GROUP BY LOWER(TRIM(merchant))
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE transactions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL,
                        amountMinor INTEGER NOT NULL,
                        categoryId INTEGER,
                        merchant TEXT,
                        note TEXT,
                        transactionDate INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        receiptGroupId TEXT,
                        merchantId INTEGER,
                        purchaseGroupId TEXT,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                        FOREIGN KEY(merchantId) REFERENCES merchants(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO transactions_new (
                        id, type, amountMinor, categoryId, merchant, note, transactionDate, source,
                        createdAt, receiptGroupId, merchantId, purchaseGroupId
                    )
                    SELECT transactions.id, transactions.type, transactions.amountMinor, transactions.categoryId,
                           transactions.merchant, transactions.note, transactions.transactionDate, transactions.source,
                           transactions.createdAt, transactions.receiptGroupId,
                           CASE WHEN transactions.type = 'EXPENSE' THEN
                               (SELECT id FROM merchants WHERE normalizedName = LOWER(TRIM(transactions.merchant)) LIMIT 1)
                           ELSE NULL END,
                           transactions.receiptGroupId
                    FROM transactions
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")
                db.execSQL("CREATE INDEX index_transactions_categoryId ON transactions (categoryId)")
                db.execSQL("CREATE INDEX index_transactions_merchantId ON transactions (merchantId)")
                db.execSQL("CREATE INDEX index_transactions_transactionDate ON transactions (transactionDate)")
                db.execSQL("CREATE INDEX index_transactions_receiptGroupId ON transactions (receiptGroupId)")
                db.execSQL("CREATE INDEX index_transactions_purchaseGroupId ON transactions (purchaseGroupId)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS financial_commitments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        amountMinor INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        frequency TEXT NOT NULL,
                        startDateEpochDay INTEGER NOT NULL,
                        endDateEpochDay INTEGER,
                        nextDueDateEpochDay INTEGER NOT NULL,
                        isActive INTEGER NOT NULL,
                        merchantId INTEGER,
                        notes TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(merchantId) REFERENCES merchants(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX index_financial_commitments_merchantId ON financial_commitments (merchantId)")
                db.execSQL("CREATE INDEX index_financial_commitments_nextDueDateEpochDay ON financial_commitments (nextDueDateEpochDay)")
                db.execSQL("CREATE INDEX index_financial_commitments_isActive ON financial_commitments (isActive)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS commitment_occurrence_overrides (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        commitmentId INTEGER NOT NULL,
                        dueDateEpochDay INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        paidTransactionId INTEGER,
                        paidAt INTEGER,
                        FOREIGN KEY(commitmentId) REFERENCES financial_commitments(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(paidTransactionId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_commitment_occurrence_overrides_commitmentId_dueDateEpochDay " +
                        "ON commitment_occurrence_overrides (commitmentId, dueDateEpochDay)"
                )
                db.execSQL(
                    "CREATE INDEX index_commitment_occurrence_overrides_paidTransactionId " +
                        "ON commitment_occurrence_overrides (paidTransactionId)"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS debt_profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        commitmentId INTEGER NOT NULL,
                        creditorName TEXT,
                        originalPrincipalMinor INTEGER NOT NULL,
                        startDateEpochDay INTEGER NOT NULL,
                        expectedEndDateEpochDay INTEGER,
                        repaymentMode TEXT NOT NULL,
                        notes TEXT,
                        isArchived INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(commitmentId) REFERENCES financial_commitments(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX index_debt_profiles_commitmentId ON debt_profiles (commitmentId)")
                db.execSQL("CREATE INDEX index_debt_profiles_repaymentMode ON debt_profiles (repaymentMode)")
                db.execSQL("CREATE INDEX index_debt_profiles_isArchived ON debt_profiles (isArchived)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS debt_payments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        debtProfileId INTEGER NOT NULL,
                        occurrenceDueDateEpochDay INTEGER,
                        paymentDateEpochDay INTEGER NOT NULL,
                        totalPaidMinor INTEGER NOT NULL,
                        principalPaidMinor INTEGER NOT NULL,
                        financingCostPaidMinor INTEGER NOT NULL,
                        lateChargePaidMinor INTEGER NOT NULL,
                        linkedTransactionId INTEGER,
                        note TEXT,
                        idempotencyKey TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(debtProfileId) REFERENCES debt_profiles(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                        FOREIGN KEY(linkedTransactionId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX index_debt_payments_debtProfileId ON debt_payments (debtProfileId)")
                db.execSQL("CREATE INDEX index_debt_payments_occurrenceDueDateEpochDay ON debt_payments (occurrenceDueDateEpochDay)")
                db.execSQL("CREATE INDEX index_debt_payments_linkedTransactionId ON debt_payments (linkedTransactionId)")
                db.execSQL("CREATE UNIQUE INDEX index_debt_payments_idempotencyKey ON debt_payments (idempotencyKey)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS financing_terms (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        debtProfileId INTEGER NOT NULL,
                        financingType TEXT NOT NULL,
                        totalRepayableMinor INTEGER,
                        rateBasisPoints INTEGER,
                        ratePeriod TEXT,
                        rateBasis TEXT,
                        calculationMethod TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(debtProfileId) REFERENCES debt_profiles(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX index_financing_terms_debtProfileId ON financing_terms (debtProfileId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS late_payment_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        debtProfileId INTEGER NOT NULL,
                        gracePeriodDays INTEGER NOT NULL,
                        chargeType TEXT NOT NULL,
                        fixedChargeMinor INTEGER,
                        rateBasisPoints INTEGER,
                        chargeInterval TEXT NOT NULL,
                        chargeBasis TEXT NOT NULL,
                        minimumChargeMinor INTEGER,
                        maximumChargeMinor INTEGER,
                        isCompounding INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(debtProfileId) REFERENCES debt_profiles(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX index_late_payment_rules_debtProfileId ON late_payment_rules (debtProfileId)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS assets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL,
                    brand TEXT, model TEXT, purchaseDateEpochDay INTEGER, purchasePriceMinor INTEGER,
                    sellerMerchantId INTEGER, currentMileageKm INTEGER, notes TEXT, isArchived INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(sellerMerchantId) REFERENCES merchants(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""".trimIndent())
                db.execSQL("CREATE INDEX index_assets_sellerMerchantId ON assets (sellerMerchantId)")
                db.execSQL("CREATE INDEX index_assets_type ON assets (type)")
                db.execSQL("CREATE INDEX index_assets_isArchived ON assets (isArchived)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS asset_identifiers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, assetId INTEGER NOT NULL, type TEXT NOT NULL,
                    label TEXT, value TEXT NOT NULL,
                    FOREIGN KEY(assetId) REFERENCES assets(id) ON UPDATE NO ACTION ON DELETE CASCADE)""".trimIndent())
                db.execSQL("CREATE INDEX index_asset_identifiers_assetId ON asset_identifiers (assetId)")
                db.execSQL("CREATE UNIQUE INDEX index_asset_identifiers_assetId_type_value ON asset_identifiers (assetId, type, value)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS asset_warranties (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, assetId INTEGER NOT NULL, name TEXT NOT NULL,
                    type TEXT NOT NULL, providerName TEXT, startDateEpochDay INTEGER NOT NULL, endDateEpochDay INTEGER NOT NULL,
                    phone TEXT, website TEXT, notes TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(assetId) REFERENCES assets(id) ON UPDATE NO ACTION ON DELETE CASCADE)""".trimIndent())
                db.execSQL("CREATE INDEX index_asset_warranties_assetId ON asset_warranties (assetId)")
                db.execSQL("CREATE INDEX index_asset_warranties_endDateEpochDay ON asset_warranties (endDateEpochDay)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS asset_maintenance_rules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, assetId INTEGER NOT NULL, title TEXT NOT NULL,
                    triggerType TEXT NOT NULL, intervalMonths INTEGER, intervalKm INTEGER, baselineDateEpochDay INTEGER,
                    baselineMileageKm INTEGER, warningDays INTEGER NOT NULL, warningKm INTEGER NOT NULL,
                    isActive INTEGER NOT NULL, notes TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(assetId) REFERENCES assets(id) ON UPDATE NO ACTION ON DELETE CASCADE)""".trimIndent())
                db.execSQL("CREATE INDEX index_asset_maintenance_rules_assetId ON asset_maintenance_rules (assetId)")
                db.execSQL("CREATE INDEX index_asset_maintenance_rules_isActive ON asset_maintenance_rules (isActive)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS asset_maintenance_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, assetId INTEGER NOT NULL, maintenanceRuleId INTEGER,
                    title TEXT NOT NULL, performedDateEpochDay INTEGER NOT NULL, mileageKm INTEGER, costMinor INTEGER,
                    serviceMerchantId INTEGER, linkedTransactionId INTEGER, notes TEXT, idempotencyKey TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(assetId) REFERENCES assets(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(maintenanceRuleId) REFERENCES asset_maintenance_rules(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(serviceMerchantId) REFERENCES merchants(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(linkedTransactionId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""".trimIndent())
                db.execSQL("CREATE INDEX index_asset_maintenance_events_assetId ON asset_maintenance_events (assetId)")
                db.execSQL("CREATE INDEX index_asset_maintenance_events_maintenanceRuleId ON asset_maintenance_events (maintenanceRuleId)")
                db.execSQL("CREATE INDEX index_asset_maintenance_events_serviceMerchantId ON asset_maintenance_events (serviceMerchantId)")
                db.execSQL("CREATE INDEX index_asset_maintenance_events_linkedTransactionId ON asset_maintenance_events (linkedTransactionId)")
                db.execSQL("CREATE UNIQUE INDEX index_asset_maintenance_events_idempotencyKey ON asset_maintenance_events (idempotencyKey)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS asset_documents (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, assetId INTEGER NOT NULL, documentType TEXT NOT NULL,
                    title TEXT NOT NULL, storedRelativePath TEXT NOT NULL, mimeType TEXT NOT NULL,
                    originalFileName TEXT NOT NULL, fileSizeBytes INTEGER NOT NULL, createdAt INTEGER NOT NULL,
                    FOREIGN KEY(assetId) REFERENCES assets(id) ON UPDATE NO ACTION ON DELETE CASCADE)""".trimIndent())
                db.execSQL("CREATE INDEX index_asset_documents_assetId ON asset_documents (assetId)")
                db.execSQL("CREATE INDEX index_asset_documents_documentType ON asset_documents (documentType)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS asset_commitment_links (
                    assetId INTEGER NOT NULL, commitmentId INTEGER NOT NULL, relationType TEXT NOT NULL,
                    PRIMARY KEY(assetId, commitmentId, relationType),
                    FOREIGN KEY(assetId) REFERENCES assets(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(commitmentId) REFERENCES financial_commitments(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""".trimIndent())
                db.execSQL("CREATE INDEX index_asset_commitment_links_commitmentId ON asset_commitment_links (commitmentId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS asset_transaction_links (
                    assetId INTEGER NOT NULL, transactionId INTEGER NOT NULL, relationType TEXT NOT NULL,
                    PRIMARY KEY(assetId, transactionId, relationType),
                    FOREIGN KEY(assetId) REFERENCES assets(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(transactionId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""".trimIndent())
                db.execSQL("CREATE INDEX index_asset_transaction_links_transactionId ON asset_transaction_links (transactionId)")
            }
        }
    }
}
