package com.example.spendwise.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.spendwise.viewmodel.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val assetDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun MyAssetsScreen(onAdd: () -> Unit, onOpen: (Long) -> Unit, viewModel: AssetsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("All") }
    val cards = state.cards.filter {
        when (filter) {
            "Vehicles" -> it.asset.type == AssetType.VEHICLE
            "Electronics" -> it.asset.type in setOf(AssetType.PHONE, AssetType.COMPUTER, AssetType.TABLET, AssetType.ELECTRONICS, AssetType.APPLIANCE)
            "Other" -> it.asset.type == AssetType.OTHER
            else -> true
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("My Assets", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(onClick = onAdd) { Text("Add Asset", maxLines = 1, softWrap = false) }
                }
                Text("Things you own and need to look after", modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Vehicles", "Electronics", "Other").forEach { label ->
                    item { FilterChip(filter == label, { filter = label }, label = { Text(label, maxLines = 1) }) }
                }
            }
        }
        if (cards.isEmpty()) item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text("No assets yet", fontWeight = FontWeight.SemiBold); TextButton(onClick = onAdd) { Text("Add your first asset") } } }
        }
        items(cards, key = { it.asset.id }) { card ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(card.asset.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(card.asset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(card.asset.type.label(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOfNotNull(card.asset.brand, card.asset.model).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" ")) }
                    card.attention?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
                    card.secondary?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAssetScreen(assetId: Long? = null, onSaved: (Long) -> Unit, onCancel: () -> Unit = {}, viewModel: AssetsViewModel = viewModel()) {
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val commitments by viewModel.commitments.collectAsStateWithLifecycle()
    val pendingDocuments by viewModel.pendingDocuments.collectAsStateWithLifecycle()
    val isStagingDocument by viewModel.isStagingDocument.collectAsStateWithLifecycle()
    var pendingDocumentUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingDocumentUri = uri
    }
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(AssetType.OTHER) }
    var brand by rememberSaveable { mutableStateOf("") }; var model by rememberSaveable { mutableStateOf("") }
    var purchaseDate by rememberSaveable { mutableStateOf<Long?>(null) }; var purchasePrice by rememberSaveable { mutableStateOf("") }
    var sellerId by rememberSaveable { mutableStateOf<Long?>(null) }; var sellerQuery by rememberSaveable { mutableStateOf("") }
    var mileage by rememberSaveable { mutableStateOf("") }; var notes by rememberSaveable { mutableStateOf("") }
    var identifierType by rememberSaveable { mutableStateOf(AssetIdentifierType.SERIAL_NUMBER) }; var identifierValue by rememberSaveable { mutableStateOf("") }
    var identifiers by remember { mutableStateOf(emptyList<PendingAssetIdentifier>()) }
    var identifierError by remember { mutableStateOf<String?>(null) }
    var hasWarranty by rememberSaveable { mutableStateOf(false) }; var warrantyName by rememberSaveable { mutableStateOf("Warranty") }
    var warrantyDates by remember { mutableStateOf(WarrantyDateDraft.new(null, LocalDate.now())) }
    var existingWarranty by remember { mutableStateOf<AssetWarrantyEntity?>(null) }
    var existingAsset by remember { mutableStateOf<AssetEntity?>(null) }
    var loading by remember { mutableStateOf(assetId != null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var commitmentId by rememberSaveable { mutableStateOf<Long?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }; var showErrors by rememberSaveable { mutableStateOf(false) }
    val sellerSuggestions = remember(sellerQuery, merchants) { MerchantRanker.rank(sellerQuery, merchants).take(5) }
    val now = System.currentTimeMillis()

    LaunchedEffect(assetId) {
        if (assetId != null) {
            viewModel.loadForEdit(assetId)?.let { snapshot ->
                val asset = snapshot.asset
                existingAsset = asset
                name = asset.name; type = asset.type; brand = asset.brand.orEmpty(); model = asset.model.orEmpty()
                purchaseDate = asset.purchaseDateEpochDay; purchasePrice = asset.purchasePriceMinor?.let(::minorToAssetInput).orEmpty()
                sellerId = asset.sellerMerchantId; mileage = asset.currentMileageKm?.toString().orEmpty(); notes = asset.notes.orEmpty()
                identifiers = snapshot.identifiers.map { PendingAssetIdentifier(it.type, it.value) }
                commitmentId = snapshot.commitmentLinks.firstOrNull { it.relationType == AssetCommitmentRelationType.FINANCING }?.commitmentId
                snapshot.warranties.firstOrNull()?.let { warranty ->
                    existingWarranty = warranty
                    hasWarranty = true
                    warrantyName = warranty.name
                    warrantyDates = WarrantyDateDraft.existing(warranty, purchaseDate)
                } ?: run { warrantyDates = WarrantyDateDraft.new(purchaseDate, LocalDate.now()) }
            }
        }
        loading = false
    }

    if (loading) { CircularProgressIndicator(Modifier.padding(24.dp)); return }

    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (assetId == null) "Add Asset" else "Edit Asset", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, isError = showErrors && name.isBlank(), modifier = Modifier.fillMaxWidth())
        AssetEnumDropdown("Type", type, AssetType.entries) { type = it }
        OutlinedTextField(brand, { brand = it }, label = { Text("Brand (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Model (optional)") }, modifier = Modifier.fillMaxWidth())
        Text("Purchase details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OptionalDateField("Purchase date", purchaseDate, { picker = "purchase" }) {
            purchaseDate = null
            warrantyDates = warrantyDates.withPurchaseDate(null, LocalDate.now())
        }
        MoneyInput("Purchase price (optional)", purchasePrice) { purchasePrice = it }
        OutlinedTextField(sellerQuery, { sellerQuery = it; sellerId = null }, label = { Text("Seller / merchant (optional)") }, modifier = Modifier.fillMaxWidth())
        if (sellerQuery.isNotBlank() && sellerId == null) InlineSuggestionList(
            sellerSuggestions.map { InlineSuggestion(it.merchant.id, it.merchant, it.merchant.displayName) },
            onSelected = { sellerId = it.id; sellerQuery = it.displayName }
        )
        if (type == AssetType.VEHICLE) OutlinedTextField(mileage, { mileage = it.filter(Char::isDigit) }, label = { Text("Current mileage (km, optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Text("Identifiers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        AssetEnumDropdown("Identifier type", identifierType, AssetIdentifierType.entries) { identifierType = it }
        OutlinedTextField(identifierValue, { identifierValue = it; identifierError = null }, label = { Text("Identifier value") }, isError = identifierError != null, modifier = Modifier.fillMaxWidth())
        identifierError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = {
            val result = AssetIdentifierDraftLogic.add(identifiers, identifierType, identifierValue)
            identifierError = result.error
            if (result.error == null) { identifiers = result.identifiers; identifierValue = "" }
        }, modifier = Modifier.fillMaxWidth()) { Text("Add identifier") }
        identifiers.forEachIndexed { index, identifier ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(identifier.type.label(), style = MaterialTheme.typography.labelMedium)
                        Text(identifier.value, style = MaterialTheme.typography.bodyLarge)
                    }
                    TextButton(onClick = { identifiers = AssetIdentifierDraftLogic.remove(identifiers, index); identifierError = null }) { Text("Remove") }
                }
            }
        }
        Text("Financing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        NullableCommitmentDropdown(commitmentId, commitments.commitments) { commitmentId = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Add warranty", Modifier.padding(top = 12.dp)); Switch(hasWarranty, {
            if (it && !hasWarranty && existingWarranty == null) warrantyDates = WarrantyDateDraft.new(purchaseDate, LocalDate.now())
            hasWarranty = it
        }) }
        if (hasWarranty) {
            OutlinedTextField(warrantyName, { warrantyName = it }, label = { Text("Warranty name") }, modifier = Modifier.fillMaxWidth())
            DateButton("Warranty start", LocalDate.ofEpochDay(warrantyDates.startDateEpochDay)) { picker = "warrantyStart" }
            DateButton("Warranty expiry", LocalDate.ofEpochDay(warrantyDates.endDateEpochDay)) { picker = "warrantyEnd" }
        }
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Text("Documents (optional)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Attach a purchase invoice, warranty card, purchase contract, or other document.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        pendingDocuments.forEach { document ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(document.title, fontWeight = FontWeight.SemiBold)
                        Text(document.originalFileName, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { viewModel.removePendingDocument(document) }, enabled = !saving) { Text("Remove") }
                }
            }
        }
        OutlinedButton(onClick = { documentPicker.launch(arrayOf("image/*", "application/pdf")) }, enabled = !saving && !isStagingDocument,
            modifier = Modifier.fillMaxWidth()) { Text(if (isStagingDocument) "Attaching document…" else if (pendingDocuments.isEmpty()) "+ Add document" else "+ Add another document") }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            showErrors = true
            val price = purchasePrice.takeIf(String::isNotBlank)?.let(::parseEgpToMinor)
            if (name.isBlank() || (purchasePrice.isNotBlank() && price == null) ||
                (hasWarranty && (warrantyName.isBlank() || warrantyDates.endDateEpochDay < warrantyDates.startDateEpochDay))) return@Button
            saving = true; saveError = null
            val asset = AssetEntity(id = existingAsset?.id ?: 0, name = name.trim(), type = type, brand = brand.clean(), model = model.clean(), purchaseDateEpochDay = purchaseDate,
                purchasePriceMinor = price, sellerMerchantId = sellerId, currentMileageKm = mileage.toLongOrNull(), notes = notes.clean(), createdAt = existingAsset?.createdAt ?: now, updatedAt = now)
            val warranty = if (hasWarranty) AssetWarrantyEntity(id = existingWarranty?.id ?: 0, assetId = existingAsset?.id ?: 0,
                name = warrantyName.trim(), type = existingWarranty?.type ?: AssetWarrantyType.OTHER, providerName = existingWarranty?.providerName,
                startDateEpochDay = warrantyDates.startDateEpochDay, endDateEpochDay = warrantyDates.endDateEpochDay,
                phone = existingWarranty?.phone, website = existingWarranty?.website, notes = existingWarranty?.notes,
                createdAt = existingWarranty?.createdAt ?: now, updatedAt = now) else null
            viewModel.save(NewAssetInput(asset, identifiers.map { AssetIdentifierEntity(assetId = asset.id, type = it.type, label = null, value = it.value) }, warranty, commitmentId),
                onSaved = { saving = false; onSaved(it) }, onError = { saving = false; saveError = it })
        }, Modifier.fillMaxWidth(), enabled = !saving && !isStagingDocument) { Text(if (saving) "Saving…" else "Save Asset") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth(), enabled = !saving && !isStagingDocument) { Text("Cancel") }
    }
    picker?.let { target ->
        val initial = when (target) { "purchase" -> purchaseDate ?: LocalDate.now().toEpochDay(); "warrantyStart" -> warrantyDates.startDateEpochDay; else -> warrantyDates.endDateEpochDay }
        SpendWiseDatePickerDialog(LocalDate.ofEpochDay(initial), { date ->
            when (target) {
                "purchase" -> { purchaseDate = date.toEpochDay(); warrantyDates = warrantyDates.withPurchaseDate(purchaseDate, LocalDate.now()) }
                "warrantyStart" -> warrantyDates = warrantyDates.withManualStart(date)
                else -> warrantyDates = warrantyDates.withManualEnd(date)
            }; picker = null
        }, { picker = null })
    }
    pendingDocumentUri?.let { uri ->
        DraftDocumentMetadataDialog(hasWarranty, { pendingDocumentUri = null }) { type, title ->
            viewModel.addPendingDocument(uri, type, title) { saveError = it }
            pendingDocumentUri = null
        }
    }
}

