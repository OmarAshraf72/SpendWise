package com.example.spendwise.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import com.example.spendwise.navigation.MainDestination
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigation: () -> Unit, onMore: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }
        )
    }) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Card(Modifier.fillMaxWidth()) {
                TextButton(onClick = onNavigation, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("Navigation")
                }
            }
            Card(Modifier.fillMaxWidth()) {
                TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("More and Categories")
                }
            }
        }
    }
}

@Composable
fun MoreScreen(
    secondaryDestinations: List<MainDestination>,
    onOpen: (MainDestination) -> Unit,
    onCustomize: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        Text("More", style = MaterialTheme.typography.headlineMedium)
        secondaryDestinations.forEach { destination -> Card(Modifier.fillMaxWidth()) {
            TextButton(onClick = { onOpen(destination) }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text(destination.title)
            }
        } }
        TextButton(onClick = onCustomize) { Text("Customize navigation") }
    }
}

@Composable
private fun ScreenPlaceholder(name: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = name, style = MaterialTheme.typography.headlineMedium)
        Text(text = "$name screen")
    }
}
