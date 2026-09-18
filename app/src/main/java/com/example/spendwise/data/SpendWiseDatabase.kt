package com.example.spendwise.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CategoryEntity::class], version = 1, exportSchema = true)
@TypeConverters(CategoryTypeConverter::class)
abstract class SpendWiseDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile private var instance: SpendWiseDatabase? = null

        fun getInstance(context: Context): SpendWiseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SpendWiseDatabase::class.java,
                "spendwise.db"
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
    }
}