@Composable
fun AssetDetailScreen(onBack: () -> Unit, onEdit: (Long) -> Unit, onCommitment: (Long) -> Unit, viewModel: AssetDetailViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle(); val categories by viewModel.categories.collectAsStateWithLifecycle(); val commitments by viewModel.commitments.collectAsStateWithLifecycle(); val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }; var message by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<AssetDocumentEntity?>(null) }
    var pendingRename by remember { mutableStateOf<AssetDocumentEntity?>(null) }
    var pendingDocumentUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingDocumentUri = uri
    }
    val asset = state.asset
    if (asset == null) { Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Asset not found") }; return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) { Text(asset.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(asset.type.label()) }
            TextButton(onClick = { onEdit(asset.id) }) { Text("Edit", maxLines = 1) }
        } }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        item { DetailSection("Overview") { listOfNotNull(asset.brand, asset.model).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" ")) }; state.identifiers.forEach { Text("${it.label ?: it.type.label()}: ${it.value}") }; asset.currentMileageKm?.let { Text("Mileage: $it km") }; if (asset.type == AssetType.VEHICLE) TextButton(onClick = { dialog = "mileage" }) { Text("Update mileage") } } }
        item { DetailSection("Purchase") { Text(asset.purchaseDateEpochDay?.let { LocalDate.ofEpochDay(it).format(assetDateFormatter) } ?: "No purchase date"); asset.purchasePriceMinor?.let { Text(formatEgp(it)) }; asset.sellerMerchantId?.let { id -> merchants.firstOrNull { it.id == id }?.let { Text("Seller: ${it.displayName}") } } } }
        item { DetailSection("Warranty") { if (state.warranties.isEmpty()) Text("No warranties") else state.warranties.forEach { Text("${it.name}: ${WarrantyCalculator.remainingLabel(LocalDate.ofEpochDay(it.endDateEpochDay), LocalDate.now())}") }; TextButton(onClick = { dialog = "warranty" }) { Text("Add warranty") } } }
        item { DetailSection("Financing") { if (state.linkedCommitments.isEmpty()) Text("No linked commitments") else state.linkedCommitments.forEach { (link, commitment) -> TextButton(onClick = { onCommitment(commitment.commitment.id) }) { Text("${link.relationType.label()}: ${commitment.commitment.title} · ${formatEgp(commitment.commitment.amountMinor)}") } }; TextButton(onClick = { dialog = "link" }) { Text("Link commitment") } } }
        item { DetailSection("Maintenance") { if (state.rules.isEmpty()) Text("No maintenance schedules") else state.rules.forEach { rule -> Text("${rule.title}: ${state.dueByRule[rule.id]?.dueLabel()}") }; Row { TextButton(onClick = { dialog = "rule" }) { Text("Add schedule") }; TextButton(onClick = { dialog = "event" }) { Text("Record maintenance") } } } }
        item { DetailSection("Documents") { if (state.documents.isEmpty()) Text("No documents") else state.documents.forEach { doc -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(doc.title, fontWeight = FontWeight.SemiBold); Text(doc.originalFileName, style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = {
            val file = viewModel.documentFile(doc.storedRelativePath); if (file == null) message = "Stored file is missing" else runCatching { val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file); context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, doc.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { message = "No app can open this document" }
        }) { Text("View") }; TextButton(onClick = { pendingRename = doc }) { Text("Rename") }; TextButton(onClick = { pendingDelete = doc }) { Text("Delete") } } }; TextButton(onClick = { documentPicker.launch(arrayOf("image/*", "application/pdf")) }) { Text("Attach document") } } }
        item { DetailSection("History") { if (state.events.isEmpty()) Text("No maintenance history") else state.events.forEach { Text("${it.title} · ${LocalDate.ofEpochDay(it.performedDateEpochDay).format(assetDateFormatter)}${it.costMinor?.let { cost -> " · ${formatEgp(cost)}" }.orEmpty()}") } } }
        item { TextButton(onClick = { dialog = "archive" }, Modifier.fillMaxWidth()) { Text("Archive asset", color = MaterialTheme.colorScheme.error) }; Spacer(Modifier.height(16.dp)) }
    }
    when (dialog) {
        "mileage" -> MileageDialog(asset.currentMileageKm, { dialog = null }) { viewModel.updateMileage(it) { accepted -> message = if (accepted) "Mileage updated" else "New mileage is lower than current mileage" }; dialog = null }
        "warranty" -> WarrantyDialog(asset.id, asset.purchaseDateEpochDay, { dialog = null }) { viewModel.addWarranty(it); dialog = null }
        "rule" -> RuleDialog(asset, { dialog = null }) { viewModel.addRule(it); dialog = null }
        "event" -> MaintenanceDialog(asset, state.rules, categories, merchants, { dialog = null }) { viewModel.recordMaintenance(it) { message = "Maintenance recorded"; dialog = null } }
        "link" -> LinkCommitmentDialog(commitments.commitments, { dialog = null }) { viewModel.linkCommitment(it, AssetCommitmentRelationType.FINANCING); dialog = null }
        "archive" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Archive asset?") }, text = { Text("History and financial links will be preserved.") }, confirmButton = { TextButton(onClick = { viewModel.archive(onBack) }) { Text("Archive") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
    }
    pendingDelete?.let { doc -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete document?") }, text = { Text("The private stored copy will be permanently removed.") }, confirmButton = { TextButton(onClick = { viewModel.deleteDocument(doc.id); pendingDelete = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }) }
    pendingRename?.let { doc -> RenameDocumentDialog(doc.title, { pendingRename = null }) { viewModel.renameDocument(doc.id, it); pendingRename = null } }
    pendingDocumentUri?.let { uri -> DocumentMetadataDialog({ pendingDocumentUri = null }) { type, title -> viewModel.attachDocument(uri, type, title) { ok -> message = if (ok) "Document attached" else "Document could not be copied" }; pendingDocumentUri = null } }
}

