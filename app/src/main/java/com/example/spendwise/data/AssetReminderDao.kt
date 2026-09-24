package com.example.spendwise.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetReminderDao {
    @Query("SELECT * FROM asset_reminder_rules ORDER BY id")
    fun observeRules(): Flow<List<AssetReminderRuleEntity>>

    @Query("SELECT * FROM asset_reminder_rules WHERE sourceType = :source AND sourceId = :sourceId")
    suspend fun rulesForSource(source: AssetAttentionSource, sourceId: Long): List<AssetReminderRuleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRule(rule: AssetReminderRuleEntity): Long

    @Query("UPDATE asset_reminder_rules SET isEnabled = :enabled WHERE sourceType = :source AND sourceId = :sourceId AND triggerKind = :kind AND leadValue = :lead")
    suspend fun setRuleEnabled(source: AssetAttentionSource, sourceId: Long, kind: AssetReminderTriggerKind, lead: Long, enabled: Boolean)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDelivery(delivery: AssetNotificationDeliveryEntity): Long

    @Query("DELETE FROM asset_notification_deliveries WHERE ruleId = :ruleId AND occurrenceKey = :occurrenceKey")
    suspend fun deleteDelivery(ruleId: Long, occurrenceKey: String)
}
