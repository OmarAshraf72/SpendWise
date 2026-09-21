package com.example.spendwise.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.combine

data class CommitmentData(
    val commitments: List<CommitmentWithMerchant>,
    val overrides: List<CommitmentOccurrenceOverrideEntity>
)

class CommitmentRepository(private val database: SpendWiseDatabase) {
    private val dao = database.commitmentDao()
    val data = combine(dao.observeCommitments(), dao.observeOverrides(), ::CommitmentData)

    suspend fun save(commitment: FinancialCommitmentEntity): Long {
        require(commitment.title.isNotBlank() && commitment.amountMinor > 0L)
        return if (commitment.id == 0L) dao.insert(commitment) else {
            dao.update(commitment)
            commitment.id
        }
    }

    suspend fun get(id: Long): FinancialCommitmentEntity? = dao.getCommitment(id)

    suspend fun setOccurrenceStatus(
        commitmentId: Long,
        dueDateEpochDay: Long,
        status: CommitmentOccurrenceStatus,
        createExpense: Boolean,
        now: Long
    ) = database.withTransaction {
        val existing = dao.getOverride(commitmentId, dueDateEpochDay)
        if (shouldCreateLinkedCommitmentExpense(status, createExpense, existing)) {
            val commitment = dao.getCommitmentWithMerchant(commitmentId) ?: return@withTransaction
            val definition = commitment.commitment
            val transactionId = database.transactionDao().insert(
                TransactionEntity(
                    type = TransactionType.EXPENSE,
                    amountMinor = definition.amountMinor,
                    categoryId = null,
                    merchant = commitment.merchant?.displayName,
                    note = "Commitment: ${definition.title}",
                    transactionDate = java.time.LocalDate.ofEpochDay(dueDateEpochDay)
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
                    source = TransactionSource.MANUAL,
                    createdAt = now,
                    receiptGroupId = null,
                    merchantId = definition.merchantId,
                    purchaseGroupId = null
                )
            )
            dao.putOverride(
                CommitmentOccurrenceOverrideEntity(
                    id = existing?.id ?: 0,
                    commitmentId = commitmentId,
                    dueDateEpochDay = dueDateEpochDay,
                    status = status,
                    paidTransactionId = transactionId,
                    paidAt = now
                )
            )
        } else {
            dao.putOverride(
                CommitmentOccurrenceOverrideEntity(
                    id = existing?.id ?: 0,
                    commitmentId = commitmentId,
                    dueDateEpochDay = dueDateEpochDay,
                    status = status,
                    paidTransactionId = existing?.paidTransactionId,
                    paidAt = if (status == CommitmentOccurrenceStatus.PAID) existing?.paidAt ?: now else null
                )
            )
        }
    }
}

internal fun shouldCreateLinkedCommitmentExpense(
    status: CommitmentOccurrenceStatus,
    createExpense: Boolean,
    existing: CommitmentOccurrenceOverrideEntity?
): Boolean = status == CommitmentOccurrenceStatus.PAID && createExpense && existing?.paidTransactionId == null
