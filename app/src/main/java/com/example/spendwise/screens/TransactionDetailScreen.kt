package com.example.spendwise.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.formatEgp
import com.example.spendwise.viewmodel.TransactionDetailViewModel
import java.time.Instant
import java.time.ZoneOffset

@Composable
fun TransactionDetailScreen(onBack: () -> Unit, viewModel: TransactionDetailViewModel = viewModel()) {
    val row by viewModel.transaction.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Transaction", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        row?.let { detail ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatEgp(detail.transaction.amountMinor), style = MaterialTheme.typography.headlineMedium)
                    Text(detail.category?.name ?: detail.transaction.type.name.lowercase().replaceFirstChar(Char::uppercase))
                    detail.transaction.merchant?.let { Text(it) }
                    Text(Instant.ofEpochMilli(detail.transaction.transactionDate).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    detail.transaction.note?.let { Text(it) }
                }
            }
        } ?: Text("Transaction not found")
    }
}
