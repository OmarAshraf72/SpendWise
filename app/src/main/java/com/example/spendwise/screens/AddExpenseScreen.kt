package com.example.spendwise.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.*
import com.example.spendwise.viewmodel.TransactionsViewModel
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val expenseDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
private data class SplitDraft(val id: Long, val note: String = "", val amount: String = "", val categoryId: Long? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(onSaved: () -> Unit, viewModel: TransactionsViewModel = viewModel()) {
    val categories by viewModel.entryCategories.collectAsStateWithLifecycle()
    val merchantSuggestions by viewModel.merchantSuggestions.collectAsStateWithLifecycle()
    val dateProvider = remember { ExpenseDateProvider() }
    val amountFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var amount by rememberSaveable { mutableStateOf("") }
    var merchant by rememberSaveable { mutableStateOf("") }
    var selectedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var categoryQuery by rememberSaveable { mutableStateOf("") }
    var categoryChosenByUser by rememberSaveable { mutableStateOf(false) }
    var merchantSuggestedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedDate by rememberSaveable { mutableStateOf(dateProvider.todayStartMillis()) }
    var splitEnabled by rememberSaveable { mutableStateOf(false) }
    var splits by remember { mutableStateOf(listOf(SplitDraft(1), SplitDraft(2))) }
    var nextSplitId by remember { mutableStateOf(3L) }
    var categoryExpanded by rememberSaveable { mutableStateOf(false) }
    var merchantExpanded by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val pageScrollState = rememberScrollState()

    BackHandler(enabled = merchantExpanded || categoryExpanded) {
        merchantExpanded = false
        categoryExpanded = false
        focusManager.clearFocus()
    }

    val totalMinor = parseEgpToMinor(amount)
    val splitInputs = splits.map { SplitExpenseInput(parseEgpToMinor(it.amount), it.categoryId, it.note) }
    val allocation = validateSplitAllocation(totalMinor ?: 0L, splitInputs)
    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
    LaunchedEffect(Unit) { amountFocus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(pageScrollState, enabled = !merchantExpanded && !categoryExpanded)
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Add Expense", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = amount, onValueChange = { amount = it; validationMessage = null },
            label = { Text("Amount") }, suffix = { Text("EGP") },
            textStyle = MaterialTheme.typography.headlineMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(amountFocus)
        )

        OutlinedTextField(
            value = merchant,
            onValueChange = {
                merchant = it
                viewModel.updateMerchantQuery(it)
                merchantExpanded = true
                categoryExpanded = false
            },
            label = { Text("Merchant") }, placeholder = { Text("Search merchant") },
            trailingIcon = {
                IconButton(onClick = {
                    if (merchantExpanded) {
                        merchantExpanded = false
                        focusManager.clearFocus()
                    } else {
                        merchantExpanded = true
                        categoryExpanded = false
                    }
                }) {
                    Icon(
                        imageVector = if (merchantExpanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                        contentDescription = if (merchantExpanded) "Close merchant options" else "Open merchant options"
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { merchantExpanded = false; focusManager.clearFocus() }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                if (it.isFocused) {
                    merchantExpanded = true
                    categoryExpanded = false
                }
            }
        )
        if (merchantExpanded) {
            InlineSuggestionList(
                suggestions = merchantSuggestions.map { suggestion ->
                    InlineSuggestion(suggestion.merchant.id, suggestion, suggestion.merchant.displayName,
                        suggestion.merchant.usageCount.takeIf { it > 0 }?.let { "Used $it times" })
                },
                onSelected = { suggestion ->
                    merchant = suggestion.merchant.displayName
                    merchantSuggestedCategoryId = suggestion.merchant.defaultCategoryId?.takeIf { id -> categories.any { it.id == id } }
                    if (!categoryChosenByUser) {
                        selectedCategoryId = merchantSuggestedCategoryId
                        categoryQuery = categories.firstOrNull { it.id == selectedCategoryId }?.name.orEmpty()
                    }
                    viewModel.updateMerchantQuery(merchant)
                    merchantExpanded = false
                    focusManager.clearFocus()
                },
                addLabel = if (shouldOfferNewMerchant(merchant, merchantSuggestions)) "Add “${merchant.trim()}”" else null,
                onAdd = if (shouldOfferNewMerchant(merchant, merchantSuggestions)) {{
                    viewModel.createMerchant(merchant) { created ->
                        created?.let { merchant = it.displayName }
                        merchantExpanded = false
                        focusManager.clearFocus()
                    }
                }} else null,
                emptyMessage = if (merchant.isNotBlank() && merchantSuggestions.isEmpty()) "No matching merchants" else null
            )
        }

        SearchableCategorySelector(
            categories = categories,
            query = categoryQuery,
            selected = selectedCategory,
            suggestedCategoryId = merchantSuggestedCategoryId,
            expanded = categoryExpanded,
            onToggleExpand = { expanded ->
                categoryExpanded = expanded
                if (expanded) merchantExpanded = false else focusManager.clearFocus()
            },
            onQueryChange = { categoryQuery = it; selectedCategoryId = null },
            onFocusChange = { focused ->
                if (focused) {
                    categoryExpanded = true
                    merchantExpanded = false
                }
            },
            onSelected = { category ->
                selectedCategoryId = category.id
                categoryQuery = category.name
                categoryChosenByUser = true
                categoryExpanded = false
                merchantExpanded = false
                focusManager.clearFocus()
            }
        )
        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(dateLabel(selectedDate, dateProvider))
        }
        Row(
            modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Split this purchase", style = MaterialTheme.typography.titleMedium)
                Text("Assign parts to different categories", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = splitEnabled, onCheckedChange = { splitEnabled = it; validationMessage = null })
        }

        if (splitEnabled) {
            splits.forEachIndexed { index, split ->
                SplitRow(
                    split, categories, allocation.remainingMinor,
                    onChange = { updated -> splits = splits.toMutableList().also { it[index] = updated } },
                    onRemove = { if (splits.size > 1) splits = splits.filterNot { it.id == split.id } }
                )
            }
            TextButton(onClick = { splits = splits + SplitDraft(nextSplitId++) }) { Text("+ Add split") }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Assigned: ${formatEgp(allocation.assignedMinor)}")
                    Text(
                        if (allocation.remainingMinor >= 0) "Remaining: ${formatEgp(allocation.remainingMinor)}"
                        else "Over allocated: ${formatEgp(-allocation.remainingMinor)}",
                        color = if (allocation.remainingMinor == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val parsed = parseEgpToMinor(amount)
                if (parsed == null || parsed <= 0L) {
                    validationMessage = "Enter an amount greater than zero with up to 2 decimal places."
                } else {
                    viewModel.saveManualPurchase(
                        parsed, selectedCategoryId, merchant, selectedDate,
                        splitInputs.takeIf { splitEnabled }, onSaved
                    ) { validationMessage = it }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Expense") }
    }

    if (showDatePicker) {
        SpendWiseDatePickerDialog(
            initialDate = Instant.ofEpochMilli(selectedDate).atZone(ZoneOffset.UTC).toLocalDate(),
            onDateSelected = {
                selectedDate = it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
            showTodayAction = true
        )
    }
}

@Composable
private fun SearchableCategorySelector(
    categories: List<CategoryEntity>, query: String, selected: CategoryEntity?, suggestedCategoryId: Long?, expanded: Boolean,
    onToggleExpand: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit, onFocusChange: (Boolean) -> Unit, onSelected: (CategoryEntity) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                onQueryChange(it)
                onToggleExpand(true)
            },
            label = { Text("Category") }, placeholder = { Text("Search categories") },
            trailingIcon = {
                IconButton(onClick = {
                    onToggleExpand(!expanded)
                }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                        contentDescription = if (expanded) "Close category options" else "Open category options"
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChange(it.isFocused) }
        )
        if (expanded) {
            val ranked = rankCategories(query, categories, suggestedCategoryId, limit = null)
            InlineSuggestionList(
                suggestions = ranked.map { InlineSuggestion(it.category.id, it.category, it.category.name,
                    if (it.category.id == suggestedCategoryId) "Suggested for this merchant" else null) },
                onSelected = onSelected,
                emptyMessage = if (query.isNotBlank() && ranked.isEmpty()) "No matching categories" else null
            )
        }
    }
}

@Composable
private fun SplitRow(
    split: SplitDraft, categories: List<CategoryEntity>, remainingMinor: Long,
    onChange: (SplitDraft) -> Unit, onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val category = categories.firstOrNull { it.id == split.categoryId }
    var query by rememberSaveable(split.id) { mutableStateOf(category?.name.orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = split.note, onValueChange = { onChange(split.copy(note = it)) },
                label = { Text("Item or note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            SearchableCategorySelector(categories, query, category, null, expanded,
                onToggleExpand = { expanded = it },
                onQueryChange = { query = it; expanded = true; onChange(split.copy(categoryId = null)) },
                onFocusChange = { if (it) expanded = true },
                onSelected = { selected -> query = selected.name; expanded = false; onChange(split.copy(categoryId = selected.id)) })
            OutlinedTextField(
                value = split.amount, onValueChange = { onChange(split.copy(amount = it)) },
                label = { Text("Split amount") }, suffix = { Text("EGP") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onRemove) { Text("Remove") }
                if (remainingMinor > 0L) {
                    TextButton(onClick = {
                        val current = parseEgpToMinor(split.amount) ?: 0L
                        onChange(split.copy(amount = minorToInput(current + remainingMinor)))
                    }) { Text("Use remaining") }
                }
            }
        }
    }
}

private fun minorToInput(value: Long): String = if (value % 100L == 0L) (value / 100L).toString()
else "${value / 100L}.${(value % 100L).toString().padStart(2, '0')}"

private fun dateLabel(value: Long, provider: ExpenseDateProvider): String {
    val formatted = Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate().format(expenseDateFormatter)
    return if (provider.isToday(value)) "Today · $formatted" else formatted
}
