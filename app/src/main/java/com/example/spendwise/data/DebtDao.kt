package com.example.spendwise.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DebtDao {
    @Query("SELECT * FROM debt_profiles WHERE isArchived = 0 ORDER BY createdAt DESC")
    fun observeActiveProfiles(): Flow<List<DebtProfileEntity>>

    @Query("SELECT * FROM debt_payments ORDER BY paymentDateEpochDay DESC, createdAt DESC")
    fun observePayments(): Flow<List<DebtPaymentEntity>>

    @Query("SELECT * FROM financing_terms")
    fun observeFinancingTerms(): Flow<List<FinancingTermsEntity>>

    @Query("SELECT * FROM late_payment_rules")
    fun observeLateRules(): Flow<List<LatePaymentRuleEntity>>

    @Query("SELECT * FROM debt_profiles WHERE id = :id LIMIT 1") suspend fun getProfile(id: Long): DebtProfileEntity?
    @Query("SELECT * FROM debt_profiles WHERE commitmentId = :commitmentId LIMIT 1") suspend fun getProfileForCommitment(commitmentId: Long): DebtProfileEntity?
    @Query("SELECT * FROM financing_terms WHERE debtProfileId = :profileId LIMIT 1") suspend fun getFinancing(profileId: Long): FinancingTermsEntity?
    @Query("SELECT * FROM late_payment_rules WHERE debtProfileId = :profileId LIMIT 1") suspend fun getLateRule(profileId: Long): LatePaymentRuleEntity?
    @Query("SELECT * FROM debt_payments WHERE debtProfileId = :profileId ORDER BY paymentDateEpochDay DESC, createdAt DESC") suspend fun getPayments(profileId: Long): List<DebtPaymentEntity>
    @Query("SELECT * FROM debt_payments WHERE idempotencyKey = :key LIMIT 1") suspend fun getPaymentByKey(key: String): DebtPaymentEntity?
    @Query("SELECT COALESCE(SUM(principalPaidMinor + financingCostPaidMinor), 0) FROM debt_payments WHERE debtProfileId = :profileId AND occurrenceDueDateEpochDay = :dueDate") suspend fun allocatedForOccurrence(profileId: Long, dueDate: Long): Long

    @Insert suspend fun insertProfile(profile: DebtProfileEntity): Long
    @Update suspend fun updateProfile(profile: DebtProfileEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFinancing(terms: FinancingTermsEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLateRule(rule: LatePaymentRuleEntity): Long
    @Insert suspend fun insertPayment(payment: DebtPaymentEntity): Long
    @Query("DELETE FROM financing_terms WHERE debtProfileId = :profileId") suspend fun deleteFinancing(profileId: Long)
    @Query("DELETE FROM late_payment_rules WHERE debtProfileId = :profileId") suspend fun deleteLateRule(profileId: Long)
    @Query("UPDATE debt_profiles SET isArchived = 1, updatedAt = :now WHERE id = :id") suspend fun archive(id: Long, now: Long)
}
