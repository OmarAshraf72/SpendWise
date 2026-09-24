package com.example.spendwise.data

/** Notification policy is pure; platform permission and delivery are handled separately. */
object AssetReminderEligibility {
    fun eligible(
        item: AssetAttentionItem,
        rule: AssetReminderRuleEntity,
        remindersEnabled: Boolean,
        alreadyDelivered: Boolean
    ): Boolean {
        if (!remindersEnabled || alreadyDelivered || !rule.isEnabled) return false
        if (rule.assetId != item.assetId || rule.sourceType != item.sourceType || rule.sourceId != item.sourceId) return false
        return when (rule.triggerKind) {
            AssetReminderTriggerKind.DAYS_BEFORE ->
                item.remainingDays?.let { it > 0 && it <= rule.leadValue } == true
            AssetReminderTriggerKind.KM_BEFORE ->
                item.remainingKm?.let { it > 0 && it <= rule.leadValue } == true
            AssetReminderTriggerKind.ON_DUE ->
                item.remainingDays?.let { it <= 0 } == true || item.remainingKm?.let { it <= 0 } == true
        }
    }
}
