package com.example.spendwise.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.formatEgp
import com.example.spendwise.viewmodel.CategorySpendingUi
import com.example.spendwise.viewmodel.HomeUiState
import com.example.spendwise.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    onAddExpense: () -> Unit,
    onAddIncome: () -> Unit,
    onScanReceipt: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "SpendWise",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = uiState.monthLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SummaryCard(uiState)
        SpendingBreakdown(uiState.categorySpending)
        InsightCard(uiState.insight)
        QuickActions(onAddExpense, onAddIncome, onScanReceipt)
    }
}

@Composable
private fun SummaryCard(uiState: HomeUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Monthly summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            SummaryLine("Income", formatEgp(uiState.totalIncomeMinor))
            SummaryLine("Spent", formatEgp(uiState.totalSpentMinor))
            SummaryLine("Remaining", formatEgp(uiState.remainingMinor), emphasized = true)
            Text(
                text = transactionCountLabel(uiState.transactionCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            val percentage = uiState.incomeSpentPercentageTenths
            if (percentage == null) {
                Text(
                    text = "Add income to see how much of your monthly income has been spent.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = "${formatPercentage(percentage)} of income spent",
                    style = MaterialTheme.typography.bodyMedium
                )
                LinearProgressIndicator(
                    progress = { percentage.coerceIn(0, 1_000) / 1_000f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, amount: String, emphasized: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
        )
        Text(
            text = amount,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SpendingBreakdown(categories: List<CategorySpendingUi>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Where did your money go?",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            if (categories.isEmpty()) {
                Text(
                    text = "Add an expense to see your monthly spending breakdown.",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    categories.forEach { category ->
                        CategorySpendingRow(category)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySpendingRow(category: CategorySpendingUi) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatEgp(category.amountMinor),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = "${formatPercentage(category.percentageTenths)} of total spending",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { category.percentageTenths.coerceIn(0, 1_000) / 1_000f },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun InsightCard(insight: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Insight",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = insight, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun QuickActions(
    onAddExpense: () -> Unit,
    onAddIncome: () -> Unit,
    onScanReceipt: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Quick actions",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Button(onClick = onAddExpense, modifier = Modifier.fillMaxWidth()) {
            Text("Add Expense")
        }
        OutlinedButton(onClick = onScanReceipt, modifier = Modifier.fillMaxWidth()) {
            Text("Scan Receipt")
        }
        TextButton(onClick = onAddIncome, modifier = Modifier.fillMaxWidth()) {
            Text("Add Income")
        }
    }
}

private fun transactionCountLabel(count: Int): String =
    if (count == 1) "1 expense transaction" else "$count expense transactions"

private fun formatPercentage(tenths: Int): String =
    if (tenths % 10 == 0) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
