package com.example.spendwise.data

import java.time.LocalDate

data class PendingAssetIdentifier(val type: AssetIdentifierType, val value: String)

data class IdentifierDraftResult(
    val identifiers: List<PendingAssetIdentifier>,
    val error: String? = null
)

object AssetIdentifierDraftLogic {
    fun add(
        current: List<PendingAssetIdentifier>,
        type: AssetIdentifierType,
        input: String
    ): IdentifierDraftResult {
        val value = input.trim()
        if (value.isEmpty()) return IdentifierDraftResult(current, "Enter an identifier value.")
        if (current.any { it.type == type && it.value == value }) {
            return IdentifierDraftResult(current, "This identifier is already added.")
        }
        return IdentifierDraftResult(current + PendingAssetIdentifier(type, value))
    }

    fun remove(current: List<PendingAssetIdentifier>, index: Int): List<PendingAssetIdentifier> =
        current.filterIndexed { itemIndex, _ -> itemIndex != index }
}

data class WarrantyDateDraft(
    val purchaseDateEpochDay: Long?,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long,
    val startManuallyOverridden: Boolean = false,
    val endManuallyOverridden: Boolean = false
) {
    fun withPurchaseDate(purchaseDate: Long?, today: LocalDate): WarrantyDateDraft {
        val start = if (startManuallyOverridden) startDateEpochDay else purchaseDate ?: today.toEpochDay()
        return copy(
            purchaseDateEpochDay = purchaseDate,
            startDateEpochDay = start,
            endDateEpochDay = if (endManuallyOverridden) endDateEpochDay else LocalDate.ofEpochDay(start).plusYears(1).toEpochDay()
        )
    }

    fun withManualStart(start: LocalDate): WarrantyDateDraft = copy(
        startDateEpochDay = start.toEpochDay(),
        endDateEpochDay = if (endManuallyOverridden) endDateEpochDay else start.plusYears(1).toEpochDay(),
        startManuallyOverridden = true
    )

    fun withManualEnd(end: LocalDate): WarrantyDateDraft =
        copy(endDateEpochDay = end.toEpochDay(), endManuallyOverridden = true)

    companion object {
        fun new(purchaseDate: Long?, today: LocalDate): WarrantyDateDraft {
            val start = purchaseDate ?: today.toEpochDay()
            return WarrantyDateDraft(purchaseDate, start, LocalDate.ofEpochDay(start).plusYears(1).toEpochDay())
        }

        fun existing(warranty: AssetWarrantyEntity, purchaseDate: Long?): WarrantyDateDraft =
            WarrantyDateDraft(purchaseDate, warranty.startDateEpochDay, warranty.endDateEpochDay, true, true)
    }
}
