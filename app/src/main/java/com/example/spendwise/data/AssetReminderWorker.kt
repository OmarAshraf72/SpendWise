package com.example.spendwise.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.spendwise.MainActivity
import java.time.LocalDate
import kotlinx.coroutines.flow.first

enum class AssetReminderEvaluation { DATE, MILEAGE }

class AssetReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        AssetReminderCoordinator(applicationContext).evaluate(AssetReminderEvaluation.DATE)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

class AssetReminderCoordinator(private val context: Context) {
    private val database = SpendWiseDatabase.getInstance(context)
    private val reminders = database.assetReminderDao()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    suspend fun evaluate(kind: AssetReminderEvaluation, onlyAssetId: Long? = null) {
        if (!AssetReminderSettings(context).enabled.first() || !permissionGranted()) return
        val data = AssetRepository(database).data.first()
        val items = AssetAttentionEngine.evaluate(data, LocalDate.now(), onlyAssetId)
        ensureDefaultRules(items, data)
        val rules = reminders.observeRules().first().filter { it.isEnabled }
        createChannel()
        items.forEach { item ->
            rules.asSequence().filter { it.assetId == item.assetId && it.sourceType == item.sourceType && it.sourceId == item.sourceId }
                .filter { rule ->
                    when (kind) {
                        AssetReminderEvaluation.DATE -> rule.triggerKind == AssetReminderTriggerKind.DAYS_BEFORE ||
                            rule.triggerKind == AssetReminderTriggerKind.ON_DUE && item.remainingDays?.let { it <= 0 } == true
                        AssetReminderEvaluation.MILEAGE -> rule.triggerKind == AssetReminderTriggerKind.KM_BEFORE ||
                            rule.triggerKind == AssetReminderTriggerKind.ON_DUE && item.remainingKm?.let { it <= 0 } == true
                    }
                }.forEach { rule ->
                    if (AssetReminderEligibility.eligible(item, rule, remindersEnabled = true, alreadyDelivered = false)) {
                        val inserted = reminders.insertDelivery(AssetNotificationDeliveryEntity(
                            ruleId = rule.id, occurrenceKey = item.occurrenceKey, deliveredAt = System.currentTimeMillis()
                        ))
                        if (inserted > 0) {
                            try { post(item, rule, data.assets.firstOrNull { it.id == item.assetId }?.name ?: "Asset") }
                            catch (error: Exception) {
                                reminders.deleteDelivery(rule.id, item.occurrenceKey)
                                throw error
                            }
                        }
                    }
                }
        }
    }

    private suspend fun ensureDefaultRules(items: List<AssetAttentionItem>, data: AssetData) {
        items.forEach { item ->
            if (reminders.rulesForSource(item.sourceType, item.sourceId).isNotEmpty()) return@forEach
            val defaults = when (item.sourceType) {
                AssetAttentionSource.WARRANTY -> listOf(AssetReminderTriggerKind.DAYS_BEFORE to 30L)
                AssetAttentionSource.MAINTENANCE -> data.rules.firstOrNull { it.id == item.sourceId }?.let { rule ->
                    listOfNotNull(
                        rule.warningDays.takeIf { item.dueDateEpochDay != null && it > 0 }?.let { AssetReminderTriggerKind.DAYS_BEFORE to it.toLong() },
                        rule.warningKm.takeIf { item.dueMileageKm != null && it > 0 }?.let { AssetReminderTriggerKind.KM_BEFORE to it }
                    )
                }.orEmpty()
                AssetAttentionSource.CHECKPOINT -> data.checkpoints.firstOrNull { it.id == item.sourceId }?.let { checkpoint ->
                    listOfNotNull(
                        checkpoint.warningDays?.takeIf { it > 0 }?.let { AssetReminderTriggerKind.DAYS_BEFORE to it.toLong() },
                        checkpoint.warningKm?.takeIf { it > 0 }?.let { AssetReminderTriggerKind.KM_BEFORE to it }
                    )
                }.orEmpty()
            }
            (defaults + (AssetReminderTriggerKind.ON_DUE to 0L)).forEach { (trigger, lead) ->
                reminders.insertRule(AssetReminderRuleEntity(
                    assetId = item.assetId, sourceType = item.sourceType, sourceId = item.sourceId,
                    triggerKind = trigger, leadValue = lead
                ))
            }
        }
    }

    private fun permissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL, "Asset reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun post(item: AssetAttentionItem, rule: AssetReminderRuleEntity, assetName: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ASSET_ID, item.assetId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val code = ("${rule.id}:${item.occurrenceKey}").hashCode()
        val pending = PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$assetName · ${item.title}")
            .setContentText(item.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(code, notification)
    }

    companion object { const val CHANNEL = "asset_reminders" }
}
