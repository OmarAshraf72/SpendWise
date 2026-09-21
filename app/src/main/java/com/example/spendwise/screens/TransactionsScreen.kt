package com.example.spendwise.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.TransactionType
import com.example.spendwise.data.TransactionWithCategory
import com.example.spendwise.data.formatEgp
import com.example.spendwise.viewmodel.TransactionsViewModel
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val transactionDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
private enum class TransactionFilter(val label: String) { ALL("All"), EXPENSES("Expenses"), INCOME("Income") }
private data class TransactionDisplay(val rows: List<TransactionWithCategory>) {
    val first get() = rows.first()
    val key get() = first.transaction.purchaseGroupId ?: "transaction-${first.transaction.id}"
}

@Composable
fun TransactionsScreen(
    onAddExpense: () -> Unit,
    viewModel: TransactionsViewModel = viewModel()
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(TransactionFilter.ALL) }
    val filteredTransactions = transactions.filter { item ->
        when (filter) {
            TransactionFilter.ALL -> true
            TransactionFilter.EXPENSES -> item.transaction.type == TransactionType.EXPENSE
            TransactionFilter.INCOME -> item.transaction.type == TransactionType.INCOME
        }
    }
    val displayTransactions = groupForDisplay(filteredTransactions)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Transactions",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransactionFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.label) }
                        )
                    }
                }
            }
            if (displayTransactions.isEmpty()) {
                item { EmptyTransactions(filter) }
            } else {
                items(displayTransactions, key = TransactionDisplay::key) { item ->
                    TransactionRow(item)
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAddExpense,
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            text = { Text("Add Expense") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }
}

@Composable
private fun EmptyTransactions(filter: TransactionFilter) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.AutoMirrored.Outlined.ReceiptLong, contentDescription = null)
        Text(
            text = when (filter) {
                TransactionFilter.ALL -> "No transactions yet"
                TransactionFilter.EXPENSES -> "No expense transactions"
                TransactionFilter.INCOME -> "No income transactions"
            },
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = if (filter == TransactionFilter.INCOME) {
                "Income added from Home will appear here."
            } else {
                "Add an expense to start tracking your spending."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TransactionRow(item: TransactionDisplay) {
    val transaction = item.first.transaction
    val isIncome = transaction.type == TransactionType.INCOME
    val isSplit = !isIncome && item.rows.size > 1 && transaction.purchaseGroupId != null
    val title = when {
        isIncome -> transaction.merchant ?: "Income"
        isSplit -> transaction.merchant ?: "Split purchase"
        else -> item.first.category?.name ?: "Uncategorized"
    }
    val totalMinor = item.rows.sumOf { it.transaction.amountMinor }
    val date = Instant.ofEpochMilli(transaction.transactionDate)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .format(transactionDateFormatter)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (isIncome) "Income" else "Expense",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = (if (isIncome) "+" else "-") + formatEgp(totalMinor),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isIncome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (!isIncome) {
                if (isSplit) {
                    Text("${item.rows.size} splits", style = MaterialTheme.typography.labelMedium)
                    item.rows.forEach { split ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(split.category?.name ?: "Uncategorized", style = MaterialTheme.typography.bodyMedium)
                            Text(formatEgp(split.transaction.amountMinor), style = MaterialTheme.typography.bodyMedium)
                        }
                        split.transaction.note?.let { note ->
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    transaction.merchant?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Text(
                text = date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun groupForDisplay(transactions: List<TransactionWithCategory>): List<TransactionDisplay> {
    val grouped = transactions.filter { it.transaction.purchaseGroupId != null }
        .groupBy { checkNotNull(it.transaction.purchaseGroupId) }
    val emitted = mutableSetOf<String>()
    return transactions.mapNotNull { row ->
        val groupId = row.transaction.purchaseGroupId ?: return@mapNotNull TransactionDisplay(listOf(row))
        if (!emitted.add(groupId)) null else TransactionDisplay(checkNotNull(grouped[groupId]))
    }
}
