package com.example.spendwise.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class AssetCalculationsTest {
    private val today = LocalDate.parse("2026-09-22")

    @Test fun warrantyStatesUseFixedDateAndInclusiveThirtyDayBoundary() {
        assertEquals(WarrantyState.ACTIVE, WarrantyCalculator.status(today.plusDays(31), today).state)
        assertEquals(WarrantyState.EXPIRING_SOON, WarrantyCalculator.status(today.plusDays(30), today).state)
        assertEquals(WarrantyState.EXPIRED, WarrantyCalculator.status(today.minusDays(1), today).state)
    }

    @Test fun durationToExpiryHandlesLeapYearDeterministically() {
        assertEquals(LocalDate.parse("2025-02-28"), WarrantyCalculator.expiryFromDuration(LocalDate.parse("2024-02-29"), years = 1))
        assertEquals(LocalDate.parse("2026-09-16"), WarrantyCalculator.expiryFromDuration(LocalDate.parse("2024-09-16"), years = 2))
    }

    @Test fun multipleWarrantiesAreEvaluatedIndependently() {
        val statuses = listOf(today.plusDays(10), today.minusDays(10)).map { WarrantyCalculator.status(it, today).state }
        assertEquals(listOf(WarrantyState.EXPIRING_SOON, WarrantyState.EXPIRED), statuses)
    }

    @Test fun timeRuleSupportsOkSoonExactlyDueOverdueAndCompletionAdvance() {
        val rule = rule(MaintenanceTriggerType.TIME, months = 12, baselineDate = "2025-10-23")
        assertEquals(MaintenanceDueStatus.OK, MaintenanceDueCalculator.calculate(rule, today, null, null).status)
        assertEquals(MaintenanceDueStatus.DUE_SOON, MaintenanceDueCalculator.calculate(rule, LocalDate.parse("2026-10-01"), null, null).status)
        assertEquals(MaintenanceDueStatus.DUE, MaintenanceDueCalculator.calculate(rule, LocalDate.parse("2026-10-23"), null, null).status)
        assertEquals(MaintenanceDueStatus.OVERDUE, MaintenanceDueCalculator.calculate(rule, LocalDate.parse("2026-10-24"), null, null).status)
        val completed = event(rule.id, "2026-10-10", 82_000)
        assertEquals(LocalDate.parse("2027-10-10"), MaintenanceDueCalculator.calculate(rule, today, 82_500, completed).nextDueDate)
    }

    @Test fun mileageRuleAdvancesFromLatestCompletionAndHandlesMissingMileage() {
        val rule = rule(MaintenanceTriggerType.MILEAGE, km = 10_000, baselineMileage = 70_000)
        assertEquals(1_000L, MaintenanceDueCalculator.calculate(rule, today, 79_000, null).remainingKm)
        assertEquals(MaintenanceDueStatus.DUE, MaintenanceDueCalculator.calculate(rule, today, 80_000, null).status)
        assertEquals(MaintenanceDueStatus.OVERDUE, MaintenanceDueCalculator.calculate(rule, today, 80_001, null).status)
        assertNull(MaintenanceDueCalculator.calculate(rule, today, null, null).remainingKm)
        assertEquals(92_000L, MaintenanceDueCalculator.calculate(rule, today, 82_500, event(rule.id, "2026-09-20", 82_000)).nextDueMileageKm)
    }

    @Test fun timeOrMileageUsesWhicheverBecomesUrgentFirst() {
        val rule = rule(MaintenanceTriggerType.TIME_OR_MILEAGE, months = 12, km = 10_000, baselineDate = "2025-12-22", baselineMileage = 70_000)
        val mileageFirst = MaintenanceDueCalculator.calculate(rule, today, 79_500, null)
        assertEquals(MaintenanceDueStatus.DUE_SOON, mileageFirst.status)
        assertEquals(MaintenanceTriggerReason.MILEAGE, mileageFirst.triggerReason)
        val timeFirst = MaintenanceDueCalculator.calculate(rule, LocalDate.parse("2026-12-10"), 72_000, null)
        assertEquals(MaintenanceDueStatus.DUE_SOON, timeFirst.status)
        assertEquals(MaintenanceTriggerReason.TIME, timeFirst.triggerReason)
    }

    @Test fun unrelatedAdHocEventDoesNotAdvanceRule() {
        val rule = rule(MaintenanceTriggerType.TIME, months = 12, baselineDate = "2026-01-01")
        val unchanged = MaintenanceDueCalculator.calculate(rule, today, null, null)
        assertEquals(LocalDate.parse("2027-01-01"), unchanged.nextDueDate)
    }

    @Test fun financialLinksCarryOnlyReferencesAndCannotDuplicateAmounts() {
        val commitment = AssetCommitmentLinkEntity(1, 20, AssetCommitmentRelationType.FINANCING)
        val transaction = AssetTransactionLinkEntity(1, 30, AssetTransactionRelationType.MAINTENANCE)
        assertEquals(20, commitment.commitmentId)
        assertEquals(30, transaction.transactionId)
    }

    @Test fun repeatedMaintenanceSaveDoesNotCreateAnotherExpense() {
        val existing = event(null, "2026-09-20", null)
        assertEquals(AssetMaintenanceWritePlan(null, true, true), planAssetMaintenanceWrite(null, true))
        assertEquals(AssetMaintenanceWritePlan(existing.id, false, false), planAssetMaintenanceWrite(existing, true))
    }

    @Test fun documentMetadataHasNoBinaryAndPathsStayPrivate() {
        val fields = AssetDocumentEntity::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("blob", true) || it.contains("bytes", true) && it != "fileSizeBytes" })
        assertTrue(isSafeAssetDocumentPath("assets/documents/abc.pdf"))
        assertFalse(isSafeAssetDocumentPath("../outside.pdf"))
    }

    private fun rule(type: MaintenanceTriggerType, months: Int? = null, km: Long? = null, baselineDate: String? = null, baselineMileage: Long? = null) =
        AssetMaintenanceRuleEntity(1, 1, "Service", type, months, km, baselineDate?.let(LocalDate::parse)?.toEpochDay(), baselineMileage, 30, 1_000, true, null, 1, 1)

    private fun event(ruleId: Long?, date: String, mileage: Long?) = AssetMaintenanceEventEntity(
        id = 5, assetId = 1, maintenanceRuleId = ruleId, title = "Service", performedDateEpochDay = LocalDate.parse(date).toEpochDay(),
        mileageKm = mileage, costMinor = null, serviceMerchantId = null, linkedTransactionId = null, notes = null,
        idempotencyKey = "event-$date", createdAt = 1
    )
}
