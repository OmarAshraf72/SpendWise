package com.example.spendwise.data

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class ItemOwnershipLogicTest {
    private val category = CategoryEntity(id = 7, name = "Lifestyle", type = CategoryType.CUSTOM,
        iconName = "menu_book", createdAt = 1)
    private val expense = TransactionEntity(id = 5, type = TransactionType.EXPENSE, amountMinor = 350_000,
        categoryId = category.id, merchant = "Nike Store", note = null,
        transactionDate = LocalDate.of(2026, 9, 10).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        source = TransactionSource.MANUAL, createdAt = 1)
    private val item = AssetEntity(id = 3, name = "Nike Air Max", type = AssetType.OTHER,
        brand = null, model = null, purchaseDateEpochDay = LocalDate.of(2026, 9, 10).toEpochDay(),
        purchasePriceMinor = 350_000, sellerMerchantId = null, currentMileageKm = null,
        notes = null, createdAt = 1, updatedAt = 1, categoryId = category.id)

    @Test fun purchasePrefillCopiesExpenseFieldsAndRejectsSplitOrIncome() {
        val prefill = assetPurchasePrefill(TransactionWithCategory(expense, category))!!
        assertEquals(expense.id, prefill.transactionId)
        assertEquals(350_000L, prefill.amountMinor)
        assertEquals(item.purchaseDateEpochDay, prefill.purchaseDateEpochDay)
        assertEquals("Nike Store", prefill.merchantName)
        assertEquals(category.id, prefill.categoryId)
        assertNull(assetPurchasePrefill(TransactionWithCategory(expense.copy(purchaseGroupId = "split"), category)))
        assertNull(assetPurchasePrefill(TransactionWithCategory(expense.copy(type = TransactionType.INCOME), category)))
    }

    @Test fun itemPurchaseValueNeverAddsToCategorySpending() {
        val entry = buildCategoryExplorerEntries(listOf(category), listOf(TransactionWithCategory(expense, category)),
            listOf(item), LocalDate.of(2026, 9, 25)).single()
        assertEquals(350_000L, entry.monthSpentMinor)
        assertEquals(1, entry.ownedItems.size)
        assertEquals(item.id, entry.ownedItems.single().id)
        assertEquals(expense.id, entry.recentExpenses.single().transaction.id)
        val noTransaction = buildCategoryExplorerEntries(listOf(category), emptyList(), listOf(item), LocalDate.of(2026, 9, 25)).single()
        assertEquals(0L, noTransaction.monthSpentMinor)
    }

    @Test fun onlyOwnedItemsCountAndIncomeDoesNotAddToExpenseTotal() {
        val entries = buildCategoryExplorerEntries(listOf(category), listOf(
            TransactionWithCategory(expense, category),
            TransactionWithCategory(expense.copy(id = 6, type = TransactionType.INCOME, amountMinor = 500_000), category)),
            listOf(item, item.copy(id = 4, ownershipStatus = OwnershipStatus.SOLD)), LocalDate.of(2026, 9, 25))
        assertEquals(350_000L, entries.single().monthSpentMinor)
        assertEquals(1, entries.single().ownedItems.size)
    }

    @Test fun customTypeNamesNormalizeAndBasicItemsHideVehicleFields() {
        assertEquals("shoes", normalizeCustomAssetTypeName("  SHOES  "))
        assertEquals("running shoes", normalizeCustomAssetTypeName("Running   Shoes"))
        assertFalse(itemCapabilities(AssetType.OTHER, 4).mileage)
        assertFalse(itemCapabilities(AssetType.OTHER, 4).maintenance)
        assertFalse(itemCapabilities(AssetType.OTHER, 4).identifiers)
        assertTrue(itemCapabilities(AssetType.VEHICLE, null).maintenance)
        assertEquals(OwnershipStatus.OWNED, item.ownershipStatus)
    }

    @Test fun archivedCategoryIsExcludedForNewItemsButPreservedForEditing() {
        val archived = category.copy(id = 8, name = "Old", isArchived = true)
        assertEquals(listOf(category), selectableItemCategories(listOf(category, archived), null))
        assertEquals(listOf(category, archived), selectableItemCategories(listOf(category, archived), archived.id))
    }

    @Test fun lifecycleLabelsAndSearchCoverOwnedAndHistoricalItems() {
        assertEquals("Given away", OwnershipStatus.GIVEN_AWAY.displayLabel())
        assertEquals("Disposed", OwnershipStatus.DISPOSED.displayLabel())
        assertTrue(itemMatchesSearch(item.copy(brand = "Nike", model = "Air Max"), "Shoes", "nike"))
        assertTrue(itemMatchesSearch(item, "Shoes", "shoes"))
        assertFalse(itemMatchesSearch(item, "Shoes", "watch"))
        OwnershipStatus.entries.filter { it != OwnershipStatus.OWNED }.forEach { status ->
            assertTrue(buildCategoryExplorerEntries(listOf(category), listOf(TransactionWithCategory(expense, category)),
                listOf(item.copy(ownershipStatus = status)), LocalDate.of(2026, 9, 25)).single().ownedItems.isEmpty())
        }
    }
}
