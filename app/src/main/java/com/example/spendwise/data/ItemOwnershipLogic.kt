package com.example.spendwise.data

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

fun normalizeCustomAssetTypeName(name: String): String = name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

fun itemTypeLabel(type: AssetType, customTypeId: Long?, customTypes: List<CustomAssetTypeEntity>): String =
    customTypeId?.let { id -> customTypes.firstOrNull { it.id == id }?.name }
        ?: when (type) {
            AssetType.COMPUTER -> "Laptop / Computer"
            else -> type.name.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercase)
        }

fun selectableItemCategories(categories: List<CategoryEntity>, currentCategoryId: Long?): List<CategoryEntity> =
    categories.filter { !it.isArchived || it.id == currentCategoryId }

data class ItemCapabilities(val mileage: Boolean, val maintenance: Boolean, val identifiers: Boolean,
                            val warranty: Boolean = true, val documents: Boolean = true)

fun itemCapabilities(type: AssetType, customTypeId: Long?): ItemCapabilities = when {
    customTypeId != null -> ItemCapabilities(false, false, false)
    type == AssetType.VEHICLE -> ItemCapabilities(true, true, true)
    type in setOf(AssetType.PHONE, AssetType.COMPUTER, AssetType.TABLET, AssetType.ELECTRONICS) ->
        ItemCapabilities(false, false, true)
    else -> ItemCapabilities(false, false, false)
}

data class AssetPurchasePrefill(
    val transactionId: Long,
    val purchaseDateEpochDay: Long,
    val amountMinor: Long,
    val merchantId: Long?,
    val merchantName: String?,
    val categoryId: Long?
)

/** Split rows represent one purchase; V1 never links an item to an arbitrary row. */
fun assetPurchasePrefill(row: TransactionWithCategory): AssetPurchasePrefill? {
    val transaction = row.transaction
    if (transaction.type != TransactionType.EXPENSE || transaction.purchaseGroupId != null) return null
    return AssetPurchasePrefill(transaction.id,
        Instant.ofEpochMilli(transaction.transactionDate).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay(),
        transaction.amountMinor, transaction.merchantId, transaction.merchant, transaction.categoryId)
}
