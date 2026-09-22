package com.example.spendwise.data

import androidx.room.withTransaction
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.combine

data class DebtData(
    val profiles: List<DebtProfileEntity> = emptyList(),
    val payments: List<DebtPaymentEntity> = emptyList(),
    val financingTerms: List<FinancingTermsEntity> = emptyList(),
    val lateRules: List<LatePaymentRuleEntity> = emptyList()
)

data class DebtDefinitionInput(
    val commitment: FinancialCommitmentEntity,
    val creditorName: String?,
    val originalPrincipalMinor: Long,
    val debtStartDate: LocalDate,
    val expectedEndDate: LocalDate?,
    val repaymentMode: RepaymentMode,
    val debtNotes: String?,
    val financingTerms: FinancingTermsEntity?,
    val lateRule: LatePaymentRuleEntity?
)

data class DebtPaymentInput(
    val profileId: Long,
    val occurrenceDueDate: LocalDate?,
    val paymentDate: LocalDate,
    val totalPaidMinor: Long,
    val principalPaidMinor: Long,
    val financingCostPaidMinor: Long,
    val lateChargePaidMinor: Long,
    val note: String?,
    val createExpense: Boolean,
    val idempotencyKey: String
)

class DebtRepository(private val database: SpendWiseDatabase) {
    private val dao = database.debtDao()
    private val commitmentDao = database.commitmentDao()

    val data = combine(
        combine(dao.observeActiveProfiles(), dao.observePayments()) { profiles, payments -> profiles to payments },
        combine(dao.observeFinancingTerms(), dao.observeLateRules()) { terms, rules -> terms to rules }
    ) { first, second -> DebtData(first.first, first.second, second.first, second.second) }

    suspend fun saveDebt(input: DebtDefinitionInput): Long = database.withTransaction {
        val definition = canonicalDebtDefinition(input)
        require(definition.commitment.title.isNotBlank() && definition.originalPrincipalMinor > 0)
        require(definition.financingTerms?.takeIf { it.financingType == FinancingType.FIXED_TOTAL }
            ?.totalRepayableMinor?.let { it >= definition.originalPrincipalMinor } != false)
        val commitmentId = if (definition.commitment.id == 0L) commitmentDao.insert(definition.commitment) else {
            commitmentDao.update(definition.commitment)
            definition.commitment.id
        }
        val existing = dao.getProfileForCommitment(commitmentId)
        val now = definition.commitment.updatedAt
        val profile = DebtProfileEntity(
            id = existing?.id ?: 0,
            commitmentId = commitmentId,
            creditorName = definition.creditorName?.trim()?.takeIf(String::isNotBlank),
            originalPrincipalMinor = definition.originalPrincipalMinor,
            startDateEpochDay = definition.debtStartDate.toEpochDay(),
            expectedEndDateEpochDay = definition.expectedEndDate?.toEpochDay(),
            repaymentMode = definition.repaymentMode,
            notes = definition.debtNotes?.trim()?.takeIf(String::isNotBlank),
            isArchived = existing?.isArchived ?: false,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        val profileId = if (existing == null) dao.insertProfile(profile) else {
            dao.updateProfile(profile)
            existing.id
        }
        val existingTerms = dao.getFinancing(profileId)
        if (definition.financingTerms == null) dao.deleteFinancing(profileId) else dao.putFinancing(
            definition.financingTerms.copy(id = existingTerms?.id ?: 0, debtProfileId = profileId, createdAt = existingTerms?.createdAt ?: now, updatedAt = now)
        )
        val existingRule = dao.getLateRule(profileId)
        if (definition.lateRule == null) dao.deleteLateRule(profileId) else dao.putLateRule(
            definition.lateRule.copy(id = existingRule?.id ?: 0, debtProfileId = profileId, createdAt = existingRule?.createdAt ?: now, updatedAt = now)
        )
        profileId
    }

    suspend fun getProfile(id: Long) = dao.getProfile(id)
    suspend fun getProfileForCommitment(commitmentId: Long) = dao.getProfileForCommitment(commitmentId)
    suspend fun getPayments(id: Long) = dao.getPayments(id)
    suspend fun getFinancing(id: Long) = dao.getFinancing(id)
    suspend fun getLateRule(id: Long) = dao.getLateRule(id)

    suspend fun recordPayment(input: DebtPaymentInput): Long = database.withTransaction {
        require(validateDebtPayment(input.totalPaidMinor, input.principalPaidMinor, input.financingCostPaidMinor, input.lateChargePaidMinor))
        val writePlan = planDebtPaymentWrite(dao.getPaymentByKey(input.idempotencyKey), input.createExpense)
        writePlan.existingPaymentId?.let { return@withTransaction it }
        val profile = dao.getProfile(input.profileId) ?: error("Debt profile not found")
        val commitment = commitmentDao.getCommitmentWithMerchant(profile.commitmentId) ?: error("Commitment not found")
        val now = System.currentTimeMillis()
        val transactionId = if (writePlan.createTransaction) database.transactionDao().insert(
            TransactionEntity(
                type = TransactionType.EXPENSE,
                amountMinor = input.totalPaidMinor,
                categoryId = null,
                merchant = commitment.merchant?.displayName ?: profile.creditorName,
                note = "Debt payment: ${commitment.commitment.title}",
                transactionDate = input.paymentDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                source = TransactionSource.MANUAL,
                createdAt = now,
                receiptGroupId = null,
                merchantId = commitment.commitment.merchantId,
                purchaseGroupId = null
            )
        ) else null
        val paymentId = dao.insertPayment(
            DebtPaymentEntity(
                debtProfileId = input.profileId,
                occurrenceDueDateEpochDay = input.occurrenceDueDate?.toEpochDay(),
                paymentDateEpochDay = input.paymentDate.toEpochDay(),
                totalPaidMinor = input.totalPaidMinor,
                principalPaidMinor = input.principalPaidMinor,
                financingCostPaidMinor = input.financingCostPaidMinor,
                lateChargePaidMinor = input.lateChargePaidMinor,
                linkedTransactionId = transactionId,
                note = input.note?.trim()?.takeIf(String::isNotBlank),
                idempotencyKey = input.idempotencyKey,
                createdAt = now
            )
        )
        input.occurrenceDueDate?.let { due ->
            val dueEpoch = due.toEpochDay()
            val allocated = dao.allocatedForOccurrence(input.profileId, dueEpoch)
            val existing = commitmentDao.getOverride(profile.commitmentId, dueEpoch)
            if (existing?.status != CommitmentOccurrenceStatus.SKIPPED) {
                commitmentDao.putOverride(
                    CommitmentOccurrenceOverrideEntity(
                        id = existing?.id ?: 0,
                        commitmentId = profile.commitmentId,
                        dueDateEpochDay = dueEpoch,
                        status = if (allocated >= commitment.commitment.amountMinor) CommitmentOccurrenceStatus.PAID else CommitmentOccurrenceStatus.UNPAID,
                        paidTransactionId = existing?.paidTransactionId ?: transactionId,
                        paidAt = if (allocated >= commitment.commitment.amountMinor) now else null
                    )
                )
            }
        }
        paymentId
    }

    suspend fun archive(profileId: Long) = database.withTransaction {
        val profile = dao.getProfile(profileId) ?: return@withTransaction
        val commitment = commitmentDao.getCommitment(profile.commitmentId)
        val now = System.currentTimeMillis()
        dao.archive(profileId, now)
        commitment?.let { commitmentDao.update(it.copy(isActive = false, updatedAt = now)) }
    }
}
