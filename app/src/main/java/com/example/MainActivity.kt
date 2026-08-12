package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.CannsheetApp
import com.example.widget.EXTRA_START_ROUTE
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    render(intent.getStringExtra(EXTRA_START_ROUTE))
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    render(intent.getStringExtra(EXTRA_START_ROUTE))
  }

  private fun render(startRoute: String?) {
    setContent {
      MyApplicationTheme {
        CannsheetApp(startDestination = startRoute ?: "consumption")
      }
    }
  }
}
