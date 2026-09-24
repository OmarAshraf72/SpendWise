package com.example.spendwise

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.example.spendwise.ui.SpendWiseApp
import com.example.spendwise.ui.theme.SpendWiseTheme

class MainActivity : ComponentActivity() {
    private var notificationAssetId by mutableLongStateOf(0L)
    private var notificationNonce by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveAssetIntent(intent)
        enableEdgeToEdge()
        setContent {
            SpendWiseTheme {
                SpendWiseApp(notificationAssetId.takeIf { it > 0 }?.let { it to notificationNonce })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveAssetIntent(intent)
    }

    private fun receiveAssetIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_ASSET_ID, 0L) ?: 0L
        if (id > 0) {
            notificationAssetId = id
            notificationNonce++
        }
    }

    companion object { const val EXTRA_ASSET_ID = "asset_reminder_asset_id" }
}
