package com.example.spendwise.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.CommitmentFrequency
import com.example.spendwise.data.CommitmentMonthTotal
import com.example.spendwise.data.CommitmentOccurrence
import com.example.spendwise.data.CommitmentOccurrenceStatus
import com.example.spendwise.data.CommitmentType
import com.example.spendwise.data.CommitmentViewMode
import com.example.spendwise.data.formatEgp
import com.example.spendwise.viewmodel.CommitmentsUiState
import com.example.spendwise.viewmodel.CommitmentsViewModel
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val commitmentDayFormatter = DateTimeFormatter.ofPattern("d MMM")
private val commitmentMonthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

@Composable
fun CommitmentsScreen(
    onAddCommitment: () -> Unit,
    onEditCommitment: (Long) -> Unit,
    viewModel: CommitmentsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showMonthPicker by remember { mutableStateOf(false) }
    var overdueExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Commitments", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Plan future financial pressure", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onAddCommitment) { Text("Add") }
            }
        }
        item { PeriodHeader(state.selectedMonth, viewModel::previousMonth, viewModel::nextMonth) { showMonthPicker = true } }
        item { ViewModeSelector(state.viewMode, viewModel::setViewMode) }
        item { TypeFilters(state.selectedType, viewModel::setTypeFilter) }
        item { PeriodSummaryCard(state) }

        if (state.overdue.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth().clickable { overdueExpanded = !overdueExpanded }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${state.overdue.size} overdue payment${if (state.overdue.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        Text(if (overdueExpanded) "Hide" else "Show")
                    }
                }
            }
            if (overdueExpanded) items(state.overdue, key = { "overdue-${it.commitment.commitment.id}-${it.dueDate}" }) {
                CommitmentCard(it, true, onEditCommitment, viewModel::setStatus)
            }
        }

        when (state.viewMode) {
            CommitmentViewMode.MONTH -> {
                item { SectionTitle("Due this month") }
                occurrenceItems(state.displayedOccurrences, "month", onAddCommitment, onEditCommitment, viewModel::setStatus)
            }
            CommitmentViewMode.NEXT_30_DAYS -> {
                item { SectionTitle("Due in the next 30 days") }
                occurrenceItems(state.displayedOccurrences, "30days", onAddCommitment, onEditCommitment, viewModel::setStatus)
            }
            CommitmentViewMode.ALL_UPCOMING -> {
                item { SectionTitle("All upcoming") }
                if (state.upcomingByMonth.isEmpty()) item { EmptyCommitments(onAddCommitment) }
                else state.upcomingByMonth.forEach { (month, occurrences) ->
                    item("heading-$month") { Text(month.format(commitmentMonthFormatter), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(occurrences, key = { "all-${it.commitment.commitment.id}-${it.dueDate}" }) {
                        CommitmentCard(it, false, onEditCommitment, viewModel::setStatus)
                    }
                }
            }
        }

        item { SectionTitle("12-month forecast") }
        item { ForecastCard(state.forecast) }
        item { Spacer(Modifier.padding(8.dp)) }
    }

    if (showMonthPicker) SpendWiseMonthPickerDialog(
        initialMonth = state.selectedMonth,
        onSelected = { viewModel.selectMonth(it); viewModel.setViewMode(CommitmentViewMode.MONTH); showMonthPicker = false },
        onDismiss = { showMonthPicker = false }
    )
}

private fun LazyListScope.occurrenceItems(
    occurrences: List<CommitmentOccurrence>, keyPrefix: String, onAdd: () -> Unit, onEdit: (Long) -> Unit,
    setStatus: (CommitmentOccurrence, CommitmentOccurrenceStatus, Boolean) -> Unit
) {
    if (occurrences.isEmpty()) item { EmptyCommitments(onAdd) }
    else items(occurrences, key = { "$keyPrefix-${it.commitment.commitment.id}-${it.dueDate}" }) {
        CommitmentCard(it, false, onEdit, setStatus)
    }
}

