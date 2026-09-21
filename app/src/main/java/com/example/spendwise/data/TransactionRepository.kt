package com.example.spendwise.data

class TransactionRepository(private val dao: TransactionDao) {
    val transactions = dao.observeTransactions()

    fun observeExpensesBetween(startInclusive: Long, endExclusive: Long) =
        dao.observeExpensesBetween(startInclusive, endExclusive)

    fun observeTransactionsBetween(startInclusive: Long, endExclusive: Long) =
        dao.observeTransactionsBetween(startInclusive, endExclusive)

    suspend fun addManualExpense(
        amountMinor: Long,
        categoryId: Long,
        merchant: String?,
        note: String?,
        transactionDate: Long
    ) {
        dao.insert(
            TransactionEntity(
                type = TransactionType.EXPENSE,
                amountMinor = amountMinor,
                categoryId = categoryId,
                merchant = merchant?.takeIf { it.isNotBlank() },
                note = note?.takeIf { it.isNotBlank() },
                transactionDate = transactionDate,
                source = TransactionSource.MANUAL,
                createdAt = System.currentTimeMillis(),
                receiptGroupId = null,
                merchantId = null,
                purchaseGroupId = null
            )
        )
    }

    suspend fun addManualIncome(
        amountMinor: Long,
        name: String?,
        note: String?,
        transactionDate: Long
    ) {
        dao.insert(
            TransactionEntity(
                type = TransactionType.INCOME,
                amountMinor = amountMinor,
                categoryId = null,
                merchant = name?.takeIf { it.isNotBlank() },
                note = note?.takeIf { it.isNotBlank() },
                transactionDate = transactionDate,
                source = TransactionSource.MANUAL,
                createdAt = System.currentTimeMillis(),
                receiptGroupId = null,
                merchantId = null,
                purchaseGroupId = null
            )
        )
    }

    suspend fun addReceiptExpenses(
        items: List<ReceiptExpenseItem>,
        merchant: String?,
        transactionDate: Long,
        receiptGroupId: String
    ) {
        val createdAt = System.currentTimeMillis()
        dao.insertAll(
            items.mapIndexed { index, item ->
                TransactionEntity(
                    type = TransactionType.EXPENSE,
                    amountMinor = item.amountMinor,
                    categoryId = item.categoryId,
                    merchant = merchant?.takeIf { it.isNotBlank() },
                    note = item.name,
                    transactionDate = transactionDate,
                    source = TransactionSource.RECEIPT,
                    createdAt = createdAt + index,
                    receiptGroupId = receiptGroupId,
                    merchantId = null,
                    purchaseGroupId = receiptGroupId
                )
            }
        )
    }

    suspend fun addManualPurchase(
        parts: List<ManualExpensePart>,
        merchantId: Long?,
        merchantName: String?,
        transactionDate: Long,
        purchaseGroupId: String?
    ) {
        require(parts.isNotEmpty() && parts.all { it.amountMinor > 0L })
        val createdAt = System.currentTimeMillis()
        dao.insertAll(parts.mapIndexed { index, part ->
            TransactionEntity(
                type = TransactionType.EXPENSE,
                amountMinor = part.amountMinor,
                categoryId = part.categoryId,
                merchant = merchantName?.takeIf(String::isNotBlank),
                note = part.note?.takeIf(String::isNotBlank),
                transactionDate = transactionDate,
                source = TransactionSource.MANUAL,
                createdAt = createdAt + index,
                receiptGroupId = null,
                merchantId = merchantId,
                purchaseGroupId = purchaseGroupId
            )
        })
    }
}

data class ReceiptExpenseItem(
    val name: String,
    val amountMinor: Long,
    val categoryId: Long
)

data class ManualExpensePart(val amountMinor: Long, val categoryId: Long, val note: String? = null)
