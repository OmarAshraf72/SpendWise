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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.*
import com.example.spendwise.viewmodel.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

private val assetDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun MyAssetsScreen(onAdd: () -> Unit, onOpen: (Long) -> Unit, viewModel: AssetsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val customTypes by viewModel.customTypes.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("All") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val cards = state.cards.filter { if (showHistory) it.asset.ownershipStatus != OwnershipStatus.OWNED else it.asset.ownershipStatus == OwnershipStatus.OWNED }.filter {
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
                    Text("My Items", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(onClick = onAdd) { Text("Add Item", maxLines = 1, softWrap = false) }
                }
                Text("Things you own and need to look after", modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!showHistory, { showHistory = false }, label = { Text("Owned") })
                FilterChip(showHistory, { showHistory = true }, label = { Text("History") })
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
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text(if (showHistory) "No past items" else "No items yet", fontWeight = FontWeight.SemiBold); if (!showHistory) TextButton(onClick = onAdd) { Text("Add your first item") } } }
        }
        items(cards, key = { it.asset.id }) { card ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(card.asset.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(card.asset.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(itemTypeLabel(card.asset.type, card.asset.customTypeId, customTypes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (showHistory) Text(card.asset.ownershipStatus.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase))
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
fun AddAssetScreen(assetId: Long? = null, transactionId: Long? = null, onSaved: (Long) -> Unit, onCancel: () -> Unit = {}, viewModel: AssetsViewModel = viewModel()) {
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val customTypes by viewModel.customTypes.collectAsStateWithLifecycle()
    val commitments by viewModel.commitments.collectAsStateWithLifecycle()
    val pendingDocuments by viewModel.pendingDocuments.collectAsStateWithLifecycle()
    val isStagingDocument by viewModel.isStagingDocument.collectAsStateWithLifecycle()
    var pendingDocumentUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingDocumentUri = uri
    }
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(AssetType.OTHER) }
    var customTypeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var createCustomType by remember { mutableStateOf(false) }
    var manageCustomTypes by remember { mutableStateOf(false) }
    var customTypeError by remember { mutableStateOf<String?>(null) }
    var linkedPurchase by remember { mutableStateOf<AssetPurchasePrefill?>(null) }
    var brand by rememberSaveable { mutableStateOf("") }; var model by rememberSaveable { mutableStateOf("") }
    var purchaseDate by rememberSaveable { mutableStateOf<Long?>(null) }; var purchasePrice by rememberSaveable { mutableStateOf("") }
    var sellerId by rememberSaveable { mutableStateOf<Long?>(null) }; var sellerQuery by rememberSaveable { mutableStateOf("") }
    var mileage by rememberSaveable { mutableStateOf("") }; var notes by rememberSaveable { mutableStateOf("") }
    var identifierType by rememberSaveable { mutableStateOf(AssetIdentifierType.SERIAL_NUMBER) }; var identifierValue by rememberSaveable { mutableStateOf("") }
    var identifiers by remember { mutableStateOf(emptyList<PendingAssetIdentifier>()) }
    var existingIdentifiers by remember { mutableStateOf(emptyList<AssetIdentifierEntity>()) }
    var identifierError by remember { mutableStateOf<String?>(null) }
    var hasWarranty by rememberSaveable { mutableStateOf(false) }; var warrantyName by rememberSaveable { mutableStateOf("Warranty") }
    var warrantyDates by remember { mutableStateOf(WarrantyDateDraft.new(null, LocalDate.now())) }
    var existingWarranty by remember { mutableStateOf<AssetWarrantyEntity?>(null) }
    var existingAsset by remember { mutableStateOf<AssetEntity?>(null) }
    var loading by remember { mutableStateOf(assetId != null || transactionId != null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var commitmentId by rememberSaveable { mutableStateOf<Long?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }; var showErrors by rememberSaveable { mutableStateOf(false) }
    val sellerSuggestions = remember(sellerQuery, merchants) { MerchantRanker.rank(sellerQuery, merchants) }
    val now = System.currentTimeMillis()

    LaunchedEffect(assetId, transactionId) {
        if (assetId != null) {
            viewModel.loadForEdit(assetId)?.let { snapshot ->
                val asset = snapshot.asset
                existingAsset = asset
                name = asset.name; type = asset.type; customTypeId = asset.customTypeId; categoryId = asset.categoryId
                brand = asset.brand.orEmpty(); model = asset.model.orEmpty()
                purchaseDate = asset.purchaseDateEpochDay; purchasePrice = asset.purchasePriceMinor?.let(::minorToAssetInput).orEmpty()
                sellerId = asset.sellerMerchantId; mileage = asset.currentMileageKm?.toString().orEmpty(); notes = asset.notes.orEmpty()
                identifiers = snapshot.identifiers.map { PendingAssetIdentifier(it.type, it.value) }
                existingIdentifiers = snapshot.identifiers
                commitmentId = snapshot.commitmentLinks.firstOrNull { it.relationType == AssetCommitmentRelationType.FINANCING }?.commitmentId
                snapshot.warranties.firstOrNull()?.let { warranty ->
                    existingWarranty = warranty
                    hasWarranty = true
                    warrantyName = warranty.name
                    warrantyDates = WarrantyDateDraft.existing(warranty, purchaseDate)
                } ?: run { warrantyDates = WarrantyDateDraft.new(purchaseDate, LocalDate.now()) }
            }
        } else if (transactionId != null) {
            linkedPurchase = viewModel.loadPurchasePrefill(transactionId)
            linkedPurchase?.let { purchase ->
                purchaseDate = purchase.purchaseDateEpochDay
                purchasePrice = minorToAssetInput(purchase.amountMinor)
                sellerId = purchase.merchantId
                sellerQuery = purchase.merchantName.orEmpty()
                categoryId = purchase.categoryId
                warrantyDates = WarrantyDateDraft.new(purchaseDate, LocalDate.now())
            }
        }
        loading = false
    }

    LaunchedEffect(sellerId, merchants) {
        if (sellerQuery.isBlank()) sellerId?.let { id -> sellerQuery = merchants.firstOrNull { it.id == id }?.displayName.orEmpty() }
    }
    LaunchedEffect(categories, linkedPurchase) {
        if (existingAsset == null && categoryId != null && categories.firstOrNull { it.id == categoryId }?.isArchived == true)
            categoryId = null
    }

    if (loading) { CircularProgressIndicator(Modifier.padding(24.dp)); return }

    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (assetId == null) "Add Item" else "Edit Item", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, isError = showErrors && name.isBlank(), modifier = Modifier.fillMaxWidth())
        ItemTypeDropdown(type, customTypeId, customTypes.filter { !it.isArchived || it.id == customTypeId },
            onSelect = { selectedType, customId -> type = selectedType; customTypeId = customId },
            onCreate = { createCustomType = true })
        TextButton(onClick = { manageCustomTypes = true }) { Text("Manage custom types") }
        ItemCategoryDropdown(categoryId, selectableItemCategories(categories, if (existingAsset != null) categoryId else null)) { categoryId = it }
        if (showErrors && categoryId == null && existingAsset == null) Text("Choose a category", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(brand, { brand = it }, label = { Text("Brand (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Model (optional)") }, modifier = Modifier.fillMaxWidth())
        Text("Purchase details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (transactionId != null && linkedPurchase == null) {
            Text("This transaction cannot be linked as an item purchase. Split purchases are not supported yet.", color = MaterialTheme.colorScheme.error)
        } else if (linkedPurchase != null) {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Linked to purchase transaction", fontWeight = FontWeight.SemiBold)
                Text("${purchaseDate?.let { LocalDate.ofEpochDay(it).format(assetDateFormatter) } ?: "No date"} · ${purchasePrice} EGP")
                sellerQuery.takeIf(String::isNotBlank)?.let { Text(it) }
                Text("This item will not create another expense.", style = MaterialTheme.typography.bodySmall)
            } }
        } else {
            OptionalDateField("Purchase date", purchaseDate, { picker = "purchase" }) {
                purchaseDate = null
                warrantyDates = warrantyDates.withPurchaseDate(null, LocalDate.now())
            }
            MoneyInput("Purchase price (optional)", purchasePrice) { purchasePrice = it }
            OutlinedTextField(sellerQuery, { sellerQuery = it; sellerId = null }, label = { Text("Seller / merchant (optional)") }, modifier = Modifier.fillMaxWidth())
            if (sellerQuery.isNotBlank() && sellerId == null) InlineSuggestionList(
                sellerSuggestions.map { InlineSuggestion(it.merchant.id, it.merchant, it.merchant.displayName) },
                onSelected = { sellerId = it.id; sellerQuery = it.displayName },
                emptyMessage = if (sellerQuery.isNotBlank() && sellerSuggestions.isEmpty()) "No matching merchants" else null
            )
        }
        val capabilities = itemCapabilities(type, customTypeId)
        if (capabilities.mileage) OutlinedTextField(mileage, { mileage = it.filter(Char::isDigit) }, label = { Text("Current mileage (km, optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        if (capabilities.identifiers) {
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
                        Text(identifier.value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { identifiers = AssetIdentifierDraftLogic.remove(identifiers, index); identifierError = null }) { Text("Remove") }
                }
            }
        }
        }
        if (capabilities.maintenance) {
            Text("Financing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            NullableCommitmentDropdown(commitmentId, commitments.commitments) { commitmentId = it }
        }
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
        Text("Attach a receipt, purchase invoice, warranty card, or other document.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        pendingDocuments.forEach { document ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(document.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(document.originalFileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            if (name.isBlank() || (existingAsset == null && categoryId == null) ||
                (transactionId != null && linkedPurchase == null) ||
                (purchasePrice.isNotBlank() && price == null) ||
                (hasWarranty && (warrantyName.isBlank() || warrantyDates.endDateEpochDay < warrantyDates.startDateEpochDay))) return@Button
            saving = true; saveError = null
            val asset = AssetEntity(id = existingAsset?.id ?: 0, name = name.trim(), type = type, brand = brand.clean(), model = model.clean(), purchaseDateEpochDay = purchaseDate,
                purchasePriceMinor = price, sellerMerchantId = sellerId, currentMileageKm = if (capabilities.mileage) mileage.toLongOrNull() else existingAsset?.currentMileageKm, notes = notes.clean(), createdAt = existingAsset?.createdAt ?: now, updatedAt = now,
                mileageUpdatedAt = if (capabilities.mileage && mileage.isNotBlank() && mileage.toLongOrNull() != existingAsset?.currentMileageKm) now else existingAsset?.mileageUpdatedAt,
                customTypeId = customTypeId, categoryId = categoryId, ownershipStatus = existingAsset?.ownershipStatus ?: OwnershipStatus.OWNED)
            val warranty = if (hasWarranty) AssetWarrantyEntity(id = existingWarranty?.id ?: 0, assetId = existingAsset?.id ?: 0,
                name = warrantyName.trim(), type = existingWarranty?.type ?: AssetWarrantyType.OTHER, providerName = existingWarranty?.providerName,
                startDateEpochDay = warrantyDates.startDateEpochDay, endDateEpochDay = warrantyDates.endDateEpochDay,
                phone = existingWarranty?.phone, website = existingWarranty?.website, notes = existingWarranty?.notes,
                createdAt = existingWarranty?.createdAt ?: now, updatedAt = now) else null
            val savedIdentifiers = if (capabilities.identifiers) identifiers.map { AssetIdentifierEntity(assetId = asset.id, type = it.type, label = null, value = it.value) }
                else existingIdentifiers
            viewModel.save(NewAssetInput(asset, savedIdentifiers, warranty, commitmentId, linkedPurchase?.transactionId,
                sellerQuery.clean()),
                onSaved = { saving = false; onSaved(it) }, onError = { saving = false; saveError = it })
        }, Modifier.fillMaxWidth(), enabled = !saving && !isStagingDocument) { Text(if (saving) "Saving…" else "Save Item") }
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
    if (createCustomType) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { createCustomType = false }, title = { Text("Create item type") },
            text = { Column {
                OutlinedTextField(draft, { draft = it; customTypeError = null }, label = { Text("Type name") }, singleLine = true)
                customTypeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = {
                viewModel.createCustomType(draft) { id, error ->
                    if (id != null) { type = AssetType.OTHER; customTypeId = id; createCustomType = false }
                    else customTypeError = error
                }
            }, enabled = draft.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton(onClick = { createCustomType = false }) { Text("Cancel") } })
    }
    if (manageCustomTypes) AlertDialog(onDismissRequest = { manageCustomTypes = false }, title = { Text("Custom item types") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            customTypes.filter { !it.isArchived }.forEach { custom ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(custom.name, Modifier.weight(1f))
                    TextButton(onClick = { viewModel.archiveCustomType(custom.id) { customTypeError = it } }) { Text("Archive") }
                }
            }
            customTypeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { manageCustomTypes = false }) { Text("Done") } })
}

