package com.example.spendwise.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.assetReminderDataStore by preferencesDataStore(name = "asset_reminder_settings")

class AssetReminderSettings(private val context: Context) {
    private val enabledKey = booleanPreferencesKey("asset_reminders_enabled")
    val enabled: Flow<Boolean> = context.assetReminderDataStore.data.map { it[enabledKey] ?: false }

    suspend fun setEnabled(enabled: Boolean) {
        context.assetReminderDataStore.edit { it[enabledKey] = enabled }
        val manager = WorkManager.getInstance(context)
        if (enabled) {
            manager.enqueueUniquePeriodicWork(
                "asset-reminders-daily", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AssetReminderWorker>(1, TimeUnit.DAYS).build()
            )
        } else {
            manager.cancelUniqueWork("asset-reminders-daily")
        }
    }
}
