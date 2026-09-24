package com.example.spendwise.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.spendwise.navigation.MainDestination
import com.example.spendwise.navigation.NavigationConfiguration
import com.example.spendwise.navigation.SecondaryVisibility
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NavigationCustomizationScreen(
    configuration: NavigationConfiguration,
    onBack: () -> Unit,
    onSaveOrder: (List<String>) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onReset: () -> Unit
) {
    var workingOrder by remember(configuration.orderedIds) { mutableStateOf(configuration.orderedIds) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var pointerYInList by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val autoScrollEdgePx = with(LocalDensity.current) { 64.dp.toPx() }
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }

    fun moveDraggedRow(id: String) {
        val index = workingOrder.indexOf(id)
        if (index < 0) return
        val visible = listState.layoutInfo.visibleItemsInfo
        val dragged = visible.firstOrNull { it.key == id } ?: return
        val center = dragged.offset + dragged.size / 2f + dragOffsetPx
        val next = workingOrder.getOrNull(index + 1)?.let { nextId -> visible.firstOrNull { it.key == nextId } }
        val previous = workingOrder.getOrNull(index - 1)?.let { previousId -> visible.firstOrNull { it.key == previousId } }
        val target = when {
            next != null && center > next.offset + next.size / 2f -> index + 1 to next
            previous != null && center < previous.offset + previous.size / 2f -> index - 1 to previous
            else -> null
        } ?: return
        workingOrder = NavigationConfiguration(workingOrder, configuration.pinnedIds)
            .move(id, target.first).orderedIds
        // Keep the dragged card under the finger when its underlying list slot changes.
        dragOffsetPx -= target.second.offset - dragged.offset
    }

    fun stopDrag(save: Boolean) {
        autoScrollJob?.cancel()
        autoScrollJob = null
        val finalOrder = workingOrder
        draggedId = null
        dragOffsetPx = 0f
        if (save && finalOrder != configuration.orderedIds) onSaveOrder(finalOrder)
        if (!save) workingOrder = configuration.orderedIds
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Customize navigation") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "instructions") {
                Text(
                    "Long-press and drag a row to reorder. Keep three to five destinations pinned.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(workingOrder, key = { it }) { id ->
                val destination = MainDestination.fromStableId(id) ?: return@items
                val isDragging = draggedId == id
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (isDragging) Modifier else Modifier.animateItem())
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) dragOffsetPx else 0f
                            shadowElevation = if (isDragging) 12.dp.toPx() else 0f
                        }
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { touch ->
                                    draggedId = id
                                    dragOffsetPx = 0f
                                    val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                                    pointerYInList = (row?.offset ?: 0) + touch.y
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    autoScrollJob?.cancel()
                                    autoScrollJob = scope.launch {
                                        while (isActive) {
                                            withFrameNanos { }
                                            val layout = listState.layoutInfo
                                            val velocity = when {
                                                pointerYInList < layout.viewportStartOffset + autoScrollEdgePx -> -12f
                                                pointerYInList > layout.viewportEndOffset - autoScrollEdgePx -> 12f
                                                else -> 0f
                                            }
                                            if (velocity != 0f) {
                                                val scrolled = listState.scrollBy(velocity)
                                                dragOffsetPx += scrolled
                                                moveDraggedRow(id)
                                            }
                                        }
                                    }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffsetPx += amount.y
                                    pointerYInList += amount.y
                                    moveDraggedRow(id)
                                },
                                onDragEnd = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); stopDrag(save = true) },
                                onDragCancel = { stopDrag(save = false) }
                            )
                        }
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Move ${destination.title} up") {
                                    val index = workingOrder.indexOf(id)
                                    if (index <= 0) false else {
                                        workingOrder = NavigationConfiguration(workingOrder, configuration.pinnedIds)
                                            .move(id, index - 1).orderedIds
                                        onSaveOrder(workingOrder)
                                        true
                                    }
                                },
                                CustomAccessibilityAction("Move ${destination.title} down") {
                                    val index = workingOrder.indexOf(id)
                                    if (index < 0 || index == workingOrder.lastIndex) false else {
                                        workingOrder = NavigationConfiguration(workingOrder, configuration.pinnedIds)
                                            .move(id, index + 1).orderedIds
                                        onSaveOrder(workingOrder)
                                        true
                                    }
                                }
                            )
                        }
                        .testTag("navigation-row-$id")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Outlined.DragHandle, contentDescription = null, modifier = Modifier.size(24.dp))
                        Icon(destination.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(destination.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (id in configuration.pinnedIds) "Pinned"
                                else if (destination.secondaryVisibility == SecondaryVisibility.GLOBAL_ACTION) "Available from top menu"
                                else "Available in More",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = id in configuration.pinnedIds,
                            onCheckedChange = { pinned ->
                                runCatching { configuration.withPinned(id, pinned) }
                                    .onSuccess { message = null; onSetPinned(id, pinned) }
                                    .onFailure { message = "Keep three to five destinations pinned." }
                            }
                        )
                    }
                }
            }
            message?.let { item(key = "message") { Text(it, color = MaterialTheme.colorScheme.error) } }
            item(key = "reset") {
                TextButton(onClick = { stopDrag(save = false); onReset(); message = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset to default")
                }
            }
        }
    }
}