@Composable
fun AssetDetailScreen(onBack: () -> Unit, onEdit: (Long) -> Unit, onCommitment: (Long) -> Unit,
                      onMaintenance: (Long, Boolean, Long?) -> Unit, onTransaction: (Long) -> Unit = {},
                      viewModel: AssetDetailViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle(); val categories by viewModel.categories.collectAsStateWithLifecycle(); val commitments by viewModel.commitments.collectAsStateWithLifecycle(); val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val customTypes by viewModel.customTypes.collectAsStateWithLifecycle()
    val allCategories by viewModel.allCategories.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val reminderRules by viewModel.reminderRules.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }; var message by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<AssetDocumentEntity?>(null) }
    var pendingRename by remember { mutableStateOf<AssetDocumentEntity?>(null) }
    var pendingDocumentUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var editingCheckpoint by remember { mutableStateOf<AssetCheckpointEntity?>(null) }
    var completingCheckpoint by remember { mutableStateOf<AssetCheckpointEntity?>(null) }
    var reminderTarget by remember { mutableStateOf<AssetAttentionItem?>(null) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingDocumentUri = uri
    }
    val asset = state.asset
    if (asset == null) { Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Item not found") }; return }
    val capabilities = itemCapabilities(asset.type, asset.customTypeId)
    val linkedPurchase = transactions.filter { it.transaction.id in state.purchaseTransactionIds }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) { Text(asset.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(itemTypeLabel(asset.type, asset.customTypeId, customTypes) +
                    (allCategories.firstOrNull { it.id == asset.categoryId }?.let { " · ${it.name}" } ?: " · Uncategorized")) }
            TextButton(onClick = { onEdit(asset.id) }) { Text("Edit", maxLines = 1) }
        } }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (asset.brand != null || asset.model != null || asset.notes != null ||
            capabilities.identifiers && state.identifiers.isNotEmpty() || capabilities.mileage) item {
            DetailSection("Overview") {
                listOfNotNull(asset.brand, asset.model).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" ")) }
                if (capabilities.identifiers) state.identifiers.forEach {
                    Text("${it.label ?: it.type.label()}: ${it.value}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                asset.notes?.let { Text(it) }
                if (capabilities.mileage) {
                    asset.currentMileageKm?.let { Text("Mileage: %,d km".format(it)) }
                    asset.mileageUpdatedAt?.let { updated ->
                        val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(updated)
                            .atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now())
                        if (days >= 30) Text("Mileage last updated $days days ago", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { dialog = "mileage" }) { Text("Update mileage") }
                }
            }
        }
        item { DetailSection("Purchase") { Text(asset.purchaseDateEpochDay?.let { LocalDate.ofEpochDay(it).format(assetDateFormatter) } ?: "No purchase date"); asset.purchasePriceMinor?.let { Text(formatEgp(it)) }; val seller = asset.sellerMerchantId?.let { id -> merchants.firstOrNull { it.id == id }?.displayName } ?: linkedPurchase.firstOrNull()?.transaction?.merchant; seller?.let { Text("Seller: $it") } } }
        if (linkedPurchase.isNotEmpty()) item { DetailSection("Purchase transaction") {
            linkedPurchase.forEach { row ->
                Text("${row.transaction.merchant ?: "Expense"} · ${formatEgp(row.transaction.amountMinor)}")
                TextButton(onClick = { onTransaction(row.transaction.id) }) { Text("View transaction") }
            }
        } }
        item { DetailSection("Warranty") { if (state.warranties.isEmpty()) Text("No warranties") else state.warranties.forEach { warranty -> Text("${warranty.name}: ${WarrantyCalculator.remainingLabel(LocalDate.ofEpochDay(warranty.endDateEpochDay), LocalDate.now())}"); state.attention.firstOrNull { it.sourceType == AssetAttentionSource.WARRANTY && it.sourceId == warranty.id }?.let { item -> TextButton(onClick = { reminderTarget = item }) { Text("Reminders") } } }; TextButton(onClick = { dialog = "warranty" }) { Text("Add warranty") } } }
        if (capabilities.maintenance) item { DetailSection("Financing") { if (state.linkedCommitments.isEmpty()) Text("No linked commitments") else state.linkedCommitments.forEach { (link, commitment) -> TextButton(onClick = { onCommitment(commitment.commitment.id) }) { Text("${link.relationType.label()}: ${commitment.commitment.title} · ${formatEgp(commitment.commitment.amountMinor)}") } }; TextButton(onClick = { dialog = "link" }) { Text("Link commitment") } } }
        if (capabilities.maintenance) item { DetailSection("Maintenance") {
            val priority = compareBy<AssetMaintenanceRuleEntity> { state.dueByRule[it.id]?.status?.ordinal ?: -1 }
                .thenBy { -(state.dueByRule[it.id]?.remainingDays ?: Long.MAX_VALUE) }
            val relevant = MaintenanceRuleKind.entries.mapNotNull { kind ->
                state.rules.filter { it.isActive && it.kind == kind }.maxWithOrNull(priority)
            }
            if (relevant.isEmpty()) {
                Text("No maintenance plan", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                relevant.forEach { rule ->
                    Text(rule.title, fontWeight = FontWeight.SemiBold)
                    state.dueByRule[rule.id]?.let { due ->
                        val nextDueText = buildString {
                            if (due.nextDueMileageKm != null) append("Next at %,d km".format(due.nextDueMileageKm))
                            else if (due.nextDueDate != null) {
                                if (due.remainingDays != null && due.remainingDays >= 0 && due.remainingDays <= 30) {
                                    append("Due in ${due.remainingDays} days")
                                } else {
                                    append("Next on ${due.nextDueDate.format(assetDateFormatter)}")
                                }
                            } else append("Next due unavailable")
                        }
                        Text(nextDueText, style = MaterialTheme.typography.bodyMedium)
                        val statusText = when (due.status) {
                            MaintenanceDueStatus.OVERDUE -> "Overdue"
                            MaintenanceDueStatus.DUE -> "Due"
                            MaintenanceDueStatus.DUE_SOON -> "Due soon"
                            MaintenanceDueStatus.OK -> "On track"
                        }
                        val statusColor = when (due.status) {
                            MaintenanceDueStatus.OVERDUE -> MaterialTheme.colorScheme.error
                            MaintenanceDueStatus.DUE, MaintenanceDueStatus.DUE_SOON -> MaterialTheme.colorScheme.primary
                            MaintenanceDueStatus.OK -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor, fontWeight = FontWeight.Medium)
                    }
                    TextButton(
                        onClick = { onMaintenance(asset.id, true, rule.id) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Mark as done")
                    }
                    HorizontalDivider()
                }
            }
            TextButton(
                onClick = { onMaintenance(asset.id, false, null) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(if (relevant.isEmpty()) "Set up maintenance" else "View maintenance", maxLines = 1)
            }
        } }
        if (capabilities.maintenance) item { DetailSection("Checkpoints") {
            if (state.checkpoints.isEmpty()) Text("No checkpoints yet")
            state.checkpoints.forEach { checkpoint ->
                Text(checkpoint.title, style = MaterialTheme.typography.titleMedium)
                val attention = state.attention.firstOrNull { it.sourceType == AssetAttentionSource.CHECKPOINT && it.sourceId == checkpoint.id }
                Text(attention?.message ?: "Completed", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (checkpoint.isActive) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { editingCheckpoint = checkpoint; dialog = "checkpoint" }) { Text("Edit", maxLines = 1) }
                        TextButton(onClick = { completingCheckpoint = checkpoint }) { Text("Mark completed", maxLines = 1) }
                        attention?.let { item -> TextButton(onClick = { reminderTarget = item }) { Text("Reminders", maxLines = 1) } }
                    }
                }
                state.checkpointEvents.filter { it.checkpointId == checkpoint.id }.forEach { event ->
                    Text("Due ${event.dueDateEpochDay?.let { LocalDate.ofEpochDay(it).format(assetDateFormatter) } ?: event.dueMileageKm?.let { "$it km" } ?: "—"} · Completed ${LocalDate.ofEpochDay(event.completedDateEpochDay).format(assetDateFormatter)}${event.completedMileageKm?.let { " · $it km" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { editingCheckpoint = null; dialog = "checkpoint" }) { Text("Add checkpoint") }
        } }
        item { DetailSection("Documents") { if (state.documents.isEmpty()) Text("No documents") else state.documents.forEach { doc -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(doc.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(doc.originalFileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }; TextButton(onClick = {
            val file = viewModel.documentFile(doc.storedRelativePath); if (file == null) message = "Stored file is missing" else runCatching { val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file); context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, doc.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { message = "No app can open this document" }
        }) { Text("View") }; TextButton(onClick = { pendingRename = doc }) { Text("Rename") }; if (doc.id !in state.maintenanceDocumentIds) TextButton(onClick = { pendingDelete = doc }) { Text("Delete") } } }; TextButton(onClick = { documentPicker.launch(arrayOf("image/*", "application/pdf")) }) { Text("Attach document") } } }
        item { DetailSection("Status") { AssetEnumDropdown("Ownership", asset.ownershipStatus, OwnershipStatus.entries) { viewModel.setOwnershipStatus(it) } } }
        item { TextButton(onClick = { dialog = "archive" }, Modifier.fillMaxWidth()) { Text("Archive item", color = MaterialTheme.colorScheme.error) }; Spacer(Modifier.height(16.dp)) }
    }
    when (dialog) {
        "mileage" -> MileageDialog(asset.currentMileageKm, { dialog = null }) { viewModel.updateMileage(it) { accepted -> message = if (accepted) "Mileage updated" else "New mileage is lower than current mileage" }; dialog = null }
        "warranty" -> WarrantyDialog(asset.id, asset.purchaseDateEpochDay, { dialog = null }) { viewModel.addWarranty(it); dialog = null }
        "checkpoint" -> AssetCheckpointDialog(asset, editingCheckpoint,
            editingCheckpoint?.let { checkpoint ->
                AssetAttentionEngine.checkpointOccurrence(checkpoint, state.checkpointEvents
                    .filter { it.checkpointId == checkpoint.id }
                    .maxWithOrNull(compareBy<AssetCheckpointEventEntity> { it.completedDateEpochDay }.thenBy { it.createdAt }))
            }, { dialog = null }) { checkpoint ->
            viewModel.saveCheckpoint(checkpoint) { message = "Checkpoint saved"; dialog = null }
        }
        "link" -> LinkCommitmentDialog(commitments.commitments, { dialog = null }) { viewModel.linkCommitment(it, AssetCommitmentRelationType.FINANCING); dialog = null }
        "archive" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Archive item?") }, text = { Text("History and financial links will be preserved.") }, confirmButton = { TextButton(onClick = { viewModel.archive(onBack) }) { Text("Archive") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } })
    }
    pendingDelete?.let { doc -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete document?") }, text = { Text("The private stored copy will be permanently removed.") }, confirmButton = { TextButton(onClick = { viewModel.deleteDocument(doc.id); pendingDelete = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }) }
    pendingRename?.let { doc -> RenameDocumentDialog(doc.title, { pendingRename = null }) { viewModel.renameDocument(doc.id, it); pendingRename = null } }
    pendingDocumentUri?.let { uri -> DocumentMetadataDialog(capabilities.maintenance, { pendingDocumentUri = null }) { type, title -> viewModel.attachDocument(uri, type, title) { ok -> message = if (ok) "Document attached" else "Document could not be copied" }; pendingDocumentUri = null } }
    completingCheckpoint?.let { checkpoint -> CompleteCheckpointDialog(checkpoint, asset.currentMileageKm,
        dismiss = { completingCheckpoint = null }) { date, mileage, note ->
        viewModel.completeCheckpoint(checkpoint.id, date, mileage, note) { message = "Checkpoint completed"; completingCheckpoint = null }
    } }
    reminderTarget?.let { target ->
        val defaults = when (target.sourceType) {
            AssetAttentionSource.WARRANTY -> 30 to null
            AssetAttentionSource.MAINTENANCE -> state.rules.firstOrNull { it.id == target.sourceId }?.let { it.warningDays to it.warningKm } ?: (null to null)
            AssetAttentionSource.CHECKPOINT -> state.checkpoints.firstOrNull { it.id == target.sourceId }?.let { it.warningDays to it.warningKm } ?: (null to null)
        }
        AssetReminderRulesDialog(target, reminderRules.filter { it.sourceType == target.sourceType && it.sourceId == target.sourceId },
            defaults.first, defaults.second, { reminderTarget = null }) { available, enabled ->
            viewModel.configureReminderRules(target.sourceType, target.sourceId, available, enabled) { reminderTarget = null }
        }
    }
}

@Composable private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); content() } } }

