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
        FinancialCommitmentEntity::class, CommitmentOccurrenceOverrideEntity::class
    ],
    version = 7,
    exportSchema = true
)
@TypeConverters(
    CategoryTypeConverter::class, TransactionConverters::class, MerchantSourceConverter::class,
    CommitmentConverters::class
)
abstract class SpendWiseDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun itemCategoryMappingDao(): ItemCategoryMappingDao
    abstract fun merchantDao(): MerchantDao
    abstract fun commitmentDao(): CommitmentDao

    companion object {
        @Volatile private var instance: SpendWiseDatabase? = null
        private val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun getInstance(context: Context): SpendWiseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SpendWiseDatabase::class.java,
                "spendwise.db"
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7
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
    }
}
