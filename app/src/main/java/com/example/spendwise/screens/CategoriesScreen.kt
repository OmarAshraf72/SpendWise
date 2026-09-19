package com.example.spendwise.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spendwise.data.CategoryEntity
import com.example.spendwise.data.CategoryType
import com.example.spendwise.viewmodel.CategoriesViewModel

private fun categoryIcon(iconName: String): ImageVector = when (iconName) {
    "shopping_cart" -> Icons.Outlined.ShoppingCart
    "spa" -> Icons.Outlined.Spa
    "restaurant" -> Icons.Outlined.Restaurant
    "directions_bus" -> Icons.Outlined.DirectionsBus
    "home" -> Icons.Outlined.Home
    "local_hospital" -> Icons.Outlined.LocalHospital
    "shopping_bag" -> Icons.Outlined.ShoppingBag
    "receipt_long" -> Icons.AutoMirrored.Outlined.ReceiptLong
    "movie" -> Icons.Outlined.Movie
    "face" -> Icons.Outlined.Face
    "menu_book" -> Icons.AutoMirrored.Outlined.MenuBook
    else -> Icons.Outlined.Category
}

@Composable
fun CategoriesScreen(viewModel: CategoriesViewModel = viewModel()) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var showEditor by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var draftName by remember { mutableStateOf("") }
    var deletingCategory by remember { mutableStateOf<CategoryEntity?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "Categories",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )

            CategorySection(title = "Default Categories", description = "Built in categories") {
                categories.filter { it.type == CategoryType.DEFAULT }.forEach { category ->
                    CategoryRow(name = category.name, icon = categoryIcon(category.iconName))
                }
            }

            CategorySection(title = "Custom Categories", description = "Categories you create") {
                categories.filter { it.type == CategoryType.CUSTOM }.forEach { category ->
                    CategoryRow(
                        name = category.name,
                        icon = categoryIcon(category.iconName),
                        custom = true,
                        onEdit = {
                            editingCategory = category
                            draftName = category.name
                            showEditor = true
                        },
                        onDelete = { deletingCategory = category }
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = {
                editingCategory = null
                draftName = ""
                showEditor = true
            },
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            text = { Text("Add Category") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        )
    }

    if (showEditor) {
        CategoryNameDialog(
            initialName = draftName,
            editing = editingCategory != null,
            onDismiss = { showEditor = false },
            onSave = { name ->
                val category = editingCategory
                if (category != null) {
                    viewModel.renameCustom(category.id, name)
                } else {
                    viewModel.addCustom(name)
                }
                showEditor = false
            }
        )
    }

    deletingCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { deletingCategory = null },
            title = { Text("Delete category?") },
            text = { Text("Delete ${category.name} from your custom categories?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.archiveCustom(category.id)
                    deletingCategory = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingCategory = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CategorySection(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        content()
    }
}

@Composable
private fun CategoryRow(
    name: String,
    icon: ImageVector,
    custom: Boolean = false,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (custom) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (custom) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.primary
            )
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (custom) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Rename $name")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete $name")
                }
            }
        }
    }
}

@Composable
private fun CategoryNameDialog(
    initialName: String,
    editing: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Rename Category" else "Add Category") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Category name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(if (editing) "Save" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
