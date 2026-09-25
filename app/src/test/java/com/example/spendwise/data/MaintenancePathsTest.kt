package com.example.spendwise.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MaintenancePathsTest {
    private val today = LocalDate.parse("2026-09-25")

    @Test fun scheduledServiceAdvancesFromActualLateMileageAndDate() {
        val rule = rule(1, MaintenanceRuleKind.SERVICE_SCHEDULE, MaintenanceTriggerType.TIME_OR_MILEAGE)
        val completed = event(1, "2026-09-25", 121_250)
        val due = MaintenanceDueCalculator.calculate(rule, today, 121_250, completed)
        assertEquals(131_250L, due.nextDueMileageKm)
        assertEquals(LocalDate.parse("2027-09-25"), due.nextDueDate)
    }

    @Test fun individualItemUsesSameRecurrenceAndOtherCompletionDoesNotAdvanceIt() {
        val service = rule(1, MaintenanceRuleKind.SERVICE_SCHEDULE, MaintenanceTriggerType.MILEAGE)
        val item = rule(2, MaintenanceRuleKind.MAINTENANCE_ITEM, MaintenanceTriggerType.MILEAGE)
        val serviceCompletion = event(1, "2026-09-25", 121_250)
        val serviceDue = MaintenanceDueCalculator.calculate(service, today, 121_250, serviceCompletion)
        val itemDue = MaintenanceDueCalculator.calculate(item, today, 121_250, null)
        assertEquals(131_250L, serviceDue.nextDueMileageKm)
        assertEquals(120_000L, itemDue.nextDueMileageKm)
        assertEquals(131_250L, MaintenanceDueCalculator.calculate(item, today, 121_250,
            event(2, "2026-09-25", 121_250)).nextDueMileageKm)
    }

    @Test fun bothKindsShareOneEventHistoryWithoutFutureOccurrenceRows() {
        val events = listOf(event(1, "2026-09-25", 121_250), event(2, "2026-09-26", 121_300))
        assertEquals(setOf(1L, 2L), events.mapNotNull { it.maintenanceRuleId }.toSet())
        assertEquals(2, events.size)
        assertEquals(2, events.map { it.title }.distinct().size)
    }

    @Test fun suggestionCatalogContainsNamesWithoutIntervalMetadata() {
        assertTrue(MaintenanceItemSuggestions.names.containsAll(listOf("Engine oil", "Brake pads", "Timing belt / chain", "Other / Custom")))
        assertEquals(MaintenanceItemSuggestions.names.size, MaintenanceItemSuggestions.names.distinct().size)
        assertFalse(MaintenanceItemSuggestions.names.any { it.contains(Regex("\\d| km|month", RegexOption.IGNORE_CASE)) })
    }

    private fun rule(id: Long, kind: MaintenanceRuleKind, trigger: MaintenanceTriggerType) =
        AssetMaintenanceRuleEntity(id, 1, if (id == 1L) "Dealer service" else "Battery", trigger,
            if (trigger == MaintenanceTriggerType.MILEAGE) null else 12, 10_000,
            if (trigger == MaintenanceTriggerType.MILEAGE) null else LocalDate.parse("2025-01-01").toEpochDay(),
            110_000, 30, 1_000, true, null, 1, 1, kind)

    private fun event(ruleId: Long, date: String, mileage: Long) = AssetMaintenanceEventEntity(
        id = ruleId, assetId = 1, maintenanceRuleId = ruleId,
        title = if (ruleId == 1L) "Dealer service" else "Battery",
        performedDateEpochDay = LocalDate.parse(date).toEpochDay(), mileageKm = mileage,
        costMinor = null, serviceMerchantId = null, linkedTransactionId = null, notes = null,
        idempotencyKey = "event-$ruleId", createdAt = 1)
}
