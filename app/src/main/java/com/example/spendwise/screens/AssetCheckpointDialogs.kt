package com.example.spendwise.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.spendwise.data.*
import java.time.LocalDate

private val vehicleCheckpointTemplates = listOf(
    "License renewal" to AssetCheckpointType.RENEWAL,
    "Insurance renewal" to AssetCheckpointType.RENEWAL,
    "Vehicle inspection" to AssetCheckpointType.INSPECTION,
    "Brake inspection" to AssetCheckpointType.INSPECTION,
    "Tire check" to AssetCheckpointType.INSPECTION,
    "Battery check" to AssetCheckpointType.INSPECTION
)

@Composable
fun AssetCheckpointDialog(
    asset: AssetEntity,
    existing: AssetCheckpointEntity?,
    currentOccurrence: CheckpointOccurrence?,
    dismiss: () -> Unit,
    save: (AssetCheckpointEntity) -> Unit
) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var type by remember(existing?.id) { mutableStateOf(existing?.checkpointType ?: AssetCheckpointType.CUSTOM) }
    var trigger by remember(existing?.id) { mutableStateOf(existing?.triggerMode ?: CheckpointTriggerMode.DATE) }
    var dueDate by remember(existing?.id) { mutableStateOf((currentOccurrence?.dueDateEpochDay ?: existing?.dueDateEpochDay)?.let(LocalDate::ofEpochDay)) }
    var dueKm by remember(existing?.id) { mutableStateOf((currentOccurrence?.dueMileageKm ?: existing?.dueMileageKm)?.toString().orEmpty()) }
    var repeatMonths by remember(existing?.id) { mutableStateOf(existing?.repeatMonths?.toString().orEmpty()) }
    var repeatKm by remember(existing?.id) { mutableStateOf(existing?.repeatKm?.toString().orEmpty()) }
    var warningDays by remember(existing?.id) { mutableStateOf(existing?.warningDays?.toString() ?: if (existing == null) "30" else "") }
    var warningKm by remember(existing?.id) { mutableStateOf(existing?.warningKm?.toString() ?: if (existing == null) "1000" else "") }
    var notes by remember(existing?.id) { mutableStateOf(existing?.notes.orEmpty()) }
    var pickingDate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val usesDate = trigger != CheckpointTriggerMode.MILEAGE
    val usesMileage = trigger != CheckpointTriggerMode.DATE

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (existing == null) "Add checkpoint" else "Edit checkpoint") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null && asset.type == AssetType.VEHICLE) {
                    Text("Optional vehicle titles")
                    vehicleCheckpointTemplates.forEach { (template, templateType) ->
                        TextButton(onClick = { title = template; type = templateType }) { Text(template) }
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                CheckpointDropdown("Type", type, AssetCheckpointType.entries) { type = it }
                CheckpointDropdown("Trigger", trigger, CheckpointTriggerMode.entries) { trigger = it }
                if (usesDate) OutlinedButton(onClick = { pickingDate = true }, Modifier.fillMaxWidth()) {
                    Text("Due date: ${dueDate ?: "Choose date"}")
                }
                if (usesMileage) OutlinedTextField(dueKm, { dueKm = it.filter(Char::isDigit) },
                    label = { Text("Due mileage (km)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                if (usesDate) OutlinedTextField(repeatMonths, { repeatMonths = it.filter(Char::isDigit) },
                    label = { Text("Repeat every months (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                if (usesMileage) OutlinedTextField(repeatKm, { repeatKm = it.filter(Char::isDigit) },
                    label = { Text("Repeat every km (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                if (usesDate) OutlinedTextField(warningDays, { warningDays = it.filter(Char::isDigit) },
                    label = { Text("Remind before (days, optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                if (usesMileage) OutlinedTextField(warningKm, { warningKm = it.filter(Char::isDigit) },
                    label = { Text("Remind before (km, optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            val now = System.currentTimeMillis()
            val draft = AssetCheckpointEntity(
                id = existing?.id ?: 0, assetId = asset.id, title = title.trim(), checkpointType = type,
                triggerMode = trigger, dueDateEpochDay = dueDate?.toEpochDay().takeIf { usesDate },
                dueMileageKm = dueKm.toLongOrNull().takeIf { usesMileage },
                repeatMonths = repeatMonths.toIntOrNull().takeIf { usesDate },
                repeatKm = repeatKm.toLongOrNull().takeIf { usesMileage },
                warningDays = warningDays.toIntOrNull().takeIf { usesDate },
                warningKm = warningKm.toLongOrNull().takeIf { usesMileage },
                notes = notes.trim().takeIf(String::isNotBlank), isActive = existing?.isActive ?: true,
                createdAt = existing?.createdAt ?: now, updatedAt = now
            )
            runCatching { AssetAttentionEngine.validateCheckpoint(draft) }
                .onSuccess { save(draft) }
                .onFailure { error = "Enter a name and valid due point. Repeat intervals must be positive." }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
    if (pickingDate) SpendWiseDatePickerDialog(dueDate ?: LocalDate.now(), { dueDate = it; pickingDate = false }, { pickingDate = false })
}

@Composable
fun CompleteCheckpointDialog(
    checkpoint: AssetCheckpointEntity,
    assetMileage: Long?,
    dismiss: () -> Unit,
    complete: (LocalDate, Long?, String?) -> Unit
) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    var mileage by remember { mutableStateOf(assetMileage?.toString().orEmpty()) }
    var note by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Mark ${checkpoint.title} completed") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picking = true }) { Text("Completed: $date") }
            if (checkpoint.triggerMode != CheckpointTriggerMode.DATE) OutlinedTextField(mileage, { mileage = it.filter(Char::isDigit) },
                label = { Text("Mileage at completion (km)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") })
            error?.let { Text(it) }
        } },
        confirmButton = { TextButton(onClick = {
            if (saving) return@TextButton
            val km = mileage.toLongOrNull()
            if (checkpoint.repeatKm != null && km == null) error = "Mileage is needed to calculate the next checkpoint"
            else { saving = true; complete(date, km, note.trim().takeIf(String::isNotBlank)) }
        }, enabled = !saving) { Text("Complete") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    if (picking) SpendWiseDatePickerDialog(date, { date = it; picking = false }, { picking = false })
}

@Composable
fun AssetReminderRulesDialog(
    item: AssetAttentionItem,
    existing: List<AssetReminderRuleEntity>,
    defaultDays: Int?, defaultKm: Long?,
    dismiss: () -> Unit,
    save: (Set<Pair<AssetReminderTriggerKind, Long>>, Set<Pair<AssetReminderTriggerKind, Long>>) -> Unit
) {
    val options = buildList {
        if (item.dueDateEpochDay != null) {
            add(AssetReminderTriggerKind.DAYS_BEFORE to 30L)
            add(AssetReminderTriggerKind.DAYS_BEFORE to 7L)
            defaultDays?.takeIf { it > 0 }?.let { add(AssetReminderTriggerKind.DAYS_BEFORE to it.toLong()) }
        }
        if (item.dueMileageKm != null) {
            add(AssetReminderTriggerKind.KM_BEFORE to 1000L)
            add(AssetReminderTriggerKind.KM_BEFORE to 500L)
            defaultKm?.takeIf { it > 0 }?.let { add(AssetReminderTriggerKind.KM_BEFORE to it) }
        }
        add(AssetReminderTriggerKind.ON_DUE to 0L)
        existing.forEach { add(it.triggerKind to it.leadValue) }
    }.distinct().toSet()
    val defaults = buildSet {
        if (item.dueDateEpochDay != null) defaultDays?.takeIf { it > 0 }?.let { add(AssetReminderTriggerKind.DAYS_BEFORE to it.toLong()) }
        if (item.dueMileageKm != null) defaultKm?.takeIf { it > 0 }?.let { add(AssetReminderTriggerKind.KM_BEFORE to it) }
        add(AssetReminderTriggerKind.ON_DUE to 0L)
    }
    var chosen by remember(item.sourceType, item.sourceId, existing) {
        mutableStateOf(if (existing.isEmpty()) defaults else existing.filter { it.isEnabled }.map { it.triggerKind to it.leadValue }.toSet())
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("${item.title} reminders") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Choose when SpendWise may notify you")
            options.forEach { option ->
                val label = when (option.first) {
                    AssetReminderTriggerKind.DAYS_BEFORE -> "${option.second} days before"
                    AssetReminderTriggerKind.KM_BEFORE -> "${option.second} km before"
                    AssetReminderTriggerKind.ON_DUE -> "On due date or mileage"
                }
                FilterChip(selected = option in chosen, onClick = {
                    chosen = if (option in chosen) chosen - option else chosen + option
                }, label = { Text(label) })
            }
        } },
        confirmButton = { TextButton(onClick = { save(options, chosen) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Enum<T>> CheckpointDropdown(label: String, value: T, values: List<T>, choose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(value.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase), {}, readOnly = true,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) {
            values.forEach { option -> DropdownMenuItem(
                text = { Text(option.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)) },
                onClick = { choose(option); expanded = false }
            ) }
        }
    }
}
