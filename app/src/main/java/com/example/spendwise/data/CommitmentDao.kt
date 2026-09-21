package com.example.spendwise.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CommitmentDao {
    @Transaction
    @Query("SELECT * FROM financial_commitments ORDER BY nextDueDateEpochDay ASC, title ASC")
    fun observeCommitments(): Flow<List<CommitmentWithMerchant>>

    @Query("SELECT * FROM commitment_occurrence_overrides")
    fun observeOverrides(): Flow<List<CommitmentOccurrenceOverrideEntity>>

    @Query("SELECT * FROM financial_commitments WHERE id = :id LIMIT 1")
    suspend fun getCommitment(id: Long): FinancialCommitmentEntity?

    @Transaction
    @Query("SELECT * FROM financial_commitments WHERE id = :id LIMIT 1")
    suspend fun getCommitmentWithMerchant(id: Long): CommitmentWithMerchant?

    @Query("SELECT * FROM commitment_occurrence_overrides WHERE commitmentId = :commitmentId AND dueDateEpochDay = :dueDate LIMIT 1")
    suspend fun getOverride(commitmentId: Long, dueDate: Long): CommitmentOccurrenceOverrideEntity?

    @Insert suspend fun insert(commitment: FinancialCommitmentEntity): Long
    @Update suspend fun update(commitment: FinancialCommitmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOverride(override: CommitmentOccurrenceOverrideEntity): Long
}
