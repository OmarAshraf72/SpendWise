package com.example.spendwise.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.*
import com.example.spendwise.viewmodel.MaintenanceManagementViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val maintenanceDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun AssetMaintenanceScreen(onBack: () -> Unit, openCompletion: Boolean = false, initialRuleId: Long? = null,
                           viewModel: MaintenanceManagementViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val pending by viewModel.pendingDocuments.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var editingRule by remember { mutableStateOf<AssetMaintenanceRuleEntity?>(null) }
    var addingRule by remember { mutableStateOf(false) }
    var archivingRule by remember { mutableStateOf<AssetMaintenanceRuleEntity?>(null) }
    var completingRule by remember { mutableStateOf<AssetMaintenanceRuleEntity?>(null) }
    var completing by remember { mutableStateOf(openCompletion) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.stageDocuments(uris) { message = it }
    }
    val asset = state.asset
    if (asset == null) { Text("Asset not found", modifier = Modifier.padding(24.dp)); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Maintenance", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(asset.name)
                }
            }
        }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        item {
            MaintenanceSection("Next / Current maintenance") {
                val active = state.rules.filter { it.isActive }
                if (active.isEmpty()) Text("No maintenance plan")
                active.forEach { rule ->
                    val due = state.dueByRule[rule.id]
                    Text(rule.title, fontWeight = FontWeight.SemiBold)
                    Text(rule.triggerType.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodySmall)
                    Text(buildList {
                        due?.nextDueDate?.let { add("Next: ${it.format(maintenanceDateFormat)}") }
                        due?.nextDueMileageKm?.let { add("Next: $it km") }
                    }.joinToString(" · ").ifEmpty { "Next due unavailable" })
                    due?.let { Text(it.status.name.replace('_', ' '), color = if (it.status == MaintenanceDueStatus.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { completingRule = rule; completing = true }) { Text("Complete") }
                    HorizontalDivider()
                }
                Button(onClick = { completingRule = null; completing = true }) { Text("Complete maintenance") }
            }
        }
        item {
            MaintenanceSection("Maintenance rules") {
                state.rules.forEach { rule ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.title, fontWeight = FontWeight.SemiBold)
                            Text(if (rule.isActive) rule.triggerType.name.replace('_', ' ') else "Archived", style = MaterialTheme.typography.bodySmall)
                        }
                        if (rule.isActive) {
                            TextButton(onClick = { editingRule = rule }) { Text("Edit") }
                            TextButton(onClick = { archivingRule = rule }) { Text("Archive") }
                        }
                    }
                }
                OutlinedButton(onClick = { addingRule = true }) { Text("Add rule") }
            }
        }
        item { Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (state.events.isEmpty()) item { Text("No completed maintenance yet") }
        items(state.events, key = { it.id }) { event ->
            val documents = state.documentLinks.filter { it.maintenanceEventId == event.id }
                .mapNotNull { link -> state.documents.firstOrNull { it.id == link.assetDocumentId } }
            MaintenanceSection(event.title) {
                Text(LocalDate.ofEpochDay(event.performedDateEpochDay).format(maintenanceDateFormat))
                event.mileageKm?.let { Text("Mileage: $it km") }
                event.costMinor?.let { Text("Cost: ${formatEgp(it)}") }
                event.providerNameSnapshot?.let { Text("Provider: $it") }
                event.notes?.let { Text(it) }
                Text(if (event.linkedTransactionId != null) "Expense linked" else "No expense created", style = MaterialTheme.typography.bodySmall)
                if (documents.isNotEmpty()) Text("Documents (${documents.size})", fontWeight = FontWeight.SemiBold)
                documents.forEach { document ->
                    TextButton(onClick = {
                        val file = viewModel.documentFile(document.storedRelativePath)
                        if (file == null) message = "Stored file is missing" else runCatching {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, document.mimeType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }.onFailure { message = "No app can open this document" }
                    }) { Text(document.title) }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (addingRule || editingRule != null) {
        MaintenanceRuleEditor(asset, editingRule, dismiss = { addingRule = false; editingRule = null }) { rule ->
            viewModel.saveRule(rule) { error ->
                message = error ?: "Rule saved"
                if (error == null) { addingRule = false; editingRule = null }
            }
        }
    }
    archivingRule?.let { rule -> AlertDialog(onDismissRequest = { archivingRule = null }, title = { Text("Archive ${rule.title}?") },
        text = { Text("Completed maintenance will remain in history.") },
        confirmButton = { TextButton(onClick = { viewModel.archiveRule(rule.id) { message = it ?: "Rule archived"; if (it == null) archivingRule = null } }) { Text("Archive") } },
        dismissButton = { TextButton(onClick = { archivingRule = null }) { Text("Cancel") } }) }
    if (completing) MaintenanceCompletionDialog(asset, state.rules.filter { it.isActive },
        completingRule ?: state.rules.firstOrNull { it.id == initialRuleId && it.isActive },
        categories, merchants, pending, busy, onPickDocuments = { picker.launch(arrayOf("image/*", "application/pdf")) },
        onRemoveDocument = viewModel::removeDocument,
        dismiss = { viewModel.clearDrafts(); completing = false; completingRule = null }) { input ->
        viewModel.complete(input) { error ->
            message = error ?: "Maintenance completed"
            if (error == null) { completing = false; completingRule = null }
        }
    }
}

@Composable private fun MaintenanceSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        content()
    } }
}

