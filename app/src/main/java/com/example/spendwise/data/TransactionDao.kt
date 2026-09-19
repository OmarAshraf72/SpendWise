package com.example.spendwise.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Transaction
    @Query("SELECT * FROM transactions ORDER BY transactionDate DESC, createdAt DESC")
    fun observeTransactions(): Flow<List<TransactionWithCategory>>

    @Transaction
    @Query(
        """
        SELECT * FROM transactions
        WHERE type = 'EXPENSE'
          AND transactionDate >= :startInclusive
          AND transactionDate < :endExclusive
        ORDER BY transactionDate DESC, createdAt DESC
        """
    )
    fun observeExpensesBetween(
        startInclusive: Long,
        endExclusive: Long
    ): Flow<List<TransactionWithCategory>>

    @Transaction
    @Query(
        """
        SELECT * FROM transactions
        WHERE transactionDate >= :startInclusive
          AND transactionDate < :endExclusive
        ORDER BY transactionDate DESC, createdAt DESC
        """
    )
    fun observeTransactionsBetween(
        startInclusive: Long,
        endExclusive: Long
    ): Flow<List<TransactionWithCategory>>

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long
}
