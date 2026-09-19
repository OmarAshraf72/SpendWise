package com.example.spendwise.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CategoryEntity::class, TransactionEntity::class, ItemCategoryMappingEntity::class],
    version = 5,
    exportSchema = true
)
@TypeConverters(CategoryTypeConverter::class, TransactionConverters::class)
abstract class SpendWiseDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun itemCategoryMappingDao(): ItemCategoryMappingDao

    companion object {
        @Volatile private var instance: SpendWiseDatabase? = null

        fun getInstance(context: Context): SpendWiseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SpendWiseDatabase::class.java,
                "spendwise.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).addCallback(object : Callback() {
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
            }).build().also { instance = it }
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
    }
}
