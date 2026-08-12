package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.CannsheetApp
import com.example.widget.EXTRA_START_ROUTE
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
  private val routeRequests = Channel<String>(capacity = Channel.BUFFERED)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    render(intent.consumeStartRoute())
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.consumeStartRoute()?.let(routeRequests::trySend)
  }

  private fun render(startRoute: String?) {
    setContent {
      MyApplicationTheme {
        CannsheetApp(
          startDestination = startRoute ?: "consumption",
          routeRequests = routeRequests.receiveAsFlow(),
        )
      }
    }
  }
}

internal fun Intent.consumeStartRoute(): String? =
  getStringExtra(EXTRA_START_ROUTE)?.also { removeExtra(EXTRA_START_ROUTE) }
