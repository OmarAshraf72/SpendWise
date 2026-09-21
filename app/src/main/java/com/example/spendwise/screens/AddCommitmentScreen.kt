package com.example.spendwise.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.CommitmentFrequency
import com.example.spendwise.data.CommitmentType
import com.example.spendwise.data.CommitmentRecurrence
import com.example.spendwise.data.MerchantRanker
import com.example.spendwise.data.shouldOfferNewMerchant
import com.example.spendwise.data.formatEgp
import com.example.spendwise.data.parseEgpToMinor
import com.example.spendwise.viewmodel.CommitmentsViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val commitmentFormDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCommitmentScreen(
    commitmentId: Long? = null,
    onSaved: () -> Unit,
    viewModel: CommitmentsViewModel = viewModel()
) {
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var id by rememberSaveable { mutableStateOf(0L) }
    var originalCreatedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(CommitmentType.INSTALLMENT) }
    var frequency by rememberSaveable { mutableStateOf(CommitmentFrequency.MONTHLY) }
    var dueDateEpoch by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(1).toEpochDay()) }
    var hasEndDate by rememberSaveable { mutableStateOf(false) }
    var endDateEpoch by rememberSaveable { mutableStateOf(LocalDate.now().plusYears(1).toEpochDay()) }
    var merchantId by rememberSaveable { mutableStateOf<Long?>(null) }
    var merchantQuery by rememberSaveable { mutableStateOf("") }
    var merchantFocused by remember { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }
    var showValidation by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var typeExpanded by remember { mutableStateOf(false) }
    var frequencyExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(commitmentId) {
        commitmentId?.let { key ->
            viewModel.getCommitment(key)?.let { existing ->
                id = existing.id; originalCreatedAt = existing.createdAt; title = existing.title
                amount = minorToInput(existing.amountMinor); type = existing.type; frequency = existing.frequency
                dueDateEpoch = existing.nextDueDateEpochDay; hasEndDate = existing.endDateEpochDay != null
                existing.endDateEpochDay?.let { endDateEpoch = it }; merchantId = existing.merchantId
                merchantQuery = merchants.firstOrNull { it.id == existing.merchantId }?.displayName.orEmpty()
                notes = existing.notes.orEmpty()
            }
        }
    }
    LaunchedEffect(merchantId, merchants) {
        if (merchantId != null && merchantQuery.isBlank()) {
            merchantQuery = merchants.firstOrNull { it.id == merchantId }?.displayName.orEmpty()
        }
    }
    val parsedAmount = parseEgpToMinor(amount)
    val effectiveHasEndDate = hasEndDate && frequency != CommitmentFrequency.ONE_TIME
    val invalidEnd = effectiveHasEndDate && endDateEpoch < dueDateEpoch
    val previewDates = CommitmentRecurrence.preview(
        frequency, LocalDate.ofEpochDay(dueDateEpoch),
        endDateEpoch.takeIf { effectiveHasEndDate }?.let(LocalDate::ofEpochDay)
    )
    val merchantSuggestions = remember(merchantQuery, merchants) { MerchantRanker.rank(merchantQuery, merchants) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(if (commitmentId == null) "Add Commitment" else "Edit Commitment", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(title, { title = it }, label = { Text("Name") }, placeholder = { Text("e.g. Car installment or Netflix") }, singleLine = true, isError = showValidation && title.isBlank(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(amount, { amount = it }, label = { Text("Amount (EGP)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, isError = showValidation && (parsedAmount == null || parsedAmount <= 0), modifier = Modifier.fillMaxWidth())
        EnumDropdown("Type", type.displayName(), CommitmentType.entries.toList(), typeExpanded, { typeExpanded = it }, { type = it; typeExpanded = false }) { it.displayName() }
        EnumDropdown("Frequency", frequency.displayName(), CommitmentFrequency.entries.filter { it != CommitmentFrequency.CUSTOM }, frequencyExpanded, { frequencyExpanded = it }, { frequency = it; frequencyExpanded = false }) { it.displayName() }
        DateField(if (frequency == CommitmentFrequency.ONE_TIME) "Due date" else "First payment date", LocalDate.ofEpochDay(dueDateEpoch)) { picker = "due" }
        if (frequency != CommitmentFrequency.ONE_TIME) {
            Text("Ends", style = MaterialTheme.typography.labelLarge)
            if (hasEndDate) {
                DateField("End date", LocalDate.ofEpochDay(endDateEpoch), invalidEnd) { picker = "end" }
                TextButton(onClick = { hasEndDate = false }) { Text("Clear end date") }
            } else {
                OutlinedButton(onClick = {
                    hasEndDate = true
                    if (endDateEpoch < dueDateEpoch) endDateEpoch = LocalDate.ofEpochDay(dueDateEpoch).plusYears(1).toEpochDay()
                    picker = "end"
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("No end date", modifier = Modifier.weight(1f)); Icon(Icons.Outlined.DateRange, contentDescription = "Choose end date")
                }
            }
        }
        OutlinedTextField(
            value = merchantQuery,
            onValueChange = { merchantQuery = it; merchantId = null },
            label = { Text("Merchant (optional)") }, placeholder = { Text("Search merchant") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { merchantFocused = it.isFocused }
        )
        if (merchantFocused) {
            InlineSuggestionList(
                merchantSuggestions.take(5).map { InlineSuggestion(it.merchant.id, it.merchant, it.merchant.displayName,
                    it.merchant.usageCount.takeIf { count -> count > 0 }?.let { count -> "Used $count times" }) },
                onSelected = { selected ->
                    merchantId = selected.id; merchantQuery = selected.displayName; merchantFocused = false; focusManager.clearFocus()
                },
                addLabel = if (shouldOfferNewMerchant(merchantQuery, merchantSuggestions)) "Add “${merchantQuery.trim()}”" else null,
                onAdd = if (shouldOfferNewMerchant(merchantQuery, merchantSuggestions)) {{
                    viewModel.createMerchant(merchantQuery) { created ->
                        merchantId = created?.id; created?.let { merchantQuery = it.displayName }
                        merchantFocused = false; focusManager.clearFocus()
                    }
                }} else null
            )
        }
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (frequency == CommitmentFrequency.ONE_TIME) "One-time payment" else "Upcoming payments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            previewDates.forEach { Text(it.format(commitmentFormDateFormatter), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (previewDates.isEmpty() && invalidEnd) Text("Choose an end date on or after the first payment.", color = MaterialTheme.colorScheme.error)
        }
        parsedAmount?.takeIf { it > 0 }?.let { Text("${formatEgp(it)} · ${frequency.displayName()}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Button(onClick = {
            showValidation = true
            if (title.isNotBlank() && parsedAmount != null && parsedAmount > 0 && !invalidEnd) {
                viewModel.saveCommitment(id, title, parsedAmount, type, frequency, LocalDate.ofEpochDay(dueDateEpoch), endDateEpoch.takeIf { effectiveHasEndDate }?.let(LocalDate::ofEpochDay), merchantId, notes, originalCreatedAt, onSaved)
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save Commitment") }
    }

    picker?.let { target ->
        val initial = if (target == "due") dueDateEpoch else endDateEpoch
        SpendWiseDatePickerDialog(
            initialDate = LocalDate.ofEpochDay(initial),
            onDateSelected = { date ->
                if (target == "due") dueDateEpoch = date.toEpochDay() else endDateEpoch = date.toEpochDay()
                picker = null
            },
            onDismiss = { picker = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(label: String, value: String, values: List<T>, expanded: Boolean, setExpanded: (Boolean) -> Unit, select: (T) -> Unit, name: (T) -> String) {
    ExposedDropdownMenuBox(expanded, setExpanded) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded, { setExpanded(false) }) { values.forEach { item -> DropdownMenuItem(text = { Text(name(item)) }, onClick = { select(item) }) } }
    }
}

@Composable private fun DateField(label: String, date: LocalDate, isError: Boolean = false, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(date.format(commitmentFormDateFormatter), modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.DateRange, contentDescription = "Choose $label")
        }
        if (isError) Text("End date must be on or after the first payment date", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private fun minorToInput(value: Long): String = if (value % 100L == 0L) (value / 100L).toString() else "${value / 100L}.${(value % 100L).toString().padStart(2, '0')}"