@Composable private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); content() } } }

@Composable private fun MileageDialog(current: Long?, dismiss: () -> Unit, save: (Long) -> Unit) { var value by remember { mutableStateOf(current?.toString().orEmpty()) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Update mileage") }, text = { OutlinedTextField(value, { value = it.filter(Char::isDigit) }, label = { Text("Mileage (km)") }) }, confirmButton = { TextButton(onClick = { value.toLongOrNull()?.let(save) }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) }

@Composable private fun WarrantyDialog(assetId: Long, purchaseDate: Long?, dismiss: () -> Unit, save: (AssetWarrantyEntity) -> Unit) { var name by remember { mutableStateOf("Warranty") }; var type by remember { mutableStateOf(AssetWarrantyType.MANUFACTURER) }; var provider by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }; var website by remember { mutableStateOf("") }; var notes by remember { mutableStateOf("") }; var start by remember(purchaseDate) { mutableStateOf(purchaseDate?.let(LocalDate::ofEpochDay) ?: LocalDate.now()) }; var end by remember(start) { mutableStateOf(start.plusYears(1)) }; var pick by remember { mutableStateOf<String?>(null) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Add warranty") }, text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Name") }); AssetEnumDropdown("Type", type, AssetWarrantyType.entries) { type = it }; OutlinedTextField(provider, { provider = it }, label = { Text("Provider (optional)") }); OutlinedTextField(phone, { phone = it }, label = { Text("Phone (optional)") }); OutlinedTextField(website, { website = it }, label = { Text("Website (optional)") }); DateButton("Start", start) { pick = "start" }; DateButton("Expiry", end) { pick = "end" }; OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }) } }, confirmButton = { TextButton(onClick = { if (name.isNotBlank() && !end.isBefore(start)) { val now = System.currentTimeMillis(); save(AssetWarrantyEntity(assetId = assetId, name = name.trim(), type = type, providerName = provider.clean(), startDateEpochDay = start.toEpochDay(), endDateEpochDay = end.toEpochDay(), phone = phone.clean(), website = website.clean(), notes = notes.clean(), createdAt = now, updatedAt = now)) } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }); pick?.let { p -> SpendWiseDatePickerDialog(if (p == "start") start else end, { if (p == "start") start = it else end = it; pick = null }, { pick = null }) } }

