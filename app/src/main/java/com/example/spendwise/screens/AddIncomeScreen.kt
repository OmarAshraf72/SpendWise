package com.example.spendwise.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.parseEgpToMinor
import com.example.spendwise.viewmodel.TransactionsViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val incomeDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddIncomeScreen(
    onSaved: () -> Unit,
    viewModel: TransactionsViewModel = viewModel()
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var selectedDate by rememberSaveable { mutableStateOf(incomeTodayStartMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showValidation by rememberSaveable { mutableStateOf(false) }

    val parsedAmountMinor = parseEgpToMinor(amount)
    val amountInvalid = showValidation && (parsedAmountMinor == null || parsedAmountMinor <= 0L)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Add Income",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount (EGP)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = amountInvalid,
            supportingText = if (amountInvalid) {
                { Text("Enter an amount greater than zero with up to 2 decimal places") }
            } else null,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = incomeFormatDate(selectedDate),
            onValueChange = {},
            readOnly = true,
            label = { Text("Date") },
            modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }
        )
        TextButton(onClick = { showDatePicker = true }) { Text("Change date") }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Source / name (optional)") },
            placeholder = { Text("Salary, Freelance, Bonus, Refund, Other") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note (optional)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                showValidation = true
                val validAmountMinor = parseEgpToMinor(amount)
                if (validAmountMinor != null && validAmountMinor > 0L) {
                    viewModel.addManualIncome(
                        amountMinor = validAmountMinor,
                        name = name.trim(),
                        note = note.trim(),
                        transactionDate = selectedDate,
                        onSaved = onSaved
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Income")
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDate)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { selectedDate = it }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

private fun incomeTodayStartMillis(): Long = LocalDate.now()
    .atStartOfDay(ZoneOffset.UTC)
    .toInstant()
    .toEpochMilli()

private fun incomeFormatDate(value: Long): String = Instant.ofEpochMilli(value)
    .atZone(ZoneOffset.UTC)
    .toLocalDate()
    .format(incomeDateFormatter)
