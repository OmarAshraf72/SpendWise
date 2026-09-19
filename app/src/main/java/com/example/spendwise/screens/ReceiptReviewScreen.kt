package com.example.spendwise.screens

import android.net.Uri
import android.content.pm.ApplicationInfo
import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.spendwise.data.CategoryEntity
import com.example.spendwise.data.formatEgp
import com.example.spendwise.data.parseEgpToMinor
import com.example.spendwise.suggestion.CategorySuggestionSource
import com.example.spendwise.viewmodel.ReceiptItemDraft
import com.example.spendwise.viewmodel.ReceiptSaveState
import com.example.spendwise.viewmodel.ReceiptViewModel
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val receiptDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptReviewScreen(
    viewModel: ReceiptViewModel,
    onSaved: () -> Unit
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var editingItem by remember { mutableStateOf<ReceiptItemDraft?>(null) }
    var showItemEditor by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showOcrDebug by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isDebugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Review Receipt", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "OCR suggestions are fully editable. Check the merchant, date, item names, prices, and choose a category for every item.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        draft.ocrMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    message,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isDebugBuild && draft.ocrDebugDetails != null) {
            OutlinedButton(
                onClick = { showOcrDebug = !showOcrDebug },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showOcrDebug) "Hide OCR debug details" else "Show OCR debug details")
            }
            if (showOcrDebug) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = draft.ocrDebugDetails.orEmpty(),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        ReceiptImagePreview(draft.imageUri)
        OutlinedTextField(
            value = draft.merchant,
            onValueChange = viewModel::setMerchant,
            label = { Text("Merchant (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = formatReceiptDate(draft.transactionDate),
            onValueChange = {},
            readOnly = true,
            label = { Text("Date") },
            modifier = Modifier.fillMaxWidth().clickable { showDatePicker = true }
        )
        TextButton(onClick = { showDatePicker = true }) { Text("Change date") }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Calculated items total", style = MaterialTheme.typography.titleMedium)
                Text(formatEgp(draft.totalMinor), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        draft.detectedTotalMinor?.let { detectedTotal ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Detected receipt total", style = MaterialTheme.typography.titleMedium)
                        Text(formatEgp(detectedTotal), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (abs(detectedTotal - draft.totalMinor) > 2L) {
                        Text(
                            "Detected receipt total is ${formatEgp(detectedTotal)} but listed items total is ${formatEgp(draft.totalMinor)}. Review the receipt items before saving.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        Text("Receipt items", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (draft.items.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Add each receipt line so SpendWise can preserve its category.",
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            draft.items.forEach { item ->
                ReceiptItemRow(
                    item = item,
                    categoryName = categories.firstOrNull { it.id == item.categoryId }?.name ?: "Select category",
                    onEdit = {
                        editingItem = item
                        showItemEditor = true
                    },
                    onDelete = { viewModel.deleteItem(item.id) }
                )
            }
        }
        OutlinedButton(
            onClick = {
                editingItem = null
                showItemEditor = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text("  Add Item")
        }
        draft.validationMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = { viewModel.saveReceipt(onSaved) },
            enabled = draft.saveState != ReceiptSaveState.SAVING && draft.saveState != ReceiptSaveState.SUCCESS,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (draft.saveState == ReceiptSaveState.SAVING) "Saving…" else "Save Receipt")
        }
    }

    if (showItemEditor) {
        ReceiptItemDialog(
            item = editingItem,
            categories = categories,
            onDismiss = { showItemEditor = false },
            onSave = { name, amountMinor, categoryId ->
                val item = editingItem
                if (item == null) viewModel.addItem(name, amountMinor, categoryId)
                else viewModel.updateItem(item.id, name, amountMinor, categoryId)
                showItemEditor = false
            }
        )
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = draft.transactionDate)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let(viewModel::setDate)
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun ReceiptImagePreview(imageUri: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        if (imageUri == null) {
            Text("Receipt image unavailable", modifier = Modifier.padding(24.dp))
        } else {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                },
                update = { imageView ->
                    if (imageView.tag != imageUri) {
                        imageView.setImageURI(Uri.parse(imageUri))
                        imageView.tag = imageUri
                    }
                },
                modifier = Modifier.fillMaxWidth().height(260.dp)
            )
        }
    }
}

@Composable
private fun ReceiptItemRow(
    item: ReceiptItemDraft,
    categoryName: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(categoryName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.categorySuggestion?.source == CategorySuggestionSource.USER_LEARNED) {
                    Text(
                        "Suggested from previous choices",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(formatEgp(item.amountMinor), style = MaterialTheme.typography.bodyLarge)
                if (item.requiresPriceReview) {
                    Text(
                        "Price recovered — please verify",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit ${item.name}") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${item.name}") }
        }
    }
}

@Composable
private fun ReceiptItemDialog(
    item: ReceiptItemDraft?,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (String, Long, Long) -> Unit
) {
    var name by remember(item?.id) { mutableStateOf(item?.name.orEmpty()) }
    var amount by remember(item?.id) { mutableStateOf(item?.amountMinor?.let(::minorToInput).orEmpty()) }
    var categoryId by remember(item?.id) { mutableStateOf(item?.categoryId) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showValidation by remember { mutableStateOf(false) }
    val amountMinor = parseEgpToMinor(amount)
    val category = categories.firstOrNull { it.id == categoryId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Add Receipt Item" else "Edit Receipt Item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Item name") },
                    isError = showValidation && name.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Price (EGP)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = showValidation && (amountMinor == null || amountMinor <= 0L),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = category?.name.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        placeholder = { Text("Select a category") },
                        isError = showValidation && categoryId == null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(modifier = Modifier.matchParentSize().clickable { menuExpanded = true })
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        categories.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.name) },
                                onClick = {
                                    categoryId = option.id
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                showValidation = true
                val validAmount = parseEgpToMinor(amount)
                val validCategory = categoryId
                if (name.isNotBlank() && validAmount != null && validAmount > 0L && validCategory != null) {
                    onSave(name.trim(), validAmount, validCategory)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun minorToInput(amountMinor: Long): String =
    BigDecimal.valueOf(amountMinor, 2).stripTrailingZeros().toPlainString()

private fun formatReceiptDate(value: Long): String = Instant.ofEpochMilli(value)
    .atZone(ZoneOffset.UTC)
    .toLocalDate()
    .format(receiptDateFormatter)
