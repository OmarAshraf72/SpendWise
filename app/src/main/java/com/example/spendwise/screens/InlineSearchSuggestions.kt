package com.example.spendwise.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class InlineSuggestion<T>(val key: Any, val value: T, val primary: String, val secondary: String? = null)

@Composable
fun <T> InlineSuggestionList(
    suggestions: List<InlineSuggestion<T>>,
    onSelected: (T) -> Unit,
    addLabel: String? = null,
    onAdd: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty() && addLabel == null) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            items(suggestions, key = { it.key }) { suggestion ->
                Column(Modifier.fillMaxWidth().clickable { onSelected(suggestion.value) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(suggestion.primary)
                    suggestion.secondary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider()
            }
            if (addLabel != null && onAdd != null) {
                item("add-action") {
                    Text(addLabel, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd)
                            .padding(horizontal = 16.dp, vertical = 14.dp))
                }
            }
        }
    }
}
