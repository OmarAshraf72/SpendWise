package com.example.spendwise.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.*
import com.example.spendwise.viewmodel.DebtDetailUiState
import com.example.spendwise.viewmodel.DebtDetailViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val debtDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtDetailScreen(onBack: () -> Unit, viewModel: DebtDetailViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showPayment by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(state.commitment?.commitment?.title ?: "Debt details") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val profile = state.profile
            val balance = state.balance
            if (profile == null || balance == null) Text("Debt profile is unavailable.") else {
                BalanceCard(balance)
                DetailCard(state)
                Button(onClick = { showPayment = true }, modifier = Modifier.fillMaxWidth()) { Text("Record payment") }
                FinancingCard(state)
                LateRuleCard(state)
                PaymentHistory(state)
                TextButton(onClick = { viewModel.archive(onBack) }, modifier = Modifier.fillMaxWidth()) { Text("Archive debt") }
            }
        }
    }
    if (showPayment) PaymentDialog(state, onDismiss = { showPayment = false }) { input, error ->
        viewModel.recordPayment(input, { showPayment = false }, error)
    }
}

@Composable private fun BalanceCard(balance: DebtBalance) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DebtAmount("Original principal", balance.originalPrincipalMinor)
            DebtAmount("Principal paid", balance.principalPaidMinor)
            DebtAmount("Remaining principal", balance.outstandingPrincipalMinor, true)
            if (balance.totalPaidMinor != balance.principalPaidMinor) DebtAmount("Total cash paid", balance.totalPaidMinor)
        }
    }
}

@Composable private fun DebtAmount(label: String, amount: Long, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        Text(formatEgp(amount), fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun DetailCard(state: DebtDetailUiState) {
    val profile = state.profile ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Repayment plan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            profile.creditorName?.let { Text("Creditor: $it") }
            Text("Started: ${LocalDate.ofEpochDay(profile.startDateEpochDay).format(debtDateFormatter)}")
            Text("Style: ${profile.repaymentMode.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)}")
            profile.expectedEndDateEpochDay?.let { Text("Expected finish: ${LocalDate.ofEpochDay(it).format(debtDateFormatter)}") }
            state.nextOccurrence?.let {
                Text("Next payment: ${formatEgp((it.amountMinor - state.nextOccurrencePaidMinor).coerceAtLeast(0))} · ${it.dueDate.format(debtDateFormatter)}")
                if (state.nextOccurrencePaidMinor in 1 until it.amountMinor) Text("Partially paid", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable private fun FinancingCard(state: DebtDetailUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Financing terms", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val terms = state.financing
            if (terms == null) Text("No financing cost recorded.") else {
                Text(terms.financingType.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase))
                if (terms.financingType == FinancingType.FIXED_TOTAL) {
                    terms.totalRepayableMinor?.let { Text("Total repayable: ${formatEgp(it)}") }
                    val cost = state.profile?.let { DebtCalculator.financingCostMinor(it, terms) }
                    cost?.let { Text("Contract financing cost: ${formatEgp(it)}") }
                } else {
                    terms.rateBasisPoints?.let { Text("Rate entered: ${formatBasisPoints(it)}") }
                    Text("Stored contract metadata only; no balance is calculated from incomplete rate terms.")
                }
            }
        }
    }
}

@Composable private fun LateRuleCard(state: DebtDetailUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Late-payment rules", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val rule = state.lateRule
            if (rule == null) Text("No late-payment rule recorded.") else {
                Text("${rule.gracePeriodDays} complete grace days")
                Text(rule.chargeType.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase))
                state.estimatedLateCharge?.takeIf { it.estimatedChargeMinor > 0 }?.let {
                    Text("Estimated late charge: ${formatEgp(it.estimatedChargeMinor)}", fontWeight = FontWeight.SemiBold)
                    Text("Estimate based on your entered contract terms.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable private fun PaymentHistory(state: DebtDetailUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Payment history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (state.payments.isEmpty()) Text("No payments recorded yet.")
        state.payments.forEach { payment ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(LocalDate.ofEpochDay(payment.paymentDateEpochDay).format(debtDateFormatter))
                        Text(formatEgp(payment.totalPaidMinor), fontWeight = FontWeight.Bold)
                    }
                    Text("Principal: ${formatEgp(payment.principalPaidMinor)}")
                    if (payment.financingCostPaidMinor > 0) Text("Financing cost: ${formatEgp(payment.financingCostPaidMinor)}")
                    if (payment.lateChargePaidMinor > 0) Text("Late charge: ${formatEgp(payment.lateChargePaidMinor)}")
                    payment.note?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun PaymentDialog(
    state: DebtDetailUiState,
    onDismiss: () -> Unit,
    save: (DebtPaymentInput, (String) -> Unit) -> Unit
) {
    val profile = state.profile ?: return
    var amount by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var principal by rememberSaveable { mutableStateOf("") }
    var financing by rememberSaveable { mutableStateOf("") }
    var late by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var createExpense by rememberSaveable { mutableStateOf(true) }
    var paymentDateEpoch by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val key = rememberSaveable { UUID.randomUUID().toString() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record payment") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(amount, { amount = it; error = null }, label = { Text("Amount paid") }, suffix = { Text("EGP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                TextButton(onClick = { showDatePicker = true }) { Text("Payment date: ${LocalDate.ofEpochDay(paymentDateEpoch).format(debtDateFormatter)}") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Advanced breakdown", modifier = Modifier.weight(1f)); Switch(advanced, { advanced = it })
                }
                if (advanced) {
                    OutlinedTextField(principal, { principal = it }, label = { Text("Principal") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    OutlinedTextField(financing, { financing = it }, label = { Text("Financing cost") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    OutlinedTextField(late, { late = it }, label = { Text("Late charge") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                } else if (state.financing != null) {
                    Text("This debt has financing terms. Use the breakdown so SpendWise does not guess payment allocation.", color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(createExpense, { createExpense = it }); Text("Also add expense transaction")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val total = parseEgpToMinor(amount)
                val p = if (advanced) parseEgpToMinor(principal) ?: 0 else if (state.financing == null) total ?: 0 else 0
                val f = if (advanced) parseEgpToMinor(financing) ?: 0 else 0
                val l = if (advanced) parseEgpToMinor(late) ?: 0 else 0
                if (total == null || !validateDebtPayment(total, p, f, l) || (!advanced && state.financing != null)) {
                    error = "Enter a valid payment and matching breakdown."
                } else save(
                    DebtPaymentInput(profile.id, state.nextOccurrence?.dueDate.takeIf { profile.repaymentMode == RepaymentMode.FIXED_INSTALLMENTS },
                        LocalDate.ofEpochDay(paymentDateEpoch), total, p, f, l, note, createExpense, key)
                ) { error = it }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (showDatePicker) SpendWiseDatePickerDialog(
        LocalDate.ofEpochDay(paymentDateEpoch),
        onDateSelected = { paymentDateEpoch = it.toEpochDay(); showDatePicker = false },
        onDismiss = { showDatePicker = false },
        showTodayAction = true
    )
}

private fun formatBasisPoints(value: Long): String {
    val whole = value / 100
    val fraction = (value % 100).toString().padStart(2, '0')
    return if (fraction == "00") "$whole%" else "$whole.$fraction%"
}
