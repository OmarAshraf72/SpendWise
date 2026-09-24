package com.example.spendwise.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.spendwise.navigation.MainDestination
import kotlin.math.roundToInt

private object FloatingNavigationStyle {
    val horizontalMargin = 12.dp
    val bottomMargin = 8.dp
    val cornerRadius = 32.dp
    val barHeight = 68.dp
    val capsuleHeight = 58.dp
    val iconSize = 24.dp
    const val surfaceOpacity = 0.91f
    const val borderOpacity = 0.28f
    const val highlightOpacity = 0.45f
    const val animationMillis = 190
}

@Composable
fun FloatingNavigationBar(
    destinations: List<MainDestination>,
    currentRoute: String?,
    onDestination: (MainDestination) -> Unit,
    onReorderPinned: (List<String>) -> Unit
) {
    if (destinations.isEmpty()) return
    val persistedIds = destinations.map(MainDestination::stableId)
    var workingIds by remember(persistedIds) { mutableStateOf(persistedIds) }
    val latestWorkingIds by rememberUpdatedState(workingIds)
    val latestReorder by rememberUpdatedState(onReorderPinned)
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragPixels by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    BackHandler(enabled = draggedId != null) {
        draggedId = null
        dragPixels = 0f
        workingIds = persistedIds
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = FloatingNavigationStyle.horizontalMargin, vertical = FloatingNavigationStyle.bottomMargin),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(FloatingNavigationStyle.cornerRadius),
        color = colors.surface.copy(alpha = FloatingNavigationStyle.surfaceOpacity),
        border = BorderStroke(1.dp, (if (draggedId == null) colors.outlineVariant else colors.primary)
            .copy(alpha = FloatingNavigationStyle.borderOpacity)),
        tonalElevation = 4.dp,
        shadowElevation = 10.dp
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(FloatingNavigationStyle.barHeight).padding(5.dp)) {
            val cellWidth = maxWidth / destinations.size
            val cellWidthPx = with(density) { cellWidth.toPx() }
            val showLabels = maxWidth >= 82.dp * destinations.size
            val selectedIndex = workingIds.indexOfFirst { id ->
                MainDestination.fromStableId(id)?.route == currentRoute
            }
            val targetWidth = if (showLabels) minOf(cellWidth - 4.dp, 100.dp) else minOf(cellWidth - 4.dp, 52.dp)
            val targetOffset = if (selectedIndex >= 0) cellWidth * selectedIndex + (cellWidth - targetWidth) / 2 else 0.dp
            val offset by animateDpAsState(targetOffset, tween(FloatingNavigationStyle.animationMillis), label = "Navigation capsule position")
            val width by animateDpAsState(targetWidth, tween(FloatingNavigationStyle.animationMillis), label = "Navigation capsule width")
            if (selectedIndex >= 0) {
                Surface(
                    Modifier.offset(x = offset).width(width).height(FloatingNavigationStyle.capsuleHeight)
                        .align(Alignment.CenterStart),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(27.dp),
                    color = colors.primaryContainer.copy(alpha = FloatingNavigationStyle.highlightOpacity),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = FloatingNavigationStyle.borderOpacity))
                ) {}
            }
            Row(Modifier.fillMaxWidth().align(Alignment.Center), horizontalArrangement = Arrangement.Center) {
                destinations.forEachIndexed { originalIndex, destination ->
                    val selected = destination.route == currentRoute
                    val id = destination.stableId
                    val targetDisplacement = cellWidth * (workingIds.indexOf(id) - originalIndex)
                    val settledDisplacement by animateDpAsState(targetDisplacement,
                        tween(FloatingNavigationStyle.animationMillis), label = "Navigation item shift")
                    val displacement = if (draggedId == id) with(density) { dragPixels.toDp() }
                        else settledDisplacement
                    Column(
                        Modifier.width(cellWidth).height(FloatingNavigationStyle.capsuleHeight)
                            .offset(x = displacement)
                            .zIndex(if (draggedId == id) 1f else 0f)
                            .graphicsLayer {
                                val scale = if (draggedId == id) 1.08f else 1f
                                scaleX = scale
                                scaleY = scale
                            }
                            .pointerInput(id, persistedIds) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedId = id
                                        dragPixels = 0f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragPixels = (dragPixels + amount.x).coerceIn(
                                            -originalIndex * cellWidthPx,
                                            (destinations.lastIndex - originalIndex) * cellWidthPx
                                        )
                                        val target = (originalIndex + dragPixels / cellWidthPx)
                                            .roundToInt().coerceIn(destinations.indices)
                                        val from = latestWorkingIds.indexOf(id)
                                        if (from >= 0 && from != target) {
                                            workingIds = latestWorkingIds.toMutableList().apply {
                                                remove(id)
                                                add(target, id)
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        val newOrder = latestWorkingIds
                                        draggedId = null
                                        dragPixels = 0f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (newOrder != persistedIds) latestReorder(newOrder)
                                    },
                                    onDragCancel = {
                                        draggedId = null
                                        dragPixels = 0f
                                        workingIds = persistedIds
                                    }
                                )
                            }
                            .selectable(selected = selected, role = Role.Tab, onClick = { onDestination(destination) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(destination.icon, contentDescription = destination.title,
                            modifier = Modifier.size(FloatingNavigationStyle.iconSize),
                            tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
                        if (showLabels) Text(
                            destination.title, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                            fontSize = 10.sp,
                            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
