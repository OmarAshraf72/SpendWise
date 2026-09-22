package com.example.spendwise.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class AssetFormLogicTest {
    private val today = LocalDate.parse("2026-09-22")

    @Test fun identifierAdditionRejectsBlankAndDuplicateButAllowsMultipleSameType() {
        val empty = emptyList<PendingAssetIdentifier>()
        val blank = AssetIdentifierDraftLogic.add(empty, AssetIdentifierType.IMEI, "  ")
        assertEquals(empty, blank.identifiers)
        assertNotNull(blank.error)

        val first = AssetIdentifierDraftLogic.add(empty, AssetIdentifierType.IMEI, " 12345 ")
        assertNull(first.error)
        assertEquals(listOf(PendingAssetIdentifier(AssetIdentifierType.IMEI, "12345")), first.identifiers)
        val duplicate = AssetIdentifierDraftLogic.add(first.identifiers, AssetIdentifierType.IMEI, "12345")
        assertEquals(first.identifiers, duplicate.identifiers)
        assertNotNull(duplicate.error)
        val second = AssetIdentifierDraftLogic.add(first.identifiers, AssetIdentifierType.IMEI, "67890")
        assertEquals(2, second.identifiers.size)
        assertEquals(first.identifiers, AssetIdentifierDraftLogic.remove(second.identifiers, 1))
    }

    @Test fun purchaseDateControlsUntouchedWarrantyStartAndDerivedExpiry() {
        val firstPurchase = LocalDate.parse("2026-03-15").toEpochDay()
        val changedPurchase = LocalDate.parse("2026-03-20").toEpochDay()
        val initial = WarrantyDateDraft.new(firstPurchase, today)
        assertEquals(firstPurchase, initial.startDateEpochDay)
        assertEquals(LocalDate.parse("2027-03-15").toEpochDay(), initial.endDateEpochDay)
        val changed = initial.withPurchaseDate(changedPurchase, today)
        assertEquals(changedPurchase, changed.startDateEpochDay)
        assertEquals(LocalDate.parse("2027-03-20").toEpochDay(), changed.endDateEpochDay)
        assertEquals(today.toEpochDay(), WarrantyDateDraft.new(null, today).startDateEpochDay)
    }

    @Test fun manualWarrantyStartSurvivesPurchaseChangeAndExpiryFollowsStart() {
        val originalPurchase = LocalDate.parse("2026-03-15").toEpochDay()
        val override = WarrantyDateDraft.new(originalPurchase, today).withManualStart(LocalDate.parse("2026-04-01"))
            .withPurchaseDate(LocalDate.parse("2026-03-20").toEpochDay(), today)
        assertEquals(LocalDate.parse("2026-04-01").toEpochDay(), override.startDateEpochDay)
        assertEquals(LocalDate.parse("2027-04-01").toEpochDay(), override.endDateEpochDay)
    }

    @Test fun editingExistingWarrantyPreservesPersistedDates() {
        val warranty = AssetWarrantyEntity(7, 1, "Phone", AssetWarrantyType.MANUFACTURER, null,
            LocalDate.parse("2025-05-01").toEpochDay(), LocalDate.parse("2027-05-01").toEpochDay(), null, null, null, 1, 1)
        val edit = WarrantyDateDraft.existing(warranty, LocalDate.parse("2025-04-01").toEpochDay())
            .withPurchaseDate(LocalDate.parse("2025-04-15").toEpochDay(), today)
        assertEquals(warranty.startDateEpochDay, edit.startDateEpochDay)
        assertEquals(warranty.endDateEpochDay, edit.endDateEpochDay)
    }
}
