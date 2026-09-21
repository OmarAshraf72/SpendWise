package com.example.spendwise.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.spendwise.data.SpendWiseDateLogic
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private enum class PickerMode { DAY, MONTH, YEAR }

@Composable
fun SpendWiseDatePickerDialog(
    initialDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    showTodayAction: Boolean = false
) {
    var selectedEpoch by rememberSaveable(initialDate) { mutableStateOf(initialDate.toEpochDay()) }
    var visibleYear by rememberSaveable(initialDate) { mutableStateOf(initialDate.year) }
    var visibleMonth by rememberSaveable(initialDate) { mutableStateOf(initialDate.monthValue) }
    var mode by rememberSaveable(initialDate) { mutableStateOf(PickerMode.DAY) }
    val selected = LocalDate.ofEpochDay(selectedEpoch)
    val visible = YearMonth.of(visibleYear, visibleMonth)
    val currentYear = LocalDate.now().year

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select date") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { mode = PickerMode.MONTH }) {
                        Text(visible.month.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                    }
                    TextButton(onClick = { mode = PickerMode.YEAR }) { Text(visible.year.toString()) }
                }
                when (mode) {
                    PickerMode.DAY -> DayGrid(visible, selected,
                        previous = { val next = visible.minusMonths(1); visibleYear = next.year; visibleMonth = next.monthValue },
                        next = { val next = visible.plusMonths(1); visibleYear = next.year; visibleMonth = next.monthValue },
                        select = { day -> selectedEpoch = visible.atDay(day).toEpochDay() })
                    PickerMode.MONTH -> MonthGrid(visible.month) { month ->
                        val changed = SpendWiseDateLogic.changeMonth(SpendWiseDateLogic.changeYear(selected, visible.year), month)
                        selectedEpoch = changed.toEpochDay(); visibleMonth = month.value; mode = PickerMode.DAY
                    }
                    PickerMode.YEAR -> YearGrid(currentYear - 10, currentYear + 30, visible.year) { year ->
                        val changed = SpendWiseDateLogic.changeYear(SpendWiseDateLogic.changeMonth(selected, visible.month), year)
                        selectedEpoch = changed.toEpochDay(); visibleYear = year; mode = PickerMode.DAY
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onDateSelected(LocalDate.ofEpochDay(selectedEpoch)) }) { Text("Select") } },
        dismissButton = {
            Row {
                if (showTodayAction) TextButton(onClick = { onDateSelected(LocalDate.now()) }) { Text("Today") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun DayGrid(month: YearMonth, selected: LocalDate, previous: () -> Unit, next: () -> Unit, select: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = previous) { Text("‹") }
            Text("${month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${month.year}", modifier = Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = next) { Text("›") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        val leading = month.atDay(1).dayOfWeek.value - 1
        val cells = List(leading) { 0 } + (1..month.lengthOfMonth()).toList()
        LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.height(250.dp)) {
            items(cells) { day ->
                if (day == 0) Text("") else {
                    val isSelected = selected.year == month.year && selected.month == month.month && selected.dayOfMonth == day
                    if (isSelected) FilledTonalButton(onClick = { select(day) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(day.toString()) }
                    else TextButton(onClick = { select(day) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(day.toString()) }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(selected: Month, onSelected: (Month) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.height(250.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Month.entries) { month ->
            val label = month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            if (month == selected) FilledTonalButton(onClick = { onSelected(month) }) { Text(label) }
            else TextButton(onClick = { onSelected(month) }) { Text(label) }
        }
    }
}

@Composable
private fun YearGrid(firstYear: Int, lastYear: Int, selected: Int, onSelected: (Int) -> Unit) {
    val years = remember(firstYear, lastYear) { (firstYear..lastYear).toList() }
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = (selected - firstYear - 3).coerceIn(0, years.lastIndex))
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.height(300.dp), state = state, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(years) { year ->
            if (year == selected) FilledTonalButton(onClick = { onSelected(year) }) { Text(year.toString()) }
            else TextButton(onClick = { onSelected(year) }) { Text(year.toString()) }
        }
    }
}

@Composable
fun SpendWiseMonthPickerDialog(initialMonth: YearMonth, onSelected: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    var year by rememberSaveable(initialMonth) { mutableStateOf(initialMonth.year) }
    var month by rememberSaveable(initialMonth) { mutableStateOf(initialMonth.month) }
    var showYears by rememberSaveable(initialMonth) { mutableStateOf(false) }
    val currentYear = LocalDate.now().year
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select month") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { showYears = !showYears }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(year.toString()) }
                if (showYears) YearGrid(currentYear - 10, currentYear + 30, year) { year = it; showYears = false }
                else MonthGrid(month) { selectedMonth -> month = selectedMonth; onSelected(YearMonth.of(year, selectedMonth)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