@Composable
private fun PeriodHeader(month: YearMonth, previous: () -> Unit, next: () -> Unit, selectDirectly: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = previous) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
        Text(month.format(commitmentMonthFormatter), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = selectDirectly).padding(12.dp))
        TextButton(onClick = next) { Text("›", style = MaterialTheme.typography.headlineMedium) }
    }
}

@Composable
private fun ViewModeSelector(selected: CommitmentViewMode, onSelected: (CommitmentViewMode) -> Unit) {
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(CommitmentViewMode.MONTH to "Month", CommitmentViewMode.NEXT_30_DAYS to "Next 30 days", CommitmentViewMode.ALL_UPCOMING to "All upcoming").forEach { (mode, label) ->
            item(mode.name) { FilterChip(selected = mode == selected, onClick = { onSelected(mode) }, label = { Text(label) }) }
        }
    }
}

@Composable
private fun TypeFilters(selected: CommitmentType?, onSelected: (CommitmentType?) -> Unit) {
    val filters = listOf(null, CommitmentType.INSTALLMENT, CommitmentType.SUBSCRIPTION, CommitmentType.UTILITIES, CommitmentType.INSURANCE, CommitmentType.DEBT_PAYMENT)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(filters) { type -> FilterChip(selected == type, { onSelected(type) }, label = { Text(type?.displayName() ?: "All") }) }
    }
}

@Composable
private fun PeriodSummaryCard(state: CommitmentsUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Summary", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            AmountLine("Committed", state.periodSummary.committedMinor)
            AmountLine("Paid", state.periodSummary.paidMinor)
            AmountLine("Remaining", state.periodSummary.remainingMinor, true)
        }
    }
}

@Composable private fun AmountLine(label: String, amount: Long, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal)
        Text(formatEgp(amount), fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

@Composable
private fun EmptyCommitments(onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No commitments in this period", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAdd) { Text("Add Commitment") }
        }
    }
}

@Composable
private fun CommitmentCard(
    occurrence: CommitmentOccurrence, overdue: Boolean, onEdit: (Long) -> Unit,
    setStatus: (CommitmentOccurrence, CommitmentOccurrenceStatus, Boolean) -> Unit
) {
    val definition = occurrence.commitment.commitment
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(definition.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(formatEgp(definition.amountMinor), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text("${occurrence.dueDate.format(commitmentDayFormatter)} · ${definition.frequency.displayName()}", color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(definition.type.displayName(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val statusLabel = when {
                overdue -> "Overdue"
                occurrence.status == CommitmentOccurrenceStatus.PAID -> "Paid"
                occurrence.status == CommitmentOccurrenceStatus.SKIPPED -> "Skipped"
                else -> null
            }
            statusLabel?.let { Text(it, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (occurrence.status == CommitmentOccurrenceStatus.UNPAID) {
                    TextButton(onClick = { setStatus(occurrence, CommitmentOccurrenceStatus.PAID, false) }) { Text("Paid") }
                    TextButton(onClick = { setStatus(occurrence, CommitmentOccurrenceStatus.PAID, true) }) { Text("Paid + expense") }
                    TextButton(onClick = { setStatus(occurrence, CommitmentOccurrenceStatus.SKIPPED, false) }) { Text("Skip") }
                }
                TextButton(onClick = { onEdit(definition.id) }) { Text("Edit") }
            }
        }
    }
}

@Composable
private fun ForecastCard(months: List<CommitmentMonthTotal>) {
    val maximum = months.maxOfOrNull { it.amountMinor }?.coerceAtLeast(1) ?: 1
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            months.forEach { total ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(total.month.format(DateTimeFormatter.ofPattern("MMM yyyy")))
                        Text(formatEgp(total.amountMinor), fontWeight = FontWeight.Medium)
                    }
                    LinearProgressIndicator({ total.amountMinor.toFloat() / maximum.toFloat() }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

fun CommitmentType.displayName(): String = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
fun CommitmentFrequency.displayName(): String = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