@Composable private fun MileageDialog(current: Long?, dismiss: () -> Unit, save: (Long) -> Unit) {
    val initialText = current?.toString().orEmpty()
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = initialText, selection = TextRange(0, initialText.length)))
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Update mileage") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current != null && current > 0) {
                    Text(
                        "Current mileage: %,d km".format(current),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it.copy(text = it.text.filter(Char::isDigit)) },
                    label = { Text("New mileage (km)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                textFieldValue.text.toLongOrNull()?.let(save)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
}

@Composable private fun WarrantyDialog(assetId: Long, purchaseDate: Long?, dismiss: () -> Unit, save: (AssetWarrantyEntity) -> Unit) { var name by remember { mutableStateOf("Warranty") }; var type by remember { mutableStateOf(AssetWarrantyType.MANUFACTURER) }; var provider by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }; var website by remember { mutableStateOf("") }; var notes by remember { mutableStateOf("") }; var start by remember(purchaseDate) { mutableStateOf(purchaseDate?.let(LocalDate::ofEpochDay) ?: LocalDate.now()) }; var end by remember(start) { mutableStateOf(start.plusYears(1)) }; var pick by remember { mutableStateOf<String?>(null) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Add warranty") }, text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Name") }); AssetEnumDropdown("Type", type, AssetWarrantyType.entries) { type = it }; OutlinedTextField(provider, { provider = it }, label = { Text("Provider (optional)") }); OutlinedTextField(phone, { phone = it }, label = { Text("Phone (optional)") }); OutlinedTextField(website, { website = it }, label = { Text("Website (optional)") }); DateButton("Start", start) { pick = "start" }; DateButton("Expiry", end) { pick = "end" }; OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }) } }, confirmButton = { TextButton(onClick = { if (name.isNotBlank() && !end.isBefore(start)) { val now = System.currentTimeMillis(); save(AssetWarrantyEntity(assetId = assetId, name = name.trim(), type = type, providerName = provider.clean(), startDateEpochDay = start.toEpochDay(), endDateEpochDay = end.toEpochDay(), phone = phone.clean(), website = website.clean(), notes = notes.clean(), createdAt = now, updatedAt = now)) } }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }); pick?.let { p -> SpendWiseDatePickerDialog(if (p == "start") start else end, { if (p == "start") start = it else end = it; pick = null }, { pick = null }) } }