@Composable private fun RuleDialog(asset: AssetEntity, dismiss: () -> Unit, save: (AssetMaintenanceRuleEntity) -> Unit) { var title by remember { mutableStateOf("") }; var trigger by remember { mutableStateOf(MaintenanceTriggerType.TIME) }; var months by remember { mutableStateOf("12") }; var km by remember { mutableStateOf("10000") }; AlertDialog(onDismissRequest = dismiss, title = { Text("Maintenance schedule") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Title") }); AssetEnumDropdown("Trigger", trigger, MaintenanceTriggerType.entries) { trigger = it }; if (trigger != MaintenanceTriggerType.MILEAGE) OutlinedTextField(months, { months = it.filter(Char::isDigit) }, label = { Text("Interval months") }); if (trigger != MaintenanceTriggerType.TIME) OutlinedTextField(km, { km = it.filter(Char::isDigit) }, label = { Text("Interval km") }) } }, confirmButton = { TextButton(onClick = { val m = months.toIntOrNull(); val k = km.toLongOrNull(); if (title.isNotBlank() && (trigger == MaintenanceTriggerType.MILEAGE || m != null) && (trigger == MaintenanceTriggerType.TIME || k != null)) { val now = System.currentTimeMillis(); save(AssetMaintenanceRuleEntity(assetId = asset.id, title = title.trim(), triggerType = trigger, intervalMonths = m.takeIf { trigger != MaintenanceTriggerType.MILEAGE }, intervalKm = k.takeIf { trigger != MaintenanceTriggerType.TIME }, baselineDateEpochDay = LocalDate.now().toEpochDay(), baselineMileageKm = asset.currentMileageKm, warningDays = 30, warningKm = 1000, notes = null, createdAt = now, updatedAt = now)) } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) }

@Composable private fun MaintenanceDialog(asset: AssetEntity, rules: List<AssetMaintenanceRuleEntity>, categories: List<CategoryEntity>, merchants: List<MerchantEntity>, dismiss: () -> Unit, save: (MaintenanceEventInput) -> Unit) { var title by remember { mutableStateOf("") }; var cost by remember { mutableStateOf("") }; var performed by remember { mutableStateOf(LocalDate.now()) }; var pickingDate by remember { mutableStateOf(false) }; var createExpense by remember { mutableStateOf(false) }; var categoryId by remember { mutableStateOf<Long?>(null) }; var merchantId by remember { mutableStateOf<Long?>(null) }; var ruleId by remember { mutableStateOf<Long?>(null) }; val key = remember { UUID.randomUUID().toString() }; AlertDialog(onDismissRequest = dismiss, title = { Text("Record maintenance") }, text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Work performed") }); DateButton("Performed", performed) { pickingDate = true }; NullableRuleDropdown(ruleId, rules) { ruleId = it }; MoneyInput("Cost (optional)", cost) { cost = it }; NullableMerchantDropdown(merchantId, merchants) { merchantId = it }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Create expense"); Switch(createExpense, { createExpense = it }) }; if (createExpense) NullableCategoryDropdown(categoryId, categories) { categoryId = it } } }, confirmButton = { TextButton(onClick = { val amount = cost.takeIf(String::isNotBlank)?.let(::parseEgpToMinor); val merchant = merchants.firstOrNull { it.id == merchantId }; if (title.isNotBlank() && (!createExpense || amount != null && categoryId != null)) save(MaintenanceEventInput(asset.id, ruleId, title, performed, asset.currentMileageKm, amount, merchantId, merchant?.displayName, categoryId, createExpense, null, key)) }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }); if (pickingDate) SpendWiseDatePickerDialog(performed, { performed = it; pickingDate = false }, { pickingDate = false }) }

