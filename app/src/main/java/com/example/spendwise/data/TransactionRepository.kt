package com.example.spendwise.data

class TransactionRepository(private val dao: TransactionDao) {
    val transactions = dao.observeTransactions()

    fun observeExpensesBetween(startInclusive: Long, endExclusive: Long) =
        dao.observeExpensesBetween(startInclusive, endExclusive)

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
                createdAt = System.currentTimeMillis()
            )
        )
    }
}
