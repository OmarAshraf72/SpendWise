package com.example.spendwise.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.SpendingPeriod
import com.example.spendwise.data.formatEgp
import com.example.spendwise.data.itemTypeLabel
import com.example.spendwise.viewmodel.CategoryDetailViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val categoryDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun CategoryDetailScreen(
    onBack: () -> Unit,
    onItem: (Long) -> Unit,
    onTransaction: (Long) -> Unit,
    viewModel: CategoryDetailViewModel = viewModel()
) {
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    val customTypes by viewModel.customTypes.collectAsStateWithLifecycle()
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()

    val details = entry
    if (details == null) {
        Text("Category not found", Modifier.padding(20.dp))
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("Back to Categories") }
        Text(details.category.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(SpendingPeriod.entries) { period ->
                FilterChip(
                    selected = period == selectedPeriod,
                    onClick = { viewModel.setPeriod(period) },
                    label = { Text(period.label, maxLines = 1) }
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Spending", style = MaterialTheme.typography.titleMedium)
                Text(
                    formatEgp(details.monthSpentMinor),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text("Recent expenses", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (details.recentExpenses.isEmpty()) {
            Text("No expenses in this category during this period", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        details.recentExpenses.forEach { row ->
            Card(Modifier.fillMaxWidth().clickable { onTransaction(row.transaction.id) }) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(row.transaction.merchant ?: row.transaction.note ?: "Expense")
                        Text(
                            Instant.ofEpochMilli(row.transaction.transactionDate)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .format(categoryDateFormat),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(formatEgp(row.transaction.amountMinor), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text("My Items", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (details.ownedItems.isEmpty()) Text("No owned items in this category yet")
        details.ownedItems.forEach { item ->
            Card(Modifier.fillMaxWidth().clickable { onItem(item.id) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold)
                    Text(itemTypeLabel(item.type, item.customTypeId, customTypes))
                    item.purchaseDateEpochDay?.let { date ->
                        Text(
                            "Bought ${LocalDate.ofEpochDay(date).format(categoryDateFormat)}" +
                                    (item.purchasePriceMinor?.let { " · ${formatEgp(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
