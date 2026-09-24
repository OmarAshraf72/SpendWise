package com.example.spendwise.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AssetAttentionEngineTest {
    private val today = LocalDate.of(2026, 9, 25)
    private fun asset(mileage: Long? = null, archived: Boolean = false) = AssetEntity(
        id = 1, name = "Car", type = AssetType.VEHICLE, brand = null, model = null,
        purchaseDateEpochDay = null, purchasePriceMinor = null, sellerMerchantId = null,
        currentMileageKm = mileage, notes = null, isArchived = archived, createdAt = 1, updatedAt = 1
    )
    private fun checkpoint(mode: CheckpointTriggerMode, date: Long? = null, km: Long? = null,
                           repeatMonths: Int? = null, repeatKm: Long? = null, active: Boolean = true) = AssetCheckpointEntity(
        id = 10, assetId = 1, title = "Inspection", checkpointType = AssetCheckpointType.INSPECTION,
        triggerMode = mode, dueDateEpochDay = date, dueMileageKm = km,
        repeatMonths = repeatMonths, repeatKm = repeatKm, warningDays = 30, warningKm = 1000,
        notes = null, isActive = active, createdAt = 1, updatedAt = 1
    )
    private fun data(asset: AssetEntity = asset(), warranties: List<AssetWarrantyEntity> = emptyList(),
                     rules: List<AssetMaintenanceRuleEntity> = emptyList(), events: List<AssetMaintenanceEventEntity> = emptyList(),
                     checkpoints: List<AssetCheckpointEntity> = emptyList(), checkpointEvents: List<AssetCheckpointEventEntity> = emptyList()) =
        AssetData(listOf(asset), emptyList(), warranties, rules, events, emptyList(), emptyList(), emptyList(), checkpoints, checkpointEvents)

    @Test fun dateMileageAndOrModesUseTheirDuePoints() {
        val date = checkpoint(CheckpointTriggerMode.DATE, today.plusDays(10).toEpochDay())
        assertEquals(AssetAttentionStatus.DUE_SOON, AssetAttentionEngine.evaluate(data(checkpoints = listOf(date)), today).single().status)
        val mileage = checkpoint(CheckpointTriggerMode.MILEAGE, km = 90_000)
        assertEquals(AssetAttentionStatus.DUE_SOON, AssetAttentionEngine.evaluate(data(asset(89_200), checkpoints = listOf(mileage)), today).single().status)
        val either = checkpoint(CheckpointTriggerMode.DATE_OR_MILEAGE, today.plusDays(90).toEpochDay(), 90_000)
        assertEquals(AssetAttentionStatus.DUE_SOON, AssetAttentionEngine.evaluate(data(asset(89_200), checkpoints = listOf(either)), today).single().status)
        assertEquals(AssetAttentionStatus.DUE, AssetAttentionEngine.evaluate(data(asset(90_000), checkpoints = listOf(either)), today).single().status)
        assertEquals(AssetAttentionStatus.OVERDUE, AssetAttentionEngine.evaluate(data(asset(90_500), checkpoints = listOf(either)), today).single().status)
    }

    @Test fun recurrenceUsesLatestCompletionAndNonRecurringCanEnd() {
        val recurring = checkpoint(CheckpointTriggerMode.DATE_OR_MILEAGE, today.toEpochDay(), 90_000, 12, 10_000)
        val event = AssetCheckpointEventEntity(id = 2, checkpointId = 10, completedDateEpochDay = today.minusDays(1).toEpochDay(),
            completedMileageKm = 90_500, note = null, createdAt = 2)
        val next = AssetAttentionEngine.checkpointOccurrence(recurring, event)
        assertEquals(today.minusDays(1).plusMonths(12).toEpochDay(), next.dueDateEpochDay)
        assertEquals(100_500L, next.dueMileageKm)
        assertEquals(AssetAttentionStatus.UPCOMING, AssetAttentionEngine.evaluate(data(asset(90_500), checkpoints = listOf(recurring), checkpointEvents = listOf(event)), today).single().status)
        assertTrue(AssetAttentionEngine.evaluate(data(checkpoints = listOf(recurring.copy(isActive = false))), today).isEmpty())
    }

    @Test fun recurrenceRequiresExplicitIntervalsAndMileageAtCompletion() {
        val date = checkpoint(CheckpointTriggerMode.DATE, today.toEpochDay(), repeatMonths = 12)
        val event = AssetCheckpointEventEntity(checkpointId = 10, completedDateEpochDay = today.toEpochDay(), completedMileageKm = null, note = null, createdAt = 1)
        assertEquals(today.plusMonths(12).toEpochDay(), AssetAttentionEngine.checkpointOccurrence(date, event).dueDateEpochDay)
        val mileage = checkpoint(CheckpointTriggerMode.MILEAGE, km = 90_000, repeatKm = 10_000)
        assertNull(AssetAttentionEngine.checkpointOccurrence(mileage, event).dueMileageKm)
        assertThrows(IllegalArgumentException::class.java) { AssetAttentionEngine.validateCheckpoint(mileage.copy(repeatKm = 0)) }
    }

    @Test fun warrantyMaintenanceAndCheckpointSharePriorityOrdering() {
        val warranty = AssetWarrantyEntity(id = 20, assetId = 1, name = "Warranty", type = AssetWarrantyType.MANUFACTURER,
            providerName = null, startDateEpochDay = today.minusYears(1).toEpochDay(), endDateEpochDay = today.plusDays(20).toEpochDay(),
            phone = null, website = null, notes = null, createdAt = 1, updatedAt = 1)
        val rule = AssetMaintenanceRuleEntity(id = 30, assetId = 1, title = "Oil", triggerType = MaintenanceTriggerType.TIME_OR_MILEAGE,
            intervalMonths = 1, intervalKm = 1000, baselineDateEpochDay = today.minusMonths(1).toEpochDay(), baselineMileageKm = 89_000,
            warningDays = 30, warningKm = 1000, notes = null, createdAt = 1, updatedAt = 1)
        val due = checkpoint(CheckpointTriggerMode.DATE, today.minusDays(2).toEpochDay())
        val result = AssetAttentionEngine.evaluate(data(asset(90_000), listOf(warranty), listOf(rule), checkpoints = listOf(due)), today)
        assertEquals(3, result.size)
        assertEquals(AssetAttentionSource.CHECKPOINT, result.first().sourceType)
        assertEquals(AssetAttentionStatus.OVERDUE, result.first().status)
        assertEquals(AssetAttentionStatus.DUE, result[1].status)
        assertEquals(AssetAttentionStatus.DUE_SOON, result[2].status)
        assertTrue(AssetAttentionEngine.evaluate(data(asset(90_000, archived = true), listOf(warranty), listOf(rule), checkpoints = listOf(due)), today).isEmpty())
    }

    @Test fun maintenanceTimeMileageAndOrUseExistingRuleSemantics() {
        val base = AssetMaintenanceRuleEntity(id = 30, assetId = 1, title = "Service", triggerType = MaintenanceTriggerType.TIME,
            intervalMonths = 1, intervalKm = null, baselineDateEpochDay = today.minusMonths(1).toEpochDay(),
            baselineMileageKm = null, warningDays = 20, warningKm = 1000, notes = null, createdAt = 1, updatedAt = 1)
        assertEquals(AssetAttentionStatus.DUE, AssetAttentionEngine.evaluate(data(rules = listOf(base)), today).single().status)
        val mileage = base.copy(triggerType = MaintenanceTriggerType.MILEAGE, intervalMonths = null,
            intervalKm = 1000, baselineDateEpochDay = null, baselineMileageKm = 89_000)
        assertEquals(AssetAttentionStatus.DUE_SOON, AssetAttentionEngine.evaluate(data(asset(89_500), rules = listOf(mileage)), today).single().status)
        val either = base.copy(triggerType = MaintenanceTriggerType.TIME_OR_MILEAGE, intervalKm = 1000, baselineMileageKm = 89_000)
        assertEquals(AssetAttentionStatus.DUE, AssetAttentionEngine.evaluate(data(asset(89_500), rules = listOf(either)), today).single().status)
    }

    @Test fun missingMileageDoesNotCreateFalseWarningAndThresholdCrossesOnUpdate() {
        val due = checkpoint(CheckpointTriggerMode.MILEAGE, km = 90_000)
        assertEquals(AssetAttentionStatus.UPCOMING, AssetAttentionEngine.evaluate(data(checkpoints = listOf(due)), today).single().status)
        assertEquals(AssetAttentionStatus.UPCOMING, AssetAttentionEngine.evaluate(data(asset(88_999), checkpoints = listOf(due)), today).single().status)
        assertEquals(AssetAttentionStatus.DUE_SOON, AssetAttentionEngine.evaluate(data(asset(89_000), checkpoints = listOf(due)), today).single().status)
        assertEquals(AssetAttentionStatus.DUE, AssetAttentionEngine.evaluate(data(asset(90_000), checkpoints = listOf(due)), today).single().status)
        assertFalse(canUpdateAssetMileage(90_000, 89_999))
        assertFalse(canUpdateAssetMileage(null, -1))
        assertTrue(canUpdateAssetMileage(89_000, 89_200))
    }

    @Test fun notificationEligibilityRespectsThresholdsDuplicatesAndGlobalSwitch() {
        val item = AssetAttentionEngine.evaluate(data(asset(89_250), checkpoints = listOf(
            checkpoint(CheckpointTriggerMode.DATE_OR_MILEAGE, today.plusDays(6).toEpochDay(), 90_000))), today).single()
        fun rule(kind: AssetReminderTriggerKind, lead: Long, enabled: Boolean = true) = AssetReminderRuleEntity(
            id = lead + 1, assetId = 1, sourceType = AssetAttentionSource.CHECKPOINT, sourceId = 10,
            triggerKind = kind, leadValue = lead, isEnabled = enabled)
        assertTrue(AssetReminderEligibility.eligible(item, rule(AssetReminderTriggerKind.DAYS_BEFORE, 30), true, false))
        assertTrue(AssetReminderEligibility.eligible(item, rule(AssetReminderTriggerKind.DAYS_BEFORE, 7), true, false))
        assertTrue(AssetReminderEligibility.eligible(item, rule(AssetReminderTriggerKind.KM_BEFORE, 1000), true, false))
        assertFalse(AssetReminderEligibility.eligible(item, rule(AssetReminderTriggerKind.KM_BEFORE, 500), true, false))
        val closer = AssetAttentionEngine.evaluate(data(asset(89_600), checkpoints = listOf(
            checkpoint(CheckpointTriggerMode.MILEAGE, km = 90_000))), today).single()
        assertTrue(AssetReminderEligibility.eligible(closer, rule(AssetReminderTriggerKind.KM_BEFORE, 500), true, false))
        assertFalse(AssetReminderEligibility.eligible(item, rule(AssetReminderTriggerKind.ON_DUE, 0), true, false))
        assertFalse(AssetReminderEligibility.eligible(item, rule(AssetReminderTriggerKind.DAYS_BEFORE, 30), false, false))
        assertFalse(AssetReminderEligibility.eligible(item, rule(AssetReminderTriggerKind.DAYS_BEFORE, 30, false), true, false))
        assertFalse(AssetReminderEligibility.eligible(item, rule(AssetReminderTriggerKind.DAYS_BEFORE, 30), true, true))
        assertEquals(AssetAttentionStatus.DUE_SOON, item.status) // In-app attention remains available with notifications off.
    }

    @Test fun dueAndOverdueRulesAndNewOccurrences() {
        val checkpoint = checkpoint(CheckpointTriggerMode.DATE, today.toEpochDay(), repeatMonths = 12)
        val due = AssetAttentionEngine.evaluate(data(checkpoints = listOf(checkpoint)), today).single()
        val rule = AssetReminderRuleEntity(id = 1, assetId = 1, sourceType = AssetAttentionSource.CHECKPOINT,
            sourceId = 10, triggerKind = AssetReminderTriggerKind.ON_DUE, leadValue = 0)
        assertTrue(AssetReminderEligibility.eligible(due, rule, true, false))
        val overdue = AssetAttentionEngine.evaluate(data(checkpoints = listOf(checkpoint)), today.plusDays(3)).single()
        assertEquals(AssetAttentionStatus.OVERDUE, overdue.status)
        assertTrue(AssetReminderEligibility.eligible(overdue, rule, true, false))
        val event = AssetCheckpointEventEntity(checkpointId = 10, completedDateEpochDay = today.toEpochDay(), completedMileageKm = null, note = null, createdAt = 2)
        val nextYear = AssetAttentionEngine.evaluate(data(checkpoints = listOf(checkpoint), checkpointEvents = listOf(event)), today.plusMonths(12)).single()
        assertTrue(nextYear.occurrenceKey != due.occurrenceKey)
        assertTrue(AssetReminderEligibility.eligible(nextYear, rule, true, false))
    }
}
