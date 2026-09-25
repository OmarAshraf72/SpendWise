package com.example.spendwise.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
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
    var addingKind by remember { mutableStateOf<MaintenanceRuleKind?>(null) }
    var choosingKind by remember { mutableStateOf(false) }
    var showingKindInfo by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
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
    BackHandler(managing) { managing = false }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (managing) managing = false else onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(if (managing) "Manage maintenance" else "Maintenance", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(asset.name)
                }
            }
        }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (managing) {
            item { OutlinedButton(onClick = { choosingKind = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add maintenance") } }
            for (kind in MaintenanceRuleKind.entries) {
                item {
                    MaintenanceSection(if (kind == MaintenanceRuleKind.SERVICE_SCHEDULE) "Scheduled services" else "Individual maintenance") {
                        val rules = state.rules.filter { it.kind == kind }
                        if (rules.isEmpty()) Text("Nothing configured yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        rules.forEach { rule ->
                            Text(rule.title, fontWeight = FontWeight.SemiBold)
                            Text(if (rule.isActive) "Active" else "Archived", style = MaterialTheme.typography.bodySmall)
                            if (rule.isActive) Row {
                                TextButton(onClick = { editingRule = rule }) { Text("Edit") }
                                TextButton(onClick = { archivingRule = rule }) { Text("Archive") }
                            }
                            HorizontalDivider()
                        }
                        OutlinedButton(onClick = { addingKind = kind }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (kind == MaintenanceRuleKind.SERVICE_SCHEDULE) "Add scheduled service" else "Add maintenance item")
                        }
                    }
                }
            }
        } else {
            item { Text("Upcoming", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            for (kind in MaintenanceRuleKind.entries) {
                item {
                    MaintenanceSection(if (kind == MaintenanceRuleKind.SERVICE_SCHEDULE) "Scheduled services" else "Tracked maintenance") {
                        val active = state.rules.filter { it.isActive && it.kind == kind }
                        if (active.isEmpty()) Text("Nothing tracked yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        active.forEach { rule ->
                            val due = state.dueByRule[rule.id]
                            Text(rule.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(buildString {
                                if (due?.nextDueMileageKm != null) append("Next at %,d km".format(due.nextDueMileageKm))
                                if (due?.nextDueMileageKm != null && due.nextDueDate != null) append(" · ")
                                if (due?.nextDueDate != null) append("Next on ${due.nextDueDate.format(maintenanceDateFormat)}")
                                if (isEmpty()) append("Next due unavailable")
                            })
                            due?.let {
                                Text(when (it.status) {
                                    MaintenanceDueStatus.OVERDUE -> "Overdue"
                                    MaintenanceDueStatus.DUE, MaintenanceDueStatus.DUE_SOON -> "Due soon"
                                    MaintenanceDueStatus.OK -> "On track"
                                }, style = MaterialTheme.typography.labelMedium,
                                    color = if (it.status == MaintenanceDueStatus.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { completingRule = rule; completing = true }) { Text("Mark as done") }
                            HorizontalDivider()
                        }
                    }
                }
            }
            item { TextButton(onClick = { completingRule = null; completing = true }, Modifier.fillMaxWidth()) { Text("+ Record other maintenance") } }
            item { OutlinedButton(onClick = { choosingKind = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add maintenance") } }
        item { Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (state.events.isEmpty()) item { Text("No completed maintenance yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.events, key = { it.id }) { event ->
            val documents = state.documentLinks.filter { it.maintenanceEventId == event.id }
                .mapNotNull { link -> state.documents.firstOrNull { it.id == link.assetDocumentId } }
            MaintenanceSection(event.title) {
                Text(LocalDate.ofEpochDay(event.performedDateEpochDay).format(maintenanceDateFormat), style = MaterialTheme.typography.bodyMedium)
                event.mileageKm?.let { Text("%,d km".format(it), style = MaterialTheme.typography.bodyMedium) }
                event.costMinor?.let { Text(formatEgp(it), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                event.providerNameSnapshot?.let { Text("Provider: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                event.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(if (event.linkedTransactionId != null) "Expense linked" else "No expense created", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        item { OutlinedButton(onClick = { managing = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Manage maintenance") } }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (addingKind != null || editingRule != null) {
        MaintenanceRuleEditor(asset, editingRule, editingRule?.kind ?: addingKind ?: MaintenanceRuleKind.MAINTENANCE_ITEM,
            dismiss = { addingKind = null; editingRule = null }) { rule ->
            viewModel.saveRule(rule) { error ->
                message = error ?: "Rule saved"
                if (error == null) { addingKind = null; editingRule = null }
            }
        }
    }
    if (choosingKind) AlertDialog(onDismissRequest = { choosingKind = false },
        title = { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("How do you want to track maintenance?", Modifier.weight(1f))
            IconButton(onClick = { showingKindInfo = true }) { Icon(Icons.Outlined.Info, "About maintenance options") }
        } },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { addingKind = MaintenanceRuleKind.SERVICE_SCHEDULE; choosingKind = false }, Modifier.fillMaxWidth()) {
                Column { Text("Scheduled services"); Text("Follow a dealer or service-center schedule.", style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedButton(onClick = { addingKind = MaintenanceRuleKind.MAINTENANCE_ITEM; choosingKind = false }, Modifier.fillMaxWidth()) {
                Column { Text("Track individual items"); Text("Track oil, filters, brakes, tires or battery.", style = MaterialTheme.typography.bodySmall) }
            }
        } }, confirmButton = { TextButton(onClick = { choosingKind = false }) { Text("Close") } })
    if (showingKindInfo) AlertDialog(onDismissRequest = { showingKindInfo = false }, title = { Text("Maintenance options") },
        text = { Text("Scheduled services track your next dealer or service-center visit, cost and invoice. Individual items track specific work such as oil, brakes, tires and battery. You can use both for the same vehicle.") },
        confirmButton = { TextButton(onClick = { showingKindInfo = false }) { Text("Got it") } })
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

@Composable private fun MaintenanceRuleEditor(
    asset: AssetEntity,
    existing: AssetMaintenanceRuleEntity?,
    kind: MaintenanceRuleKind,
    dismiss: () -> Unit,
    save: (AssetMaintenanceRuleEntity) -> Unit
) {
    var title by remember(existing?.id, kind) { mutableStateOf(existing?.title ?: if (kind == MaintenanceRuleKind.SERVICE_SCHEDULE) "Dealer service" else "") }
    var trigger by remember(existing?.id) { mutableStateOf(existing?.triggerType ?: MaintenanceTriggerType.TIME_OR_MILEAGE) }
    var months by remember(existing?.id) { mutableStateOf(existing?.intervalMonths?.toString().orEmpty()) }
    var km by remember(existing?.id) { mutableStateOf(existing?.intervalKm?.toString().orEmpty()) }
    var baselineDate by remember(existing?.id) { mutableStateOf(existing?.baselineDateEpochDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now()) }
    var baselineKm by remember(existing?.id) { mutableStateOf(existing?.baselineMileageKm?.toString() ?: asset.currentMileageKm?.toString().orEmpty()) }
    var notes by remember(existing?.id) { mutableStateOf(existing?.notes.orEmpty()) }
    var showAdvanced by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val triggerLabels = listOf("Whichever comes first", "Distance", "Time")
    val triggerValues = listOf(MaintenanceTriggerType.TIME_OR_MILEAGE, MaintenanceTriggerType.MILEAGE, MaintenanceTriggerType.TIME)
    val selectedTriggerLabel = when (trigger) {
        MaintenanceTriggerType.TIME_OR_MILEAGE -> "Whichever comes first"
        MaintenanceTriggerType.MILEAGE -> "Distance"
        MaintenanceTriggerType.TIME -> "Time"
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (kind == MaintenanceRuleKind.SERVICE_SCHEDULE) "Scheduled service" else "What needs maintenance?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (kind == MaintenanceRuleKind.MAINTENANCE_ITEM) {
                    Text("Suggested items", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(MaintenanceItemSuggestions.names) { suggestion ->
                            OutlinedButton(onClick = { title = if (suggestion == "Other / Custom") "" else suggestion }) { Text(suggestion, maxLines = 1) }
                        }
                    }
                }
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text(if (kind == MaintenanceRuleKind.SERVICE_SCHEDULE) "Name" else "Maintenance item") },
                    placeholder = { Text(if (kind == MaintenanceRuleKind.SERVICE_SCHEDULE) "e.g. Dealer service" else "e.g. Air filter") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                MaintenanceSelect("Repeat by", selectedTriggerLabel, triggerLabels) { index ->
                    trigger = triggerValues[index]
                }
                if (trigger != MaintenanceTriggerType.TIME) {
                    OutlinedTextField(
                        value = km, onValueChange = { km = it.filter(Char::isDigit) },
                        label = { Text("Every (km)") },
                        placeholder = { Text("5000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(if (asset.currentMileageKm != null) "Start from current mileage: %,d km".format(asset.currentMileageKm)
                        else "Enter a starting mileage in Advanced settings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (trigger != MaintenanceTriggerType.MILEAGE) {
                    OutlinedTextField(
                        value = months, onValueChange = { months = it.filter(Char::isDigit) },
                        label = { Text("Every (months)") },
                        placeholder = { Text("6") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Advanced settings", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(if (showAdvanced) "Hide" else "Show", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (showAdvanced) {
                    if (trigger != MaintenanceTriggerType.MILEAGE) {
                        OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Baseline date: ${baselineDate.format(maintenanceDateFormat)}")
                        }
                    }
                    if (trigger != MaintenanceTriggerType.TIME) {
                        OutlinedTextField(
                            value = baselineKm, onValueChange = { baselineKm = it.filter(Char::isDigit) },
                            label = { Text("Baseline mileage (km)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = months.toIntOrNull()
                val k = km.toLongOrNull()
                val base = baselineKm.toLongOrNull()
                if (title.isBlank() || (trigger != MaintenanceTriggerType.MILEAGE && (m == null || m <= 0)) ||
                    (trigger != MaintenanceTriggerType.TIME && (k == null || k <= 0)) ||
                    (trigger != MaintenanceTriggerType.TIME && base == null)) {
                    error = "Enter a name, valid interval and starting mileage where needed"
                } else {
                    save(AssetMaintenanceRuleEntity(
                        id = existing?.id ?: 0, assetId = asset.id, title = title.trim(), triggerType = trigger,
                        intervalMonths = m.takeIf { trigger != MaintenanceTriggerType.MILEAGE },
                        intervalKm = k.takeIf { trigger != MaintenanceTriggerType.TIME },
                        baselineDateEpochDay = baselineDate.toEpochDay().takeIf { trigger != MaintenanceTriggerType.MILEAGE },
                        baselineMileageKm = base.takeIf { trigger != MaintenanceTriggerType.TIME },
                        warningDays = existing?.warningDays ?: 30,
                        warningKm = existing?.warningKm ?: 1000,
                        notes = notes.trim().takeIf(String::isNotBlank),
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        kind = kind
                    ))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
    if (pickingDate) SpendWiseDatePickerDialog(baselineDate, { baselineDate = it; pickingDate = false }, { pickingDate = false })
}

@Composable private fun MaintenanceCompletionDialog(asset: AssetEntity, rules: List<AssetMaintenanceRuleEntity>, initialRule: AssetMaintenanceRuleEntity?,
    categories: List<CategoryEntity>, merchants: List<MerchantEntity>, pending: List<PendingAssetDocument>, busy: Boolean,
    onPickDocuments: () -> Unit, onRemoveDocument: (PendingAssetDocument) -> Unit, dismiss: () -> Unit,
    save: (MaintenanceEventInput) -> Unit) {
    var ruleId by remember(initialRule?.id) { mutableStateOf(initialRule?.id) }
    var title by remember(initialRule?.id) { mutableStateOf(initialRule?.title.orEmpty()) }
    var performed by remember { mutableStateOf(LocalDate.now()) }
    val initialMileage = asset.currentMileageKm?.toString().orEmpty()
    var mileageState by remember {
        mutableStateOf(TextFieldValue(text = initialMileage, selection = TextRange(0, initialMileage.length)))
    }
    var cost by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var createExpense by remember { mutableStateOf(false) }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val key = remember { UUID.randomUUID().toString() }
    val selectedRule = rules.firstOrNull { it.id == ruleId }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(when (selectedRule?.kind) {
        MaintenanceRuleKind.SERVICE_SCHEDULE -> "Complete scheduled service"
        MaintenanceRuleKind.MAINTENANCE_ITEM -> "Mark ${selectedRule.title} as done"
        null -> "Record maintenance"
    }) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MaintenanceSelect("Rule", selectedRule?.title ?: "Ad-hoc", listOf("Ad-hoc") + rules.map { it.title }) {
                ruleId = if (it == 0) null else rules[it - 1].id
                if (it > 0) title = rules[it - 1].title
            }
            OutlinedTextField(title, { title = it }, label = { Text("Work performed") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { pickingDate = true }) { Text("Performed: ${performed.format(maintenanceDateFormat)}") }
            OutlinedTextField(
                value = mileageState,
                onValueChange = { mileageState = it.copy(text = it.text.filter(Char::isDigit)) },
                label = { Text("Mileage (km)${if (selectedRule?.triggerType != null && selectedRule.triggerType != MaintenanceTriggerType.TIME) " required" else " optional"}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            if (asset.currentMileageKm != null && asset.currentMileageKm > 0) {
                Text(
                    "Current mileage: %,d km".format(asset.currentMileageKm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            val completedMileage = mileageState.text.toLongOrNull()
            error = when {
                title.isBlank() -> "Enter the work performed"
                cost.isNotBlank() && (amount == null || amount <= 0) -> "Enter a valid positive cost"
                mileageState.text.isNotBlank() && completedMileage == null -> "Enter a valid mileage"
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
