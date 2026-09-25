package com.example.spendwise.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    onAddToItems: (Long) -> Unit = {},
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
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ScreenHeader(title = "Transactions")
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
                    TransactionRow(item, onAddToItems)
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onAddExpense,
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            text = { Text("Add Expense", maxLines = 1) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }
}

@Composable
private fun EmptyTransactions(filter: TransactionFilter) {
    val (title, subtitle) = when (filter) {
        TransactionFilter.ALL -> "No transactions yet" to "Add an expense to start tracking your spending."
        TransactionFilter.EXPENSES -> "No expense transactions" to "Add an expense to start tracking your spending."
        TransactionFilter.INCOME -> "No income transactions" to "Income added from Home will appear here."
    }
    EmptyStateContainer(
        icon = Icons.AutoMirrored.Outlined.ReceiptLong,
        title = title,
        subtitle = subtitle
    )
}

@Composable
private fun TransactionRow(item: TransactionDisplay, onAddToItems: (Long) -> Unit) {
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isIncome) "Income" else "Expense",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = (if (isIncome) "+" else "-") + formatEgp(totalMinor),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isIncome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (!isIncome) {
                if (isSplit) {
                    Text("${item.rows.size} splits", style = MaterialTheme.typography.labelMedium)
                    item.rows.forEach { split ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                split.category?.name ?: "Uncategorized",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(formatEgp(split.transaction.amountMinor), style = MaterialTheme.typography.bodyMedium)
                        }
                        split.transaction.note?.let { note ->
                            Text(
                                note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    transaction.merchant?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Text(
                text = date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!isIncome && transaction.purchaseGroupId == null) {
                TextButton(
                    onClick = { onAddToItems(transaction.id) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Add to My Items")
                }
            }
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