@Composable private fun RuleDialog(asset: AssetEntity, dismiss: () -> Unit, save: (AssetMaintenanceRuleEntity) -> Unit) {
    var title by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf(MaintenanceTriggerType.TIME_OR_MILEAGE) }
    var months by remember { mutableStateOf("") }
    var km by remember { mutableStateOf("") }
    val triggerValues = listOf(MaintenanceTriggerType.TIME_OR_MILEAGE, MaintenanceTriggerType.MILEAGE, MaintenanceTriggerType.TIME)

    AlertDialog(onDismissRequest = dismiss, title = { Text("Maintenance schedule") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Suggested maintenance", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("Engine oil", "Oil filter", "Air filter", "Cabin filter", "Brakes", "Tires", "Battery", "Coolant", "Transmission service")) { suggestion ->
                    OutlinedButton(onClick = { title = suggestion }) { Text(suggestion, maxLines = 1) }
                }
            }
            OutlinedTextField(title, { title = it }, label = { Text("Maintenance name") }, modifier = Modifier.fillMaxWidth())
            AssetEnumDropdown("Repeat by", trigger, triggerValues) { trigger = it }
            if (trigger != MaintenanceTriggerType.TIME) {
                OutlinedTextField(km, { km = it.filter(Char::isDigit) }, label = { Text("Every (km)") }, modifier = Modifier.fillMaxWidth())
                asset.currentMileageKm?.let { current ->
                    Text("Start from current mileage: %,d km".format(current), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trigger != MaintenanceTriggerType.MILEAGE) {
                OutlinedTextField(months, { months = it.filter(Char::isDigit) }, label = { Text("Every (months)") }, modifier = Modifier.fillMaxWidth())
            }
        } },
        confirmButton = { TextButton(onClick = {
            val m = months.toIntOrNull(); val k = km.toLongOrNull()
            if (title.isNotBlank() && (trigger == MaintenanceTriggerType.MILEAGE || m != null && m > 0) &&
                (trigger == MaintenanceTriggerType.TIME || k != null && k > 0)) {
                val now = System.currentTimeMillis()
                save(AssetMaintenanceRuleEntity(assetId = asset.id, title = title.trim(), triggerType = trigger,
                    intervalMonths = m.takeIf { trigger != MaintenanceTriggerType.MILEAGE },
                    intervalKm = k.takeIf { trigger != MaintenanceTriggerType.TIME },
                    baselineDateEpochDay = LocalDate.now().toEpochDay(), baselineMileageKm = asset.currentMileageKm,
                    warningDays = 30, warningKm = 1000, notes = null, createdAt = now, updatedAt = now))
            }
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable private fun MaintenanceDialog(asset: AssetEntity, rules: List<AssetMaintenanceRuleEntity>, categories: List<CategoryEntity>, merchants: List<MerchantEntity>, dismiss: () -> Unit, save: (MaintenanceEventInput) -> Unit) { var title by remember { mutableStateOf("") }; var cost by remember { mutableStateOf("") }; var performed by remember { mutableStateOf(LocalDate.now()) }; var pickingDate by remember { mutableStateOf(false) }; var createExpense by remember { mutableStateOf(false) }; var categoryId by remember { mutableStateOf<Long?>(null) }; var merchantId by remember { mutableStateOf<Long?>(null) }; var ruleId by remember { mutableStateOf<Long?>(null) }; val key = remember { UUID.randomUUID().toString() }; AlertDialog(onDismissRequest = dismiss, title = { Text("Record maintenance") }, text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(title, { title = it }, label = { Text("Work performed") }); DateButton("Performed", performed) { pickingDate = true }; NullableRuleDropdown(ruleId, rules) { ruleId = it }; MoneyInput("Cost (optional)", cost) { cost = it }; NullableMerchantDropdown(merchantId, merchants) { merchantId = it }; Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Create expense"); Switch(createExpense, { createExpense = it }) }; if (createExpense) NullableCategoryDropdown(categoryId, categories) { categoryId = it } } }, confirmButton = { TextButton(onClick = { val amount = cost.takeIf(String::isNotBlank)?.let(::parseEgpToMinor); val merchant = merchants.firstOrNull { it.id == merchantId }; if (title.isNotBlank() && (!createExpense || amount != null && categoryId != null)) save(MaintenanceEventInput(asset.id, ruleId, title, performed, asset.currentMileageKm, amount, merchantId, merchant?.displayName, categoryId, createExpense, null, key)) }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }); if (pickingDate) SpendWiseDatePickerDialog(performed, { performed = it; pickingDate = false }, { pickingDate = false }) }

@Composable private fun LinkCommitmentDialog(items: List<CommitmentWithMerchant>, dismiss: () -> Unit, save: (Long) -> Unit) { var selected by remember { mutableStateOf<Long?>(null) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Link financing commitment") }, text = { NullableCommitmentDropdown(selected, items) { selected = it } }, confirmButton = { TextButton(onClick = { selected?.let(save) }) { Text("Link") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) }

@Composable private fun RenameDocumentDialog(initial: String, dismiss: () -> Unit, save: (String) -> Unit) { var title by remember { mutableStateOf(initial) }; AlertDialog(onDismissRequest = dismiss, title = { Text("Rename document") }, text = { OutlinedTextField(title, { title = it }, label = { Text("Title") }) }, confirmButton = { TextButton(onClick = { if (title.isNotBlank()) save(title) }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) }

@Composable private fun DocumentMetadataDialog(vehicleSpecific: Boolean, dismiss: () -> Unit, save: (AssetDocumentType, String) -> Unit) { var title by remember { mutableStateOf("") }; var type by remember { mutableStateOf(AssetDocumentType.OTHER) }; val choices = if (vehicleSpecific) AssetDocumentType.entries else listOf(AssetDocumentType.RECEIPT, AssetDocumentType.INVOICE, AssetDocumentType.WARRANTY_CARD, AssetDocumentType.OTHER); AlertDialog(onDismissRequest = dismiss, title = { Text("Attach document") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { AssetEnumDropdown("Document type", type, choices) { type = it }; OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }) } }, confirmButton = { TextButton(onClick = { save(type, title) }) { Text("Copy to SpendWise") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }) }

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
                    AssetDocumentType.RECEIPT, AssetDocumentType.INVOICE, AssetDocumentType.WARRANTY_CARD,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ItemTypeDropdown(type: AssetType, customTypeId: Long?, customTypes: List<CustomAssetTypeEntity>,
                                         onSelect: (AssetType, Long?) -> Unit, onCreate: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(itemTypeLabel(type, customTypeId, customTypes), {}, readOnly = true,
            label = { Text("Type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) {
            AssetType.entries.forEach { builtin -> DropdownMenuItem(text = { Text(itemTypeLabel(builtin, null, emptyList())) },
                onClick = { onSelect(builtin, null); expanded = false }) }
            customTypes.forEach { custom -> DropdownMenuItem(text = { Text(custom.name) },
                onClick = { onSelect(AssetType.OTHER, custom.id); expanded = false }) }
            DropdownMenuItem(text = { Text("+ Create custom type") }, onClick = { expanded = false; onCreate() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ItemCategoryDropdown(categoryId: Long?, categories: List<CategoryEntity>, select: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(categories.firstOrNull { it.id == categoryId }?.name ?: "Choose category", {}, readOnly = true,
            label = { Text("Category") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) {
            categories.forEach { category -> DropdownMenuItem(text = { Text(category.name) },
                onClick = { select(category.id); expanded = false }) }
        }
    }
}
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
    else if (this is MaintenanceTriggerType) when (this) {
        MaintenanceTriggerType.MILEAGE -> "Distance"
        MaintenanceTriggerType.TIME -> "Time"
        MaintenanceTriggerType.TIME_OR_MILEAGE -> "Whichever comes first"
    }
    else name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
private fun MaintenanceDueResult.dueLabel(): String = when (triggerReason) { MaintenanceTriggerReason.MILEAGE -> remainingKm?.let { if (it < 0) "overdue by ${-it} km" else "due in $it km" } ?: "Mileage unavailable"; else -> remainingDays?.let { if (it < 0) "overdue by ${-it} days" else "due in $it days" } ?: "Date unavailable" }