@Composable private fun LinkCommitmentDialog(items: List<CommitmentWithMerchant>, dismiss: () -> Unit, save: (Long) -> Unit) { var selected by remember { mutableStateOf<Long?>(null) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Link financing commitment") }, text = { NullableCommitmentDropdown(selected, items) { selected = it } }, confirmButton = { TextButton(onClick = { selected?.let(save) }) { Text("Link") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) }

@Composable private fun RenameDocumentDialog(initial: String, dismiss: () -> Unit, save: (String) -> Unit) { var title by remember { mutableStateOf(initial) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Rename document") }, text = { OutlinedTextField(title, { title = it }, label = { Text("Title") }) }, confirmButton = { TextButton(onClick = { if (title.isNotBlank()) save(title) }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) }

@Composable private fun DocumentMetadataDialog(dismiss: () -> Unit, save: (AssetDocumentType, String) -> Unit) { var title by remember { mutableStateOf("") }; var type by remember { mutableStateOf(AssetDocumentType.OTHER) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Attach document") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { AssetEnumDropdown("Document type", type, AssetDocumentType.entries) { type = it }; OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }) } }, confirmButton = { TextButton(onClick = { save(type, title) }) { Text("Copy to SpendWise") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) }

@Composable
private fun DraftDocumentMetadataDialog(
    hasWarranty: Boolean,
    dismiss: () -> Unit,
    save: (AssetDocumentType, String) -> Unit
) {
    var type by remember { mutableStateOf(if (hasWarranty) AssetDocumentType.WARRANTY_CARD else AssetDocumentType.INVOICE) }
    var title by remember { mutableStateOf(type.displayTitle()) }
    var titleEdited by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Document details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AssetEnumDropdown("Document type", type, listOf(
                    AssetDocumentType.INVOICE, AssetDocumentType.WARRANTY_CARD,
                    AssetDocumentType.PURCHASE_CONTRACT, AssetDocumentType.OTHER
                )) { selected ->
                    type = selected
                    if (!titleEdited) title = selected.displayTitle()
                }
                OutlinedTextField(title, { title = it; titleEdited = true }, label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { save(type, title) }) { Text("Attach") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class) @Composable private fun <T : Enum<T>> AssetEnumDropdown(label: String, value: T, values: List<T>, select: (T) -> Unit) { var expanded by remember { mutableStateOf(false) }; ExposedDropdownMenuBox(expanded, { expanded = it }) { OutlinedTextField(value.label(), {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth()); ExposedDropdownMenu(expanded, { expanded = false }) { values.forEach { item -> DropdownMenuItem({ Text(item.label()) }, onClick = { select(item); expanded = false }) } } } }
@OptIn(ExperimentalMaterial3Api::class) @Composable private fun NullableCommitmentDropdown(value: Long?, items: List<CommitmentWithMerchant>, select: (Long?) -> Unit) { var expanded by remember { mutableStateOf(false) }; ExposedDropdownMenuBox(expanded, { expanded = it }) { OutlinedTextField(items.firstOrNull { it.commitment.id == value }?.commitment?.title ?: "None", {}, readOnly = true, label = { Text("Existing commitment") }, modifier = Modifier.menuAnchor().fillMaxWidth()); ExposedDropdownMenu(expanded, { expanded = false }) { DropdownMenuItem({ Text("None") }, onClick = { select(null); expanded = false }); items.forEach { item -> DropdownMenuItem({ Text(item.commitment.title) }, onClick = { select(item.commitment.id); expanded = false }) } } } }
@OptIn(ExperimentalMaterial3Api::class) @Composable private fun NullableRuleDropdown(value: Long?, items: List<AssetMaintenanceRuleEntity>, select: (Long?) -> Unit) { var expanded by remember { mutableStateOf(false) }; ExposedDropdownMenuBox(expanded, { expanded = it }) { OutlinedTextField(items.firstOrNull { it.id == value }?.title ?: "Ad-hoc maintenance", {}, readOnly = true, label = { Text("Schedule") }, modifier = Modifier.menuAnchor().fillMaxWidth()); ExposedDropdownMenu(expanded, { expanded = false }) { DropdownMenuItem({ Text("Ad-hoc maintenance") }, onClick = { select(null); expanded = false }); items.forEach { item -> DropdownMenuItem({ Text(item.title) }, onClick = { select(item.id); expanded = false }) } } } }
@OptIn(ExperimentalMaterial3Api::class) @Composable private fun NullableCategoryDropdown(value: Long?, items: List<CategoryEntity>, select: (Long?) -> Unit) { var expanded by remember { mutableStateOf(false) }; ExposedDropdownMenuBox(expanded, { expanded = it }) { OutlinedTextField(items.firstOrNull { it.id == value }?.name ?: "Select category", {}, readOnly = true, label = { Text("Expense category") }, modifier = Modifier.menuAnchor().fillMaxWidth()); ExposedDropdownMenu(expanded, { expanded = false }) { items.forEach { item -> DropdownMenuItem({ Text(item.name) }, onClick = { select(item.id); expanded = false }) } } } }
@OptIn(ExperimentalMaterial3Api::class) @Composable private fun NullableMerchantDropdown(value: Long?, items: List<MerchantEntity>, select: (Long?) -> Unit) { var expanded by remember { mutableStateOf(false) }; ExposedDropdownMenuBox(expanded, { expanded = it }) { OutlinedTextField(items.firstOrNull { it.id == value }?.displayName ?: "No service provider", {}, readOnly = true, label = { Text("Service provider") }, modifier = Modifier.menuAnchor().fillMaxWidth()); ExposedDropdownMenu(expanded, { expanded = false }) { DropdownMenuItem({ Text("None") }, onClick = { select(null); expanded = false }); items.take(30).forEach { item -> DropdownMenuItem({ Text(item.displayName) }, onClick = { select(item.id); expanded = false }) } } } }
@Composable private fun MoneyInput(label: String, value: String, change: (String) -> Unit) = OutlinedTextField(value, change, label = { Text(label) }, suffix = { Text("EGP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
@Composable private fun DateButton(label: String, date: LocalDate, click: () -> Unit) = OutlinedButton(click, Modifier.fillMaxWidth()) { Text("$label: ${date.format(assetDateFormatter)}", Modifier.weight(1f)); Icon(Icons.Outlined.DateRange, label) }
@Composable private fun OptionalDateField(label: String, epoch: Long?, choose: () -> Unit, clear: () -> Unit) { if (epoch == null) OutlinedButton(choose, Modifier.fillMaxWidth()) { Text("Add $label") } else Row { OutlinedButton(choose, Modifier.weight(1f)) { Text(LocalDate.ofEpochDay(epoch).format(assetDateFormatter)) }; TextButton(onClick = clear) { Text("Clear") } } }
private fun String.clean() = trim().takeIf(String::isNotBlank)
private fun minorToAssetInput(minor: Long): String = if (minor % 100 == 0L) (minor / 100).toString()
    else "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"
private fun Enum<*>.label() = if (this is AssetDocumentType) displayTitle()
    else name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
private fun MaintenanceDueResult.dueLabel(): String = when (triggerReason) { MaintenanceTriggerReason.MILEAGE -> remainingKm?.let { if (it < 0) "overdue by ${-it} km" else "due in $it km" } ?: "Mileage unavailable"; else -> remainingDays?.let { if (it < 0) "overdue by ${-it} days" else "due in $it days" } ?: "Date unavailable" }