@Composable private fun MaintenanceRuleEditor(asset: AssetEntity, existing: AssetMaintenanceRuleEntity?, dismiss: () -> Unit,
                                              save: (AssetMaintenanceRuleEntity) -> Unit) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var trigger by remember(existing?.id) { mutableStateOf(existing?.triggerType ?: MaintenanceTriggerType.TIME) }
    var months by remember(existing?.id) { mutableStateOf(existing?.intervalMonths?.toString().orEmpty()) }
    var km by remember(existing?.id) { mutableStateOf(existing?.intervalKm?.toString().orEmpty()) }
    var baselineDate by remember(existing?.id) { mutableStateOf(existing?.baselineDateEpochDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now()) }
    var baselineKm by remember(existing?.id) { mutableStateOf(existing?.baselineMileageKm?.toString() ?: asset.currentMileageKm?.toString().orEmpty()) }
    var notes by remember(existing?.id) { mutableStateOf(existing?.notes.orEmpty()) }
    var pickingDate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (existing == null) "Add maintenance rule" else "Edit maintenance rule") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            MaintenanceSelect("Trigger", trigger.name.replace('_', ' '), MaintenanceTriggerType.entries.map { it.name.replace('_', ' ') }) {
                trigger = MaintenanceTriggerType.entries[it]
            }
            if (trigger != MaintenanceTriggerType.MILEAGE) {
                OutlinedTextField(months, { months = it.filter(Char::isDigit) }, label = { Text("Every months") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { pickingDate = true }) { Text("Baseline: ${baselineDate.format(maintenanceDateFormat)}") }
            }
            if (trigger != MaintenanceTriggerType.TIME) {
                OutlinedTextField(km, { km = it.filter(Char::isDigit) }, label = { Text("Every km") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(baselineKm, { baselineKm = it.filter(Char::isDigit) }, label = { Text("Baseline mileage (km)") }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = {
            val m = months.toIntOrNull(); val k = km.toLongOrNull(); val base = baselineKm.toLongOrNull()
            if (title.isBlank() || (trigger != MaintenanceTriggerType.MILEAGE && (m == null || m <= 0)) ||
                (trigger != MaintenanceTriggerType.TIME && (k == null || k <= 0 || base == null))) error = "Enter a name and valid interval/baseline"
            else save(AssetMaintenanceRuleEntity(id = existing?.id ?: 0, assetId = asset.id, title = title.trim(), triggerType = trigger,
                intervalMonths = m.takeIf { trigger != MaintenanceTriggerType.MILEAGE }, intervalKm = k.takeIf { trigger != MaintenanceTriggerType.TIME },
                baselineDateEpochDay = baselineDate.toEpochDay().takeIf { trigger != MaintenanceTriggerType.MILEAGE },
                baselineMileageKm = base.takeIf { trigger != MaintenanceTriggerType.TIME }, warningDays = existing?.warningDays ?: 30,
                warningKm = existing?.warningKm ?: 1000, notes = notes.trim().takeIf(String::isNotBlank),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    if (pickingDate) SpendWiseDatePickerDialog(baselineDate, { baselineDate = it; pickingDate = false }, { pickingDate = false })
}

@Composable private fun MaintenanceCompletionDialog(asset: AssetEntity, rules: List<AssetMaintenanceRuleEntity>, initialRule: AssetMaintenanceRuleEntity?,
    categories: List<CategoryEntity>, merchants: List<MerchantEntity>, pending: List<PendingAssetDocument>, busy: Boolean,
    onPickDocuments: () -> Unit, onRemoveDocument: (PendingAssetDocument) -> Unit, dismiss: () -> Unit,
    save: (MaintenanceEventInput) -> Unit) {
    var ruleId by remember(initialRule?.id) { mutableStateOf(initialRule?.id) }
    var title by remember(initialRule?.id) { mutableStateOf(initialRule?.title.orEmpty()) }
    var performed by remember { mutableStateOf(LocalDate.now()) }
    var mileage by remember { mutableStateOf(asset.currentMileageKm?.toString().orEmpty()) }
    var cost by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var createExpense by remember { mutableStateOf(false) }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val key = remember { UUID.randomUUID().toString() }
    val selectedRule = rules.firstOrNull { it.id == ruleId }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Complete maintenance") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MaintenanceSelect("Rule", selectedRule?.title ?: "Ad-hoc", listOf("Ad-hoc") + rules.map { it.title }) {
                ruleId = if (it == 0) null else rules[it - 1].id
                if (it > 0) title = rules[it - 1].title
            }
            OutlinedTextField(title, { title = it }, label = { Text("Work performed") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { pickingDate = true }) { Text("Performed: ${performed.format(maintenanceDateFormat)}") }
            OutlinedTextField(mileage, { mileage = it.filter(Char::isDigit) }, label = { Text("Mileage (km)${if (selectedRule?.triggerType != null && selectedRule.triggerType != MaintenanceTriggerType.TIME) " required" else " optional"}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cost, { cost = it }, label = { Text("Cost (EGP, optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            MaintenanceSelect("Known provider", "Select merchant (optional)", listOf("None") + merchants.map { it.displayName }) {
                provider = if (it == 0) "" else merchants[it - 1].displayName
            }
            OutlinedTextField(provider, { provider = it }, label = { Text("Provider / merchant (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(createExpense, { createExpense = it }); Spacer(Modifier.width(8.dp)); Text("Create expense") }
            if (createExpense) MaintenanceSelect("Expense category", categories.firstOrNull { it.id == categoryId }?.name ?: "Select category", categories.map { it.name }) {
                categoryId = categories[it].id
            }
            pending.forEach { doc -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(doc.originalFileName, modifier = Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { onRemoveDocument(doc) }, enabled = !busy) { Text("Remove") }
            } }
            OutlinedButton(onClick = onPickDocuments, enabled = !busy) { Text("Attach image or PDF") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = {
            val amount = cost.takeIf(String::isNotBlank)?.let(::parseEgpToMinor)
            val completedMileage = mileage.toLongOrNull()
            error = when {
                title.isBlank() -> "Enter the work performed"
                cost.isNotBlank() && (amount == null || amount <= 0) -> "Enter a valid positive cost"
                mileage.isNotBlank() && completedMileage == null -> "Enter a valid mileage"
                completedMileage != null && !canUpdateAssetMileage(asset.currentMileageKm, completedMileage) -> "Mileage cannot be lower than current mileage"
                selectedRule?.triggerType != null && selectedRule.triggerType != MaintenanceTriggerType.TIME && completedMileage == null -> "Mileage is required for this rule"
                createExpense && (amount == null || amount <= 0 || categoryId == null) -> "Expense needs a positive cost and category"
                else -> null
            }
            if (error == null) {
                val matchedMerchant = merchants.firstOrNull { it.displayName.equals(provider.trim(), ignoreCase = true) }
                save(MaintenanceEventInput(asset.id, ruleId, title.trim(), performed, completedMileage, amount,
                    matchedMerchant?.id, provider.trim().takeIf(String::isNotBlank), categoryId, createExpense,
                    notes.trim().takeIf(String::isNotBlank), key))
            }
        }, enabled = !busy) { Text(if (busy) "Saving…" else "Save") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } })
    if (pickingDate) SpendWiseDatePickerDialog(performed, { performed = it; pickingDate = false }, { pickingDate = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MaintenanceSelect(label: String, value: String, choices: List<String>, select: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) {
            choices.forEachIndexed { index, choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { select(index); expanded = false }) }
        }
    }
}
