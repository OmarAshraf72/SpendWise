package com.example.spendwise.data

/** The profile determines whether a debt has a schedule. Open-ended placeholder
 * commitment fields never describe a payment or contribute to forecasts. */
fun canonicalDebtDefinition(input: DebtDefinitionInput): DebtDefinitionInput {
    val commitment = input.commitment
    return when (input.repaymentMode) {
        RepaymentMode.OPEN_ENDED -> input.copy(
            commitment = commitment.copy(
                amountMinor = 0,
                frequency = CommitmentFrequency.ONE_TIME,
                startDateEpochDay = input.debtStartDate.toEpochDay(),
                nextDueDateEpochDay = input.debtStartDate.toEpochDay(),
                endDateEpochDay = null
            )
        )
        RepaymentMode.FIXED_INSTALLMENTS -> {
            require(commitment.amountMinor > 0) { "Installment amount is required" }
            require(commitment.frequency != CommitmentFrequency.CUSTOM) { "Select a supported frequency" }
            input
        }
    }
}
