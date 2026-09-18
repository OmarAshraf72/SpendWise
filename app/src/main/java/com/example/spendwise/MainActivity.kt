package com.example.spendwise

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.spendwise.ui.SpendWiseApp
import com.example.spendwise.ui.theme.SpendWiseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpendWiseTheme {
                SpendWiseApp()
            }
        }
    }
}
