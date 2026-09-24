package com.example.spendwise.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import com.example.spendwise.navigation.MainDestination
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.example.spendwise.viewmodel.AssetReminderSettingsViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigation: () -> Unit, onMore: () -> Unit,
                   remindersViewModel: AssetReminderSettingsViewModel = viewModel()) {
    val enabled by remindersViewModel.enabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) remindersViewModel.setEnabled(true)
    }
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
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                TextButton(onClick = onNavigation, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("Navigation", modifier = Modifier.fillMaxWidth())
                }
            }
            Card(Modifier.fillMaxWidth()) {
                TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("More and Categories", modifier = Modifier.fillMaxWidth())
                }
            }
            Card(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text("Asset reminders", style = MaterialTheme.typography.titleMedium)
                        Text("Local notifications for assets needing attention", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = enabled, onCheckedChange = { turnOn ->
                        if (!turnOn) remindersViewModel.setEnabled(false)
                        else if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else remindersViewModel.setEnabled(true)
                    })
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        Text("More", style = MaterialTheme.typography.headlineMedium)
        secondaryDestinations.forEach { destination -> Card(Modifier.fillMaxWidth()) {
            TextButton(onClick = { onOpen(destination) }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text(destination.title, modifier = Modifier.fillMaxWidth())
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
